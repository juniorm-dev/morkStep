package com.morkstep.engine

import com.morkstep.Constants
import com.morkstep.data.AudioMode
import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import com.morkstep.sensing.SpeedSource
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

class FakeSensors(initSpeed: Float?, initHr: Int?, initPace: Int? = null) : SpeedSource, HeartRateSource, PaceSource {
    private val _speed = MutableStateFlow(initSpeed)
    override val speed: kotlinx.coroutines.flow.StateFlow<Float?> = _speed
    private val _hr = MutableStateFlow(initHr)
    override val hr: kotlinx.coroutines.flow.StateFlow<Int?> = _hr
    private val _pace = MutableStateFlow(initPace)
    override val pace: kotlinx.coroutines.flow.StateFlow<Int?> = _pace
    fun setSpeed(p: Float?) { _speed.value = p }
    fun setHr(h: Int?) { _hr.value = h }
    fun setPace(c: Int?) { _pace.value = c }
}

class RecordingCue : CueSink {
    val spoken = mutableListOf<String>()
    var beeps = 0
    val vibrations = mutableListOf<CueVibration>()
    override fun beep() { beeps++ }
    override fun speak(text: String) { spoken.add(text) }
    override fun vibrate(kind: CueVibration) { vibrations.add(kind) }
}

class SessionEngineTest {

    private val roundsProfile = WorkoutProfile(
        id = 1,
        name = "Rounds",
        lengthMode = WorkoutLength.ROUNDS,
        rounds = 2,
        warmupSec = 60,
        pushSec = 60,
        slowSec = 60,
        cooldownSec = 60,
        recoverySpeedCapMph = 4.5,
        pushSpeedFloorMph = 3.2,
        recoveryPaceCapSpm = 110,
        pushPaceFloorSpm = 100,
        hrPushMin = 150,
        hrRecoveryMax = 120,
    )

    private fun engineWith(profile: WorkoutProfile, clock: FakeClock, cue: RecordingCue, speed: Float? = 4.0f, hr: Int? = 130, pace: Int? = null) =
        SessionEngine(profile, FakeSensors(speed, hr, pace), FakeSensors(speed, hr, pace), FakeSensors(speed, hr, pace), cue, clock)

    /**
     * Cross a phase transition's warning-settle window and tick once, so the
     * warning cue under test is no longer muted. The default profile levels out
     * transitions ([WorkoutProfile.resetPhaseAverages] = true), which holds
     * warning cues back for [Constants.PHASE_TRANSITION_SETTLE_MS] after the
     * entry tick — the previous phase's reading (and a first-sample pace spike)
     * is not a valid basis for a warning about the new phase.
     */
    private fun tickPastSettle(eng: SessionEngine, clock: FakeClock) {
        clock.advance(Constants.PHASE_TRANSITION_SETTLE_MS)
        eng.tick()
    }

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
    fun completedPushIn_isPlanRelative() {
        val p = roundsProfile
        assertEquals(0, completedPushIn(60, p))
        assertEquals(0, completedPushIn(119, p))
        assertEquals(1, completedPushIn(120, p))
        assertEquals(1, completedPushIn(180, p))
        assertEquals(2, completedPushIn(240, p))
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
        assertEquals(1, eng.snapshot.pushSegmentsDone)

        clock.advance(364_000 - 121_000)
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertEquals(2, eng.snapshot.pushSegmentsDone)
        assertEquals(PhaseType.COOLDOWN, eng.snapshot.phase)
        assertEquals(1.0f, eng.snapshot.progress!!, 0.01f)
    }

