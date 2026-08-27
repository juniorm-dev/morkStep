package com.morkstep.engine

import com.morkstep.data.IntervalConfig
import com.morkstep.data.IntervalSegment
import com.morkstep.data.PhaseType
import com.morkstep.data.defaultConfig
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun setPace(p: Float?) { _pace.value = p }
    fun setHr(h: Int?) { _hr.value = h }
}

class RecordingCue : CueSink {
    val spoken = mutableListOf<String>()
    var beeps = 0
    override fun beep() { beeps++ }
    override fun speak(text: String) { spoken.add(text) }
}

class SessionEngineTest {

    private val config = IntervalConfig(
        segments = listOf(
            IntervalSegment(PhaseType.WARMUP, 60),
            IntervalSegment(PhaseType.FAST, 60),
            IntervalSegment(PhaseType.SLOW, 60),
            IntervalSegment(PhaseType.COOLDOWN, 60),
        ),
        paceCeiling = 6.5,
        paceFloor = 5.0,
        hrCeiling = 150,
        hrFloor = 120,
    )

    private fun engineWith(clock: FakeClock, cue: RecordingCue, pace: Float? = 6.2f, hr: Int? = 130) =
        SessionEngine(config, FakeSensors(pace, hr), FakeSensors(pace, hr), cue, clock)

    @Test
    fun segmentIndexFor_mapsTimeToSegment() {
        val segs = config.segments
        assertEquals(0, segmentIndexFor(0, segs))
        assertEquals(0, segmentIndexFor(59, segs))
        assertEquals(1, segmentIndexFor(60, segs))
        assertEquals(1, segmentIndexFor(119, segs))
        assertEquals(2, segmentIndexFor(120, segs))
        assertEquals(3, segmentIndexFor(180, segs))
        assertEquals(3, segmentIndexFor(300, segs))
    }

    @Test
    fun secondsInSegment_countsWithinSegment() {
        val segs = config.segments
        assertEquals(0, secondsInSegment(0, segs))
        assertEquals(59, secondsInSegment(59, segs))
        assertEquals(0, secondsInSegment(60, segs))
        assertEquals(30, secondsInSegment(90, segs))
    }

    @Test
    fun tick_advancesPhaseAndBeeps() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(clock, cue)
        eng.run()
        assertEquals(PhaseType.WARMUP, eng.snapshot.phase)

        clock.advance(61_000)
        eng.tick()
        assertEquals(PhaseType.FAST, eng.snapshot.phase)
        assertEquals(1, eng.snapshot.phaseIndex)
        assertEquals(1, eng.snapshot.secondsInPhase)
        assertTrue(cue.beeps >= 1)
        assertTrue(cue.spoken.any { it.contains("Push") })
    }

    @Test
    fun tick_marksFinishedAndCountsFastSegments() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(clock, cue)
        eng.run()
        clock.advance(241_000) // past total 240s
        eng.tick()
        assertTrue(eng.snapshot.finished)
        assertEquals(1, eng.snapshot.fastSegmentsDone)
        assertEquals(PhaseType.COOLDOWN, eng.snapshot.phase)
    }

    @Test
    fun slowPaceBelowFloor_producesCue() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(clock, cue, pace = 4.2f, hr = 128)
        eng.run()
        clock.advance(61_000) // now in FAST phase, pace 4.2 < floor 5.0
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Speed up") })
    }

    @Test
    fun highHr_producesCueAndCountsOverCeiling() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(clock, cue, pace = 6.2f, hr = 165)
        eng.run()
        clock.advance(61_000) // FAST phase, HR 165 > 150
        eng.tick()
        assertTrue(eng.snapshot.overCeilingSec >= 1)
        assertTrue(cue.spoken.any { it.contains("high") })
    }

    @Test
    fun cooldown_announced() {
        val clock = FakeClock(1_000)
        val cue = RecordingCue()
        val eng = engineWith(clock, cue)
        eng.run()
        clock.advance(181_000)
        eng.tick()
        assertTrue(cue.spoken.any { it.contains("Cooldown") })
    }

    @Test
    fun defaultConfig_hasStandardIwtShape() {
        val c = defaultConfig()
        assertEquals(PhaseType.WARMUP, c.segments.first().type)
        assertEquals(PhaseType.COOLDOWN, c.segments.last().type)
        assertEquals(5, c.segments.count { it.type == PhaseType.FAST })
        assertEquals(5, c.segments.count { it.type == PhaseType.SLOW })
        assertEquals(180, c.segments.first { it.type == PhaseType.FAST }.seconds)
    }
}