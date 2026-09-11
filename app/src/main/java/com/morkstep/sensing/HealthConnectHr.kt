package com.morkstep.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.morkstep.DebugLog
import com.morkstep.data.PhaseAverages
import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutEntity
import com.morkstep.data.WorkoutProfile
import com.morkstep.engine.phaseAt
import com.morkstep.engine.planFor
import java.time.Duration
import java.time.Instant

/**
 * Post-workout heart-rate backfill from Health Connect.
 *
 * The phone has no HR sensor, so when the Wear relay is off (and no BLE strap
 * was connected) there is no real-time source. After the workout ends we ask
 * Health Connect for whatever HR records it holds over the exact workout
 * window:
 *  - statistical aggregate: overall average, min, max (`HeartRateRecord`),
 *  - per-minute buckets mapped back onto the profile's phase plan, giving an HR
 *    average for every phase occurrence (warm-up, each push, each recovery,
 *    cool-down) plus the pooled push/recovery averages — the same fields a
 *    real-time session would have recorded.
 *
 * "Not perfect" by design: Health Connect only has data if some device or app
 * (a watch, a strap app, etc.) wrote it, samples can be sparse, and the read
 * window is capped at 30 days before the first grant unless the history
 * permission is also granted. Real-time cues and live readings are unaffected.
 */
data class HealthConnectHr(
    val avgOverall: Int?,
    val avgPush: Int?,
    val avgRecovery: Int?,
    val minHr: Int?,
    val maxHr: Int?,
    /** HR average per phase occurrence, in workout order; empty when no bucket landed in a phase. */
    val phaseAverages: List<PhaseAverages>,
)

/**
 * Read the workout window's HR from Health Connect, or null when Health
 * Connect is unavailable, HR read permission was not granted, or no HR
 * records exist in the window. Never throws: callers treat null as "skip".
 *
 * Every outcome is traced to [log] under the `[hc]` tag, so an exported debug
 * log says *why* a workout came back without HR — unavailable, unpermitted, a
 * failed query, or a window Health Connect holds no records for (a source that
 * has not synced yet).
 */
suspend fun healthConnectHrForWorkout(
    context: Context,
    entity: WorkoutEntity,
    profile: WorkoutProfile,
    log: DebugLog? = null,
): HealthConnectHr? {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
        log?.log("[hc] Health Connect unavailable")
        return null
    }
    if (ContextCompat.checkSelfPermission(context, "android.permission.health.READ_HEART_RATE") !=
        PackageManager.PERMISSION_GRANTED
    ) {
        log?.log("[hc] read permission not granted")
        return null
    }
    val start = Instant.ofEpochMilli(entity.startTime)
    val end = Instant.ofEpochMilli(entity.endTime)
    if (end <= start) {
        log?.log("[hc] empty window")
        return null
    }

    val client = HealthConnectClient.getOrCreate(context)
    // Average/min/max over the window. Min/max are the optional pair — a
    // provider that predates the statistical metrics rejects that request — so
    // a failed read is retried for the average alone instead of losing the
    // whole backfill to one unsupported metric.
    val aggregate = runCatching {
        client.aggregate(
            AggregateRequest(
                metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_MIN),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
    }.getOrElse { e ->
        log?.log("[hc] aggregate failed (${e::class.simpleName}); retrying average only")
        runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
        }.getOrElse { retry ->
            log?.log("[hc] average-only aggregate failed (${retry::class.simpleName})")
            null
        }
    }
    val avgOverall = aggregate?.get(HeartRateRecord.BPM_AVG)?.toInt()
    val minHr = aggregate?.get(HeartRateRecord.BPM_MIN)?.toInt()
    val maxHr = aggregate?.get(HeartRateRecord.BPM_MAX)?.toInt()

    // Per-minute buckets → phase averages. Bucketing is best-effort: if the
    // bucket read fails (or a metric is unsupported per bucket) skip phases.
    val buckets = runCatching {
        client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = setOf(HeartRateRecord.BPM_AVG),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = Duration.ofMinutes(1),
            )
        )
    }.getOrElse { e ->
        log?.log("[hc] bucket read failed (${e::class.simpleName})")
        emptyList()
    }
    val phaseBuckets = buckets.mapNotNull { b ->
        val bucketStart = b.startTime
        val offsetSec = Duration.between(start, bucketStart).seconds
        val avg = b.result[HeartRateRecord.BPM_AVG]?.toInt() ?: return@mapNotNull null
        offsetSec to avg
    }
    val (avgPush, avgRecovery) = phaseAveragesFromBuckets(phaseBuckets, profile, entity.durationSec)
    val phaseAverages = phaseAveragesPerOccurrence(phaseBuckets, profile, entity.durationSec)

    if (avgOverall == null && minHr == null && maxHr == null && avgPush == null && avgRecovery == null &&
        phaseAverages.isEmpty()
    ) {
        log?.log("[hc] no HR in ${entity.durationSec}s window (${buckets.size} buckets)")
        return null
    }
    log?.log(
        "[hc] HR avg ${avgOverall ?: "–"} min ${minHr ?: "–"} max ${maxHr ?: "–"} · " +
            "${phaseBuckets.size} buckets · ${phaseAverages.size} phases"
    )
    return HealthConnectHr(avgOverall, avgPush, avgRecovery, minHr, maxHr, phaseAverages)
}

/**
 * Map per-minute HR buckets (offset seconds since workout start → avg bpm) onto
 * the profile's phase plan, one entry per phase occurrence that a bucket landed
 * in — warm-up, each push round, each recovery round, cool-down — in workout
 * order. Pure, and built on the same `phaseAt` plan math the engine uses, so a
 * bucket is attributed to exactly the phase (and round) a live session would
 * have attributed it to.
 */
internal fun phaseAveragesPerOccurrence(
    buckets: List<Pair<Long, Int>>,
    profile: WorkoutProfile,
    totalSeconds: Int,
): List<PhaseAverages> {
    val plan = planFor(profile)
    val sums = LinkedHashMap<Pair<PhaseType, Int>, Long>()
    val counts = mutableMapOf<Pair<PhaseType, Int>, Int>()
    buckets.forEach { (offsetSec, bpm) ->
        if (offsetSec >= totalSeconds) return@forEach
        val at = phaseAt(offsetSec.toInt(), profile, plan.coreEndSec, plan.finishSec)
        // Occurrence within the phase type: the round the bucket fell in
        // (`phaseAt` counts completed pushes, so a FAST phase is one ahead).
        val key = at.phase to when (at.phase) {
            PhaseType.FAST -> at.pushDone + 1
            PhaseType.SLOW -> at.pushDone
            PhaseType.WARMUP, PhaseType.COOLDOWN -> 1
        }
        sums[key] = (sums[key] ?: 0L) + bpm
        counts[key] = (counts[key] ?: 0) + 1
    }
    return sums.map { (key, sum) -> PhaseAverages(key.first, avgHrBpm = (sum / counts.getValue(key)).toInt()) }
}

/**
 * Fold backfilled per-phase HR into the phases the session itself recorded:
 * every recorded phase keeps its live speed/pace and takes the backfill's HR
 * only where it has none (a real-time HR reading is never overwritten). The
 * two lists are matched by phase type plus occurrence, not by position, so a
 * phase either source missed still lands in workout order — a phase the
 * session recorded no signal for at all is added from the backfill rather than
 * dropped, and a backfill with no bucket for a phase leaves that phase's
 * recorded averages untouched.
 */
internal fun mergeBackfilledPhaseAverages(
    recorded: List<PhaseAverages>,
    backfilled: List<PhaseAverages>,
): List<PhaseAverages> {
    if (backfilled.isEmpty()) return recorded
    if (recorded.isEmpty()) return backfilled
    val merged = LinkedHashMap<Pair<PhaseType, Int>, PhaseAverages>()
    phaseOccurrences(recorded).forEachIndexed { i, key -> merged[key] = recorded[i] }
    phaseOccurrences(backfilled).forEachIndexed { i, key ->
        val existing = merged[key]
        val backfilledHr = backfilled[i].avgHrBpm
        when {
            existing == null -> merged[key] = backfilled[i]
            existing.avgHrBpm == null && backfilledHr != null -> merged[key] = existing.copy(avgHrBpm = backfilledHr)
        }
    }
    return merged.entries.sortedBy { phaseOrder(it.key) }.map { it.value }
}

/** Workout-order key of each phase occurrence: which occurrence of its type this phase is. */
private fun phaseOccurrences(phases: List<PhaseAverages>): List<Pair<PhaseType, Int>> {
    val seen = mutableMapOf<PhaseType, Int>()
    return phases.map { p ->
        val n = (seen[p.phase] ?: 0) + 1
        seen[p.phase] = n
        p.phase to n
    }
}

/**
 * Sort rank of a phase occurrence in workout order: warm-up, then round by
 * round (push before recovery), then cool-down — the order the phases ran in,
 * independent of a phase having been missed by either source.
 */
private fun phaseOrder(key: Pair<PhaseType, Int>): Int = when (key.first) {
    PhaseType.WARMUP -> 0
    PhaseType.FAST -> 2 * key.second - 1
    PhaseType.SLOW -> 2 * key.second
    PhaseType.COOLDOWN -> Int.MAX_VALUE
}

/**
 * Map per-minute HR buckets (offset seconds since workout start → avg bpm)
 * onto the profile's phase plan, returning (push avg, recovery avg). Pure and
 * deterministic — the same plan math the engine uses, so phase boundaries
 * match a real session exactly.
 */
internal fun phaseAveragesFromBuckets(
    buckets: List<Pair<Long, Int>>,
    profile: WorkoutProfile,
    totalSeconds: Int,
): Pair<Int?, Int?> {
    val plan = planFor(profile)
    var pushSum = 0L
    var pushN = 0
    var recoverySum = 0L
    var recoveryN = 0
    buckets.forEach { (offsetSec, bpm) ->
        if (offsetSec >= totalSeconds) return@forEach
        when (phaseAt(offsetSec.toInt(), profile, plan.coreEndSec, plan.finishSec).phase) {
            PhaseType.FAST -> { pushSum += bpm; pushN++ }
            PhaseType.SLOW -> { recoverySum += bpm; recoveryN++ }
            else -> Unit // warm-up/cooldown buckets are not phase averages
        }
    }
    return (if (pushN > 0) (pushSum / pushN).toInt() else null) to
        (if (recoveryN > 0) (recoverySum / recoveryN).toInt() else null)
}