    @Test
    fun tick_time_profile_finishesAtTarget() {
        val timeProfile = WorkoutProfile(
            id = 6, name = "T3", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 10, pushSec = 20, slowSec = 20, cooldownSec = 10,
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
        val adhoc = WorkoutProfile(id = 7, name = "A", lengthMode = WorkoutLength.ADHOC, pushSec = 60, slowSec = 60, adhocCueEveryNPush = 2)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(adhoc, clock, cue)
        eng.run()
        clock.advance(10_000)
        eng.tick()
        assertFalse(eng.snapshot.finished)
        assertNull(eng.snapshot.progress)
        // 180s warm-up + 2 full fast/slow pairs (2*120s) = past push round 3,
        // so every-2nd-push cue fires at push 2 — but this is a phase-change
        // tick, so the ADHOC cue follows on the next tick.
        clock.advance(430_000) // t=440 → FAST entry
        eng.tick() // phase change announced; ADHOC cue sits until the next tick
        clock.advance(1_000) // t=441
        eng.tick()
        assertEquals(2, eng.snapshot.pushSegmentsDone)
        assertTrue(cue.spoken.any { it.contains("Push round 2") })
        eng.endNow()
        assertTrue(eng.snapshot.finished)
        assertFalse(eng.snapshot.running)
    }

    @Test
    fun roundsMode_quarterCuesByPushCount() {
        val p = WorkoutProfile(
            id = 20, name = "QR", lengthMode = WorkoutLength.ROUNDS, rounds = 4,
            warmupSec = 60, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run()
        clock.advance(121_000) // t=121: fast 1 complete → 25% of 4 rounds, on a phase-change tick
        eng.tick() // phase change announced; quarter sits until the next tick
        clock.advance(1_000) // t=122
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("One quarter") })
        clock.advance(118_000) // t=240: fast 2 complete → 50%
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Halfway") })
        clock.advance(120_000) // t=360: fast 3 complete → 75%
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Three quarters") })
    }

    @Test
    fun timeMode_quarterCuesByTime() {
        val p = WorkoutProfile(
            id = 21, name = "QT", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 0, pushSec = 30, slowSec = 30, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run()
        clock.advance(16_000) // t=16/60 s ≈ 27% → FAST entry
        eng.tick() // phase change announced; quarter sits until the next tick
        clock.advance(1_000) // t=17
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("One quarter") })
        clock.advance(13_000) // t=30/60 s = 50% → SLOW entry
        eng.tick() // phase change announced; "Halfway" sits until the next tick
        clock.advance(1_000) // t=31
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Halfway") })
    }

    @Test
    fun distanceMode_quarterCuesByMiles() {
        val p = WorkoutProfile(
            id = 22, name = "QD", lengthMode = WorkoutLength.DISTANCE, distanceMiles = 1.0,
            warmupSec = 0, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue, speed = 4.0f, hr = 120)
        eng.run()
        clock.advance(226_000) // 4.0 mph × 225s = 0.25 mi → SLOW entry tick
        eng.tick() // phase change announced; quarter sits until the next tick
        clock.advance(1_000) // t=227
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("One quarter") })
    }

    @Test
    fun fastSpeedBelowCeiling_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.8f, hr = 128)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun fastHighHr_countsOverCeilingNoCue() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 5.0f, hr = 165) // at/over push min: no push cue
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // over-push-min counts on ticks after phase entry
        eng.tick()
        assertTrue(eng.snapshot.overPushMinSec >= 1)
        assertFalse(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun fastHrBelowPushMin_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun slowHrAboveRecoveryMax_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 165) // > hrRecoveryMax 120
        eng.run()
        clock.advance(121_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun slowSpeedAboveFloor_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 5.0f, hr = 128) // > speedFloor 3.2
        eng.run()
        clock.advance(121_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun warningThreshold_controlsRepeatCadence() {
        val p = roundsProfile.copy(warningThresholdSec = 5)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue, speed = 2.8f, hr = 128) // below speed ceiling
        eng.run()
        clock.advance(60_001) // t=60 → FAST entry
        eng.tick()
        clock.advance(1_000) // t=61: first warning cue → suppressed
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        tickPastSettle(eng, clock) // first warning cue fires
        assertEquals(1, cue.spoken.count { it.contains("Speed up") })
        clock.advance(3_000) // 3 s < 5 s threshold → no repeat
        eng.tick()
        assertEquals(1, cue.spoken.count { it.contains("Speed up") })
        clock.advance(3_000) // 6 s ≥ 5 s threshold → repeat
        eng.tick()
        assertEquals(2, cue.spoken.count { it.contains("Speed up") })
    }

    @Test
    fun distance_accumulatesFromSpeed() {
        val timeProfile = WorkoutProfile(
            id = 8, name = "T4", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 0, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(timeProfile, clock, cue, speed = 4.0f, hr = 120)
        eng.run()
        clock.advance(10_001)
        eng.tick()
        val miles = eng.snapshot.distanceMiles
        assertTrue(miles > 0.010 && miles < 0.013)
    }

    @Test
    fun phaseAverages_speedHrAndPacePerBucket() {
        val p = roundsProfile // warmup 60, fast 60, slow 60
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val sensors = FakeSensors(3.0f, 100, 95)
        val eng = SessionEngine(p, sensors, sensors, sensors, cue, clock)
        eng.run() // t=0 warmup sample: 3.0 / 100 / 95
        sensors.setSpeed(3.0f); sensors.setHr(100); sensors.setPace(95)
        clock.advance(10_000); eng.tick() // warmup sample 3.0 / 100 / 95
        sensors.setSpeed(4.0f); sensors.setHr(140); sensors.setPace(120)
        clock.advance(60_000); eng.tick() // t=70 → FAST, sample 4.0 / 140 / 120
        sensors.setSpeed(2.0f); sensors.setHr(110); sensors.setPace(90)
        clock.advance(60_000); eng.tick() // t=130 → SLOW, sample 2.0 / 110 / 90

        // Each phase entry resets its bucket to just inside the target band
        // (push min + 1 / recovery max - 1), then the entry sample blends in.
        // push: seed 4.2 + sample 4.0 = 8.2 / 2; speed floor 3.2.
        assertEquals(4.1f, eng.snapshot.avgPushSpeedMph!!, 0.01f)
        // recovery: seed 3.5 + sample 2.0 = 5.5 / 2; speed cap 4.5.
        assertEquals(2.75f, eng.snapshot.avgRecoverySpeedMph!!, 0.01f)
        assertEquals(3.0f, eng.snapshot.avgOverallSpeedMph!!, 0.01f) // (3+3+4+2)/4
        // push HR: seed 151 + 140 = 291 / 2 → 145; recovery: 119 + 110 = 229 / 2 → 114.
        assertEquals(145, eng.snapshot.avgPushHr!!)
        assertEquals(114, eng.snapshot.avgRecoveryHr!!)
        assertEquals(112, eng.snapshot.avgOverallHr!!) // (100+100+140+110)/4 = 112.5 → 112
        // push pace: seed 101 + 120 = 221 / 2 → 110; recovery: 109 + 90 = 199 / 2 → 99.
        assertEquals(110, eng.snapshot.avgPushPace!!)
        assertEquals(99, eng.snapshot.avgRecoveryPace!!)
        assertEquals(100, eng.snapshot.avgOverallPace!!) // (95+95+120+90)/4 = 100
    }

    @Test
    fun phaseAverages_notSeededWhenToggleOff() {
        // resetPhaseAverages = false keeps the pure sample average: no seed is
        // injected on phase entry, so a single-push-sample phase reports exactly
        // that sample rather than blending in the push-min/+1 seed.
        val p = roundsProfile.copy(resetPhaseAverages = false) // warmup 60, fast 60
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val sensors = FakeSensors(3.0f, 100, 95)
        val eng = SessionEngine(p, sensors, sensors, sensors, cue, clock)
        eng.run() // t=0 warmup sample
        sensors.setSpeed(4.0f); sensors.setHr(140); sensors.setPace(120)
        clock.advance(70_000); eng.tick() // t=70 → FAST, sample 4.0 / 140 / 120

        assertEquals(4.0f, eng.snapshot.avgPushSpeedMph!!, 0.01f)
        assertEquals(140, eng.snapshot.avgPushHr!!)
        assertEquals(120, eng.snapshot.avgPushPace!!)
    }

    @Test
    fun phaseOccurrences_recordTheirOwnAverages() {
        // Every phase occurrence — warm-up, both pushes, both recoveries,
        // cool-down — is recorded in workout order with its own averaged samples.
        // A tick on a phase boundary counts for the phase being entered, exactly
        // as the engine buckets its pooled averages.
        val clock = FakeClock(1_000)
        val sensors = FakeSensors(3.0f, 120, 95)
        val eng = SessionEngine(roundsProfile, sensors, sensors, sensors, RecordingCue(), clock)
        eng.run() // t=0 → warm-up sample 3.0 / 120 / 95
        clock.advance(60_000)
        sensors.setSpeed(4.0f); sensors.setHr(150); sensors.setPace(110)
        eng.tick() // t=60 → FAST; warm-up closes on its single sample
        clock.advance(30_000)
        sensors.setSpeed(4.2f); sensors.setHr(152)
        eng.tick() // t=90 → second push sample
        clock.advance(30_000)
        sensors.setSpeed(2.0f); sensors.setHr(110); sensors.setPace(90)
        eng.tick() // t=120 → SLOW; push closes: 4.1 / 151 / 110
        clock.advance(60_000)
        sensors.setSpeed(4.5f); sensors.setHr(160); sensors.setPace(115)
        eng.tick() // t=180 → FAST; recovery closes: 2.0 / 110 / 90
        clock.advance(60_000)
        sensors.setSpeed(2.5f); sensors.setHr(115); sensors.setPace(92)
        eng.tick() // t=240 → SLOW; push closes: 4.5 / 160 / 115
        clock.advance(60_000)
        sensors.setSpeed(2.0f); sensors.setHr(105); sensors.setPace(88)
        eng.tick() // t=300 → COOLDOWN; recovery closes: 2.5 / 115 / 92
        clock.advance(60_000)
        eng.tick() // t=360 → finished; cooldown closes: 2.0 / 105 / 88

        assertTrue(eng.snapshot.finished)
        val phases = eng.snapshot.phaseAverages
        assertEquals(
            listOf(
                PhaseType.WARMUP, PhaseType.FAST, PhaseType.SLOW,
                PhaseType.FAST, PhaseType.SLOW, PhaseType.COOLDOWN,
            ),
            phases.map { it.phase },
        )
        assertEquals(3.0f, phases[0].avgSpeedMph!!, 0.01f)
        assertEquals(120, phases[0].avgHrBpm!!)
        assertEquals(95, phases[0].avgPaceSpm!!)
        assertEquals(4.1f, phases[1].avgSpeedMph!!, 0.01f)
        assertEquals(151, phases[1].avgHrBpm!!)
        assertEquals(110, phases[1].avgPaceSpm!!)
        assertEquals(2.0f, phases[2].avgSpeedMph!!, 0.01f)
        assertEquals(110, phases[2].avgHrBpm!!)
        assertEquals(90, phases[2].avgPaceSpm!!)
        assertEquals(4.5f, phases[3].avgSpeedMph!!, 0.01f)
        assertEquals(160, phases[3].avgHrBpm!!)
        assertEquals(2.5f, phases[4].avgSpeedMph!!, 0.01f)
        assertEquals(115, phases[4].avgHrBpm!!)
        assertEquals(2.0f, phases[5].avgSpeedMph!!, 0.01f)
        assertEquals(105, phases[5].avgHrBpm!!)
        assertEquals(88, phases[5].avgPaceSpm!!)
    }

    @Test
    fun phaseOccurrences_dropMissingMetricsAndFlushOnEarlyEnd() {
        // With no HR and no pace signal at all, a phase records those metrics as
        // null rather than as zeros, and stopping early still closes the phase
        // that was in progress.
        val clock = FakeClock(1_000)
        val sensors = FakeSensors(3.5f, null, null)
        val eng = SessionEngine(roundsProfile, sensors, sensors, sensors, RecordingCue(), clock)
        eng.run() // t=0 → warm-up sample 3.5 / – / –
        clock.advance(20_000)
        eng.tick()
        eng.endNow()

        val phases = eng.snapshot.phaseAverages
        assertEquals(1, phases.size)
        assertEquals(PhaseType.WARMUP, phases[0].phase)
        assertEquals(3.5f, phases[0].avgSpeedMph!!, 0.01f)
        assertNull(phases[0].avgHrBpm)
        assertNull(phases[0].avgPaceSpm)
    }

    // ---- pause / resume ----

    @Test
    fun pause_freezesElapsedDistanceAndCues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run() // t=0 → WARMUP
        clock.advance(31_000)
        eng.tick() // t=31, still WARMUP
        assertEquals(31, eng.snapshot.totalSeconds)
        val distBefore = eng.snapshot.distanceMiles
        val spokenBefore = cue.spoken.size
        val beepsBefore = cue.beeps

        eng.pause()
        assertTrue(eng.snapshot.paused)
        clock.advance(200_000)
        eng.tick()
        eng.tick()
        // Frozen: no time, no distance, no cues while paused.
        assertEquals(31, eng.snapshot.totalSeconds)
        assertEquals(distBefore, eng.snapshot.distanceMiles, 0.0)
        assertEquals(spokenBefore, cue.spoken.size)
        assertEquals(beepsBefore, cue.beeps)

        eng.resume()
        assertFalse(eng.snapshot.paused)
        clock.advance(1_000)
        eng.tick() // t=32 (paused 200 s excluded)
        assertEquals(32, eng.snapshot.totalSeconds)
    }

    @Test
    fun pause_blocksPhaseEntryCuesUntilResume() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run()
        clock.advance(30_000)
        eng.tick()
        eng.pause()
        val spokenBefore = cue.spoken.size
        clock.advance(31_000) // would cross into FAST at t=60 while paused
        eng.tick()
        assertEquals(PhaseType.WARMUP, eng.snapshot.phase)
        assertEquals(spokenBefore, cue.spoken.size)
        eng.resume()
        clock.advance(30_000) // t=60 → FAST entry fires after resume
        eng.tick()
        assertEquals(PhaseType.FAST, eng.snapshot.phase)
        assertTrue(cue.spoken.any { it.contains("Push phase") })
    }

    @Test
    fun pause_resume_multipleCyclesAndFinishWhilePaused() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run()
        clock.advance(10_000)
        eng.tick() // t=10
        eng.pause()
        clock.advance(50_000)
        eng.resume()
        clock.advance(20_000)
        eng.tick() // t=30, the 50 s pause is excluded
        assertEquals(30, eng.snapshot.totalSeconds)
        eng.pause()
        clock.advance(5_000)
        eng.tick()
        assertEquals(30, eng.snapshot.totalSeconds)
        // Finishing while paused is allowed and clears the paused flag.
        eng.endNow()
        assertTrue(eng.snapshot.finished)
        assertFalse(eng.snapshot.running)
        assertFalse(eng.snapshot.paused)
    }

    // ---- push-start announcement ----

    @Test
    fun pushStart_announcementIncludesPushNumber() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run()
        clock.advance(60_001) // t=60 → first FAST
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Push phase 1") })
        clock.advance(60_000) // t=120 → SLOW so the next FAST entry is observed
        eng.tick()
        clock.advance(60_000) // t=180 → second FAST
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Push phase 2") })
    }

    @Test
    fun pushStart_takesPrecedenceOverWarningCuesOnEntryTick() {
        // HR below the push min (150): the warning must NOT clobber the push
        // announcement on the same entry tick.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, hr = 100)
        eng.run()
        clock.advance(60_001) // t=60 → first FAST
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Push phase 1") })
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        // Warnings still fire on subsequent ticks, once the new phase's
        // readings have settled.
        clock.advance(1_000) // t=61: first warning cue suppressed
        eng.tick()
        tickPastSettle(eng, clock) // warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun quarterCue_defersBehindPhaseChangeOnEntryTick() {
        // A quarter threshold crossed on the same tick as a phase change must
        // not clobber the transition: the phase-change cue plays alone first,
        // and the quarter follows on the next tick.
        val p = WorkoutProfile(
            id = 30, name = "QPD", lengthMode = WorkoutLength.ROUNDS, rounds = 4,
            warmupSec = 60, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run() // t=0 → WARMUP
        clock.advance(121_000) // t=121 → SLOW entry, fast 1 complete (25%)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Recovery walking") }) // phase-change cue
        assertFalse(cue.spoken.any { it.contains("One quarter") }) // quarter deferred
        clock.advance(1_000) // t=122
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("One quarter") }) // quarter now fires
    }

    @Test
    fun adhocCue_defersBehindPhaseChangeOnEntryTick() {
        // The ADHOC every-Nth-push cue falls on a phase-change tick and must
        // wait until the following tick, exactly like the quarter cues.
        val p = WorkoutProfile(
            id = 31, name = "ADHOCP", lengthMode = WorkoutLength.ADHOC,
            pushSec = 60, slowSec = 60, adhocCueEveryNPush = 2, warmupSec = 180,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run() // t=0 → WARMUP
        clock.advance(440_000) // t=440 → FAST entry, push round 2 complete
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Push phase") }) // phase-change cue
        assertFalse(cue.spoken.any { it.contains("Push round 2") }) // ADHOC deferred
        clock.advance(1_000) // t=441
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Push round 2") }) // ADHOC cue now fires
    }

    @Test
    fun phaseTransition_mutesWarningsUntilTheNewPhaseReadingsSettle() {
        // FAST with HR below the push min (150). "Level out phase transitions"
        // (on by default) holds warnings back past the entry tick: one tick after
        // the transition the reading is still the previous phase's, so nothing
        // may cue until the settle window has elapsed.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, hr = 100)
        eng.run()
        clock.advance(60_001) // t=60 → entry into FAST (only the announcement)
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        clock.advance(1_000) // t=61 → first tick after entry, muted
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        clock.advance(Constants.PHASE_TRANSITION_SETTLE_MS - 2_000) // still inside the window
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        tickPastSettle(eng, clock) // settles → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
        // Same for recovery: entering SLOW mutes warnings the same way.
        val clock2 = FakeClock(1_000)
        val cue2 = RecordingCue()
        val eng2 = engineWith(roundsProfile, clock2, cue2, speed = 2.0f, hr = 165) // > hrRecoveryMax 120
        eng2.run()
        clock2.advance(121_000) // t=121 → entry into SLOW
        eng2.tick()
        assertFalse(cue2.spoken.any { it.contains("Slow down") })
        clock2.advance(1_000) // t=122 → first tick after entry, muted
        eng2.tick()
        assertFalse(cue2.spoken.any { it.contains("Slow down") })
        tickPastSettle(eng2, clock2) // settles → the warning fires
        assertTrue(cue2.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun phaseTransitionWarnings_toggleOffKeepsOneTickSuppression() {
        // resetPhaseAverages = false ("Level out phase transitions" off) keeps
        // the pre-settle contract: only the very first tick after a transition
        // is skipped, so the warning lands two ticks in.
        val p = roundsProfile.copy(resetPhaseAverages = false)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(60_001) // t=60 → FAST entry
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        clock.advance(1_000) // t=61 → first tick after entry, suppressed
        eng.tick()
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        clock.advance(1_000) // t=62 → warning fires
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }
    // ---- haptic vibration cues ----

    @Test
    fun phaseChange_vibratesTransition() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue)
        eng.run() // t=0 → WARMUP
        clock.advance(60_001) // t=60 → FAST entry
        eng.tick()
        assertTrue(cue.vibrations.contains(CueVibration.TRANSITION))
    }

    @Test
    fun finish_vibratesTransition() {
        val p = WorkoutProfile(
            id = 9, name = "T1", lengthMode = WorkoutLength.TIME, timeMinutes = 1,
            warmupSec = 0, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run()
        clock.advance(60_001) // TIME finishes exactly at the target
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertTrue(cue.vibrations.contains(CueVibration.TRANSITION))
        assertTrue(cue.spoken.any { it.contains("Workout complete") })
    }

    @Test
    fun quarter_vibratesGuidance() {
        val p = WorkoutProfile(
            id = 10, name = "T2", lengthMode = WorkoutLength.TIME, timeMinutes = 2,
            warmupSec = 0, pushSec = 60, slowSec = 60, cooldownSec = 0,
        )
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run()
        clock.advance(30_001) // t=30 = 25% of 120 s → FAST entry
        eng.tick() // phase change announced; quarter sits until the next tick
        clock.advance(1_000) // t=31
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("One quarter done") })
        assertTrue(cue.vibrations.contains(CueVibration.GUIDANCE))
    }

    @Test
    fun warningCue_vibratesGuidance() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
        assertTrue(cue.vibrations.contains(CueVibration.GUIDANCE))
    }

    @Test
    fun warningVibrates_EvenWhenAudioOff() {
        // Haptics are independent of the audio mode: a rate warning must
        // still vibrate when audio is off.
        val p = roundsProfile.copy(audioMode = AudioMode.OFF)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue, speed = 4.0f, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        assertTrue(cue.vibrations.contains(CueVibration.GUIDANCE))
    }

    @Test
    fun phaseChangeOnly_silencesGuidanceButKeepsTransitions() {
        // AudioMode.PHASE_CHANGE: quarter/warning cues stay silent, but phase
        // intros (and the finish) still announce and beep.
        val p = roundsProfile.copy(audioMode = AudioMode.PHASE_CHANGE)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue, speed = 4.0f, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(61_000) // t=61 → FAST entry
        eng.tick()
        clock.advance(1_000) // t=62: first warning tick (suppressed)
        eng.tick()
        tickPastSettle(eng, clock) // warning evaluates: silent (audio), but it still vibrates
        clock.advance(297_000) // t=360 → finish
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertTrue(cue.spoken.any { it.contains("Push phase 1") })
        assertTrue(cue.spoken.any { it.contains("Workout complete") })
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        assertTrue(cue.beeps > 0)
        // Haptics still mirror every cue class.
        assertTrue(cue.vibrations.contains(CueVibration.GUIDANCE))
    }

    @Test
    fun audioOff_silencesTransitionBeepsAndIntros() {
        // AudioMode.OFF: no speech at all and no transition beep; haptics are
        // the only remaining cue channel.
        val p = roundsProfile.copy(audioMode = AudioMode.OFF)
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(p, clock, cue)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        assertTrue(cue.spoken.isEmpty())
        assertEquals(0, cue.beeps)
        assertTrue(cue.vibrations.contains(CueVibration.TRANSITION))
    }

    @Test
    fun audioModeAll_playsAllCues() {
        // AudioMode.ALL (the default) announces transitions, quarters and
        // warnings; beeps fire on transitions.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 100) // < hrPushMin 150
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning tick (suppressed)
        eng.tick()
        tickPastSettle(eng, clock) // warning fires
        assertTrue(cue.spoken.any { it.contains("Push phase 1") })
        assertTrue(cue.spoken.any { it.contains("Speed up") })
        assertTrue(cue.beeps > 0)
    }

    @Test
    fun fastHrBetweenRecoveryMaxAndPushMin_cuesSpeedUp() {
        // The push min is the push target: HR inside the band (120–150) is
        // still below the push min and must cue.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 5.0f, hr = 130)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun slowHrBetweenRecoveryMaxAndPushMin_cuesSlowDown() {
        // The recovery max is the recovery target: HR inside the band (120–150)
        // is still above the recovery max and must cue.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 130)
        eng.run()
        clock.advance(121_000) // t=121 → SLOW
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun zeroHr_suppressesHrCue() {
        // HR 0 (no signal) must not cue even though it is far below the push min.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 5.0f, hr = 0)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun hrAtMinValidThreshold_triggersHrCue() {
        // 1 bpm is the min valid signal: speed is over the ceiling (5.0 > 4.5)
        // so only the HR condition can cue — and it must.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 5.0f, hr = 1)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun noSignalSpeed_suppressesSpeedCue() {
        // Speed at/below the 1.5 mph min-signal threshold (e.g. 0, GPS not
        // reporting) must not cue even though HR is below the push min.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 1.5f, hr = 160)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun speedJustAboveThreshold_triggersSpeedCue() {
        // 1.6 mph is above the 1.5 mph min-signal threshold: HR is above the
        // push min (160 > 150) so only the speed condition can cue — and it must.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 1.6f, hr = 160)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun zeroReadings_neverCueOnRecovery() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 0f, hr = 0)
        eng.run()
        clock.advance(121_000) // t=121 → SLOW
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun bothHrAndSpeedTrigger_singleCue() {
        // Speed and HR both below the push targets: exactly one cue and one vibration.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 100)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertEquals(1, cue.spoken.count { it.contains("Speed up") })
        assertEquals(1, cue.vibrations.count { it == CueVibration.GUIDANCE })
    }

    // ---- pace (pedometer) warning cues ----

    @Test
    fun fastPaceBelowFloor_cues() {
        // Speed and HR on target (at/above the speed floor + HR push min); only pace is off.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 150, pace = 85) // < paceFloor 100
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun slowPaceAboveCeiling_cues() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 115, pace = 125) // > paceCeiling 110
        eng.run()
        clock.advance(121_000) // t=121 → SLOW
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertTrue(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun noSignalPace_suppressesPaceCue() {
        // Pace 0 (no signal) must not cue even though speed/HR are on target
        // and only the pace condition could fire.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 150, pace = 0)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun deliberateStandstillPace_doesNotCue() {
        // A pace at/below the standstill floor (10 spm) must not cue in either
        // phase even though only the pace condition could fire — walking that
        // slowly is intentional, so it is not a missed target. Speed/HR are on
        // target so only the pace gate decides.
        // Push side: pace below floor.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 4.0f, hr = 150, pace = 5)
        eng.run()
        clock.advance(61_000)
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Speed up") })
        // Recovery side: pace below ceiling, speed/HR inside the recovery band.
        val clock2 = FakeClock(1_000)
        val cue2 = RecordingCue()
        val eng2 = engineWith(roundsProfile, clock2, cue2, speed = 2.0f, hr = 115, pace = 5)
        eng2.run()
        clock2.advance(121_000) // t=121 → SLOW
        eng2.tick()
        clock2.advance(1_000) // first warning cue after entry suppressed
        eng2.tick()
        clock2.advance(1_000)
        eng2.tick()
        assertFalse(cue2.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun recoveryPaceBelowCeiling_doesNotCue() {
        // Recovery: pace is a valid signal (90 ≥ 10) but below the recovery max
        // (90 < 110), and speed/HR are inside the recovery band — the target is
        // met, so no "Slow down" cue may fire.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 115, pace = 90)
        eng.run()
        clock.advance(121_000) // t=121 → SLOW
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed
        assertFalse(cue.spoken.any { it.contains("Slow down") })
    }

    @Test
    fun slowPaceOff_cuesExactlyOnce() {
        // Recovery: only pace is off (above ceiling); exactly one shared cue + vibration.
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(roundsProfile, clock, cue, speed = 2.0f, hr = 115, pace = 125)
        eng.run()
        clock.advance(121_000) // t=121 → SLOW
        eng.tick()
        clock.advance(1_000) // first warning cue after entry suppressed
        eng.tick()
        tickPastSettle(eng, clock) // settle window elapsed → the warning fires
        assertEquals(1, cue.spoken.count { it.contains("Slow down") })
        // The 1/2-round quarter cue also vibrates GUIDANCE, so require at least
        // one guidance vibration rather than an exact count.
        assertTrue(cue.vibrations.contains(CueVibration.GUIDANCE))
    }
}