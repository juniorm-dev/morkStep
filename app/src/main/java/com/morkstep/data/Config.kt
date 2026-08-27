package com.morkstep.data

/** Phase of a workout interval. */
enum class PhaseType { WARMUP, FAST, SLOW, COOLDOWN }

/** A single configured interval segment. */
data class IntervalSegment(
    val type: PhaseType = PhaseType.FAST,
    val seconds: Int = 60,
)

/** The user-configurable workout plan. */
data class IntervalConfig(
    /** Phase segments in the order they run, including warmup/cooldown. */
    val segments: List<IntervalSegment> = emptyList(),
    /** Target walking-pace band (km/h). Push intervals aim within this band. */
    val paceCeiling: Double = 6.5,
    val paceFloor: Double = 5.0,
    /** Heart-rate band (bpm) targeted during push intervals. */
    val hrCeiling: Int = 150,
    val hrFloor: Int = 120,
    /** Audio cues enabled on/off. */
    val audioCues: Boolean = true,
) {
    val totalSeconds: Int get() = segments.sumOf { it.seconds }
}

/** Standard IWT default: 3 min warmup, 5x(3 min fast, 3 min slow), 2 min cooldown. */
fun defaultConfig(): IntervalConfig = IntervalConfig(
    segments = buildList {
        add(IntervalSegment(PhaseType.WARMUP, 180))
        repeat(5) {
            add(IntervalSegment(PhaseType.FAST, 180))
            add(IntervalSegment(PhaseType.SLOW, 180))
        }
        add(IntervalSegment(PhaseType.COOLDOWN, 120))
    },
)