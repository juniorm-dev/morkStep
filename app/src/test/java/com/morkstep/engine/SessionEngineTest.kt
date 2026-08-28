package com.morkstep.engine

import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClock(private var now: Long) : SessionClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) { now += ms }
}

class FakeSensors(initPace: Float?, initHr: Int?) : PaceSource, HeartRateSource {
    private val _pace = MutableStateFlow(initPace)
    override val pace: kotlinx.coroutines.flow.StateFlow<Float?> = _pace
    private val _hr = MutableStateFlow(initHr)
    override val hr: kotlinx.coroutines.flow.StateFlow<Int?> = _hr
}

class RecordingCue : CueSink {
    val spoken = mutableListOf<String>()
    var beeps = 0
    override fun beep() { beeps++ }
    override fun speak(text: String) { spoken.add(text) }
}

class SessionEngineTest {

    private val roundsProfile = WorkoutProfile(
        id = 1,
        name = "Rounds",
        lengthMode = WorkoutLength.ROUNDS,
        rounds = 2,
        warmupSec = 60,
        fastSec = 60,
        slowSec = 60,
        cooldownSec = 60,
        paceCeilingMph = 4.5,
        paceFloorMph = 3.2,
        hrCeiling = 150,
        hrFloor = 120,
    )

    private fun engineWith(profile: WorkoutProfile, clock: FakeClock, cue: RecordingCue, pace: Float? = 4.0f, hr: Int? = 130) =
        SessionEngine(profile, FakeSensors(pace, hr), FakeSensors(pace, hr), cue, clock)

    // ---- pure helpers ----

    @Test
    fun planFor_rounds() {
        val p = planFor(roundsProfile)
        assertEquals(60L + 2 * 120, p.coreEndSec)
        assertEquals(60L + 2 * 120 + 60, p.finishSec)
    }

    @Test
    fun planFor_time() {
        val p = WorkoutProfile(id = 2, name = "T", lengthMode = WorkoutLength.TIME, timeMinutes = 2, cooldownSec = 60)
        val plan = planFor(p)
        assertEquals(120L, plan.finishSec)
        assertEquals(60L, plan.coreEndSec)
    }

    @Test
    fun phaseAt_mapsBasicCycle() {
        assertEquals(PhaseType.WARMUP, phaseAt(0, roundsProfile, 300, 360).phase)
        assertEquals(PhaseType.FAST, phaseAt(60, roundsProfile, 300, 360).phase)
        assertEquals(PhaseType.SLOW, phaseAt(120, roundsProfile, 300, 360).phase)
        assertEquals(PhaseType.FAST, phaseAt(180, roundsProfile, 300, 360).phase)
        assertEquals(PhaseType.SLOW, phaseAt(240, roundsProfile, 300, 360).phase)
        assertEquals(PhaseType.COOLDOWN, phaseAt(300, roundsProfile, 300, 360).phase)
    }

    @Test
    fun phaseAt_secondsInPhaseAndOrdinal() {
        val fast = phaseAt(61, roundsProfile, 300, 360)
        assertEquals(PhaseType.FAST, fast.phase)
        assertEquals(1, fast.secondsInPhase)
        assertEquals(1, fast.phaseOrdinal)
        val slow = phaseAt(125, roundsProfile, 300, 360)
        assertEquals(PhaseType.SLOW, slow.phase)
        assertEquals(5, slow.secondsInPhase)
        assertEquals(2, slow.phaseOrdinal)
    }

    @Test
    fun completedFastIn_isPlanRelative() {
        val p = roundsProfile
        assertEquals(0, completedFastIn(60, p))
        assertEquals(0, completedFastIn(119, p))
        assertEquals(1, completedFastIn(120, p))
        assertEquals(1, completedFastIn(180, p))
        assertEquals(2, completedFastIn(240, p))
    }

    @Test
    fun progressAt_roundsAndTime() {
        assertEquals(0.0f, progressAt(0, roundsProfile, 300, 360, 0.0)!!, 0.01f)
        assertEquals(0.5f, progressAt(180, roundsProfile, 300, 360, 0.0)!!, 0.01f)
        assertEquals(1.0f, progressAt(360, roundsProfile, 300, 360, 0.0)!!, 0.01f)
        val t = WorkoutProfile(id = 3, name = "T2", lengthMode = WorkoutLength.TIME, timeMinutes = 1, cooldownSec = 0)
        assertEquals(0.5f, progressAt(30, t, 60, 60, 0.0)!!, 0.01f)
    }

    @Test
    fun progressAt_distanceAndAdhoc() {
        val d = WorkoutProfile(id = 4, name = "D", lengthMode = WorkoutLength.DISTANCE, distanceMiles = 1.0)
        assertEquals(0.25f, progressAt(0, d, Long.MAX_VALUE, Long.MAX_VALUE, 0.25)!!, 0.01f)
        val a = WorkoutProfile(id = 5, name = "A", lengthMode = WorkoutLength.ADHOC)
        assertNull(progressAt(999, a, Long.MAX_VALUE, Long.MAX_VALUE, 1.0))
    }

    // ---- engine behavior ----

    @Test
    fun tick_rounds_profile_finishesAndCountsFast() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run()
        assertEquals(PhaseType.WARMUP, eng.snapshot.phase)

        clock.advance(61_000)
        eng.tick()
        assertEquals(PhaseType.FAST, eng.snapshot.phase)

        clock.advance(121_000 - 61_000)
        eng.tick()
        assertEquals(PhaseType.SLOW, eng.snapshot.phase)
        assertEquals(1, eng.snapshot.fastSegmentsDone)

        clock.advance(364_000 - 121_000)
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertEquals(2, eng.snapshot.fastSegmentsDone)
        assertEquals(PhaseType.COOLDOWN, eng.snapshot.phase)
        assertEquals(1.0f, eng.snapshot.progress!!, 0.01f)
    }

    @Test
    fun tick_time_profile_finishesAtTarget() {
        val timeProfile = WorkoutProfile(
            id = 6, name = "T3", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 10, fastSec = 20, slowSec = 20, cooldownSec = 10,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(timeProfile, clock, cue)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertEquals(60, eng.snapshot.totalSeconds)
    }

    @Test
    fun tick_adhoc_runsUntilEndNow() {
        val adhoc = WorkoutProfile(id = 7, name = "A", lengthMode = WorkoutLength.ADHOC, fastSec = 60, slowSec = 60, adhocCueEveryNPush = 2)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(adhoc, clock, cue)
        eng.run()
        clock.advance(10_000)
        eng.tick()
        assertFalse(eng.snapshot.finished)
        assertNull(eng.snapshot.progress)
        // 180s warm-up + 2 full fast/slow pairs (2*120s) = past push round 3,
        // so every-2nd-push cue fires at push 2.
        clock.advance(430_000)
        eng.tick()
        assertEquals(2, eng.snapshot.fastSegmentsDone)
        assertTrue(cue.spoken.any { it.contains("Push round 2") })
        eng.endNow()
        assertTrue(eng.snapshot.finished)
        assertFalse(eng.snapshot.running)
    }

    @Test
    fun quarterCues_fireAtEachQuarter() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run()
        clock.advance(90_000)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("quarter") })
        clock.advance(90_000)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Halfway") })
        clock.advance(90_000)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Three quarters") })
    }

    @Test
    fun slowPaceBelowFloor_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, pace = 2.8f, hr = 128)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun highHr_countsOverCeilingAndCues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, pace = 4.0f, hr = 165)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        assertTrue(eng.snapshot.overCeilingSec >= 1)
        assertTrue(cue.spoken.any { it.contains("high") })
    }

    @Test
    fun distance_accumulatesFromPace() {
        val timeProfile = WorkoutProfile(
            id = 8, name = "T4", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 0, fastSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(timeProfile, clock, cue, pace = 4.0f, hr = 120)
        eng.run()
        clock.advance(10_001)
        eng.tick()
        val miles = eng.snapshot.distanceMiles
        assertTrue(miles > 0.010 && miles < 0.013)
    }
}