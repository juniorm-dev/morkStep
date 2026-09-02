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
 *  - per-minute buckets mapped back onto the profile's phase plan, for the
 *    push/recovery averages just like the real-time engine would have recorded.
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
)

/**
 * Read the workout window's HR from Health Connect, or null when Health
 * Connect is unavailable, HR read permission was not granted, or no HR
 * records exist in the window. Never throws: callers treat null as "skip".
 */
suspend fun healthConnectHrForWorkout(
    context: Context,
    entity: WorkoutEntity,
    profile: WorkoutProfile,
): HealthConnectHr? {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
    if (ContextCompat.checkSelfPermission(context, "android.permission.health.READ_HEART_RATE") !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    val start = Instant.ofEpochMilli(entity.startTime)
    val end = Instant.ofEpochMilli(entity.endTime)
    if (end <= start) return null

    val client = HealthConnectClient.getOrCreate(context)
    val aggregate = client.aggregate(
        AggregateRequest(
            metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_MIN),
            timeRangeFilter = TimeRangeFilter.between(start, end),
        )
    )
    val avgOverall = aggregate[HeartRateRecord.BPM_AVG]?.toInt()
    val minHr = aggregate[HeartRateRecord.BPM_MIN]?.toInt()
    val maxHr = aggregate[HeartRateRecord.BPM_MAX]?.toInt()

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
    }.getOrDefault(emptyList())
    val phaseBuckets = buckets.mapNotNull { b ->
        val bucketStart = b.startTime ?: return@mapNotNull null
        val offsetSec = Duration.between(start, bucketStart).seconds
        val avg = b.result[HeartRateRecord.BPM_AVG]?.toInt() ?: return@mapNotNull null
        offsetSec to avg
    }
    val (avgPush, avgRecovery) = phaseAveragesFromBuckets(phaseBuckets, profile, entity.durationSec)

    if (avgOverall == null && minHr == null && maxHr == null && avgPush == null && avgRecovery == null) {
        return null
    }
    return HealthConnectHr(avgOverall, avgPush, avgRecovery, minHr, maxHr)
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