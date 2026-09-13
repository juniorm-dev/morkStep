package com.morkstep.sensing

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
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
 *
 * [profile] may be null: a row whose profile was renamed or deleted after the
 * session still gets the profile-independent average/min/max read, because the
 * phase plan (not the aggregate) is what needs the profile.
 */
suspend fun healthConnectHrForWorkout(
    context: Context,
    entity: WorkoutEntity,
    profile: WorkoutProfile?,
    log: DebugLog? = null,
): HealthConnectHr? {
    val status = HealthConnectClient.getSdkStatus(context)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
        log?.log("[hc] Health Connect ${sdkStatusName(status)} — read skipped")
        return null
    }
    if (!readPermissionGranted(context)) {
        log?.log("[hc] read permission not granted (Settings → Grant Health Connect access)")
        return null
    }
    val start = Instant.ofEpochMilli(entity.startTime)
    val end = Instant.ofEpochMilli(entity.endTime)
    if (end <= start) {
        log?.log("[hc] empty window (${entity.endTime - entity.startTime} ms)")
        return null
    }

    val client = HealthConnectClient.getOrCreate(context)
    log?.log(
        "[hc] #${entity.id} reading ${sdkStatusName(status)} · window ${entity.durationSec}s · " +
            if (profile == null) "no profile (overall HR only)" else "profile \"${profile.name}\""
    )
    probeHealthConnectRecords(client, entity, start, end, log)
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

    // Per-minute buckets → phase averages, skipped when the profile is gone: the
    // phase plan is what attributes a bucket to a phase, so a row without one
    // keeps the profile-independent average/min/max read above rather than losing
    // the whole backfill. Otherwise the read is best-effort: a failed bucket read
    // (or a metric unsupported per bucket) skips phases.
    val buckets = if (profile == null) {
        log?.log("[hc] phase buckets skipped: profile is gone")
        emptyList()
    } else runCatching {
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
    val (avgPush, avgRecovery) = profile?.let {
        phaseAveragesFromBuckets(phaseBuckets, it, entity.durationSec)
    } ?: (null to null)
    val phaseAverages = profile?.let {
        phaseAveragesPerOccurrence(phaseBuckets, it, entity.durationSec)
    }.orEmpty()

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
/**
 * Connection probe for the `[hc]` trace: read the newest HR record inside the
 * workout window (one record, no statistics). It tells apart the two cases an
 * empty aggregate cannot — the provider not answering at all, and the provider
 * answering with no heart rate inside the window (nothing has written it) — and
 * reports where in the window the record it does hold starts. Skipped without a
 * log, since nothing would read the answer.
 */
private suspend fun probeHealthConnectRecords(
    client: HealthConnectClient,
    entity: WorkoutEntity,
    start: Instant,
    end: Instant,
    log: DebugLog?,
) {
    if (log == null) return
    val probe = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = false,
                pageSize = 1,
            )
        )
    }
    probe.onSuccess { response ->
        val record = response.records.firstOrNull()
        if (record == null) {
            log.log("[hc] provider answered · no HR record in the ${entity.durationSec}s window")
        } else {
            val offsetSec = Duration.between(start, record.startTime).seconds
            log.log("[hc] provider answered · HR record from +${offsetSec}s (${record.samples.size} samples)")
        }
    }.onFailure { e ->
        log.log("[hc] provider read failed (${e::class.simpleName}: ${e.message})")
    }
}

/**
 * One-line Health Connect state for the debug trace and the exported log header:
 * availability plus the heart-rate read permission — whether the app can talk to
 * Health Connect at all, apart from whether it holds data.
 */
fun healthConnectStatusNote(context: Context): String {
    val permission = if (readPermissionGranted(context)) "granted" else "not granted"
    return "${sdkStatusName(HealthConnectClient.getSdkStatus(context))} · read permission $permission"
}

/** Whether the app holds the Health Connect heart-rate read permission. */
private fun readPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, "android.permission.health.READ_HEART_RATE") ==
        PackageManager.PERMISSION_GRANTED

/** Human-readable [HealthConnectClient.getSdkStatus] code for the `[hc]` trace. */
private fun sdkStatusName(status: Int): String = when (status) {
    HealthConnectClient.SDK_AVAILABLE -> "available"
    HealthConnectClient.SDK_UNAVAILABLE -> "unavailable (no provider)"
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "unavailable (provider update required)"
    else -> "unknown status $status"
}

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
 * Fold a Health Connect read into a finished workout: every field the session
 * left null takes the backfill's value, and a real-time reading is never
 * overwritten — the min/max pair, which only the backfill records, follows the
 * same fill-only rule so a later read without it cannot clear an earlier one.
 * Pure, so the finish-line read and every later attempt (retry chain, History
 * sweep, opening a History card) share exactly one merge rule.
 */
internal fun mergeBackfilledHr(entity: WorkoutEntity, hc: HealthConnectHr): WorkoutEntity = entity.copy(
    avgOverallHr = entity.avgOverallHr ?: hc.avgOverall,
    avgPushHr = entity.avgPushHr ?: hc.avgPush,
    avgRecoveryHr = entity.avgRecoveryHr ?: hc.avgRecovery,
    minHr = entity.minHr ?: hc.minHr,
    maxHr = entity.maxHr ?: hc.maxHr,
    phaseAverages = mergeBackfilledPhaseAverages(entity.phaseAverages, hc.phaseAverages),
)

/**
 * Whether Health Connect still has HR to fill in [w]: one of the summary fields
 * a read can set is still missing. Drives the on-demand read when a History card
 * is opened — including a session that recorded HR live but holds no min/max
 * pair, which only the backfill records.
 */
internal fun hrBackfillGap(w: WorkoutEntity): Boolean =
    w.avgOverallHr == null || w.avgPushHr == null || w.avgRecoveryHr == null ||
        w.minHr == null || w.maxHr == null

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