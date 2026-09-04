package com.morkstep.wear

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode tests for the phone's 35-byte `/morkstep/state` relay payload. */
class WearSessionStateTest {

    private fun encodePhonePayload(
        phaseOrd: Int = 2,
        paused: Boolean = false,
        running: Boolean = true,
        secondsInPhase: Int = 45,
        speed: Float = 4.8f,
        fastDone: Int = 3,
        fastTotal: Int = 5,
        fastSec: Int = 180,
        slowSec: Int = 120,
        floor: Float = 3.2f,
        ceiling: Float = 4.5f,
    ): ByteArray {
        val buf = ByteBuffer.allocate(35).order(ByteOrder.BIG_ENDIAN)
        buf.put(phaseOrd.toByte())
        buf.put((if (paused) 1 else 0).toByte())
        buf.put((if (running) 1 else 0).toByte())
        buf.putInt(secondsInPhase)
        buf.putFloat(speed)
        buf.putInt(fastDone)
        buf.putInt(fastTotal)
        buf.putInt(fastSec)
        buf.putInt(slowSec)
        buf.putFloat(floor)
        buf.putFloat(ceiling)
        return buf.array()
    }

    @Test
    fun decode_fullPayloadRoundTrips() {
        val st = decodeWearSessionState(
            encodePhonePayload(
                phaseOrd = 3, paused = true, running = true, secondsInPhase = 30,
                speed = 2.9f, fastDone = 4, fastTotal = 6, fastSec = 200, slowSec = 100,
                floor = 3.0f, ceiling = 4.2f,
            )
        )
        assertEquals(3, st.phaseOrd)
        assertTrue(st.paused)
        assertTrue(st.running)
        assertEquals(30, st.secondsInPhase)
        assertEquals(2.9f, st.speed!!, 0.001f)
        assertEquals(4, st.fastDone)
        assertEquals(6, st.fastTotal!!)
        assertEquals(200, st.fastSec)
        assertEquals(100, st.slowSec)
        assertEquals(3.0f, st.speedFloor, 0.001f)
        assertEquals(4.2f, st.speedCeiling, 0.001f)
    }

    @Test
    fun decode_nanSpeedBecomesNull() {
        val st = decodeWearSessionState(encodePhonePayload(speed = Float.NaN))
        assertNull(st.speed)
    }

    @Test
    fun decode_negativeFastTotalBecomesNull() {
        val st = decodeWearSessionState(encodePhonePayload(fastTotal = -1))
        assertNull(st.fastTotal)
    }

    @Test
    fun decode_shortPayloadYieldsDefaults() {
        val st = decodeWearSessionState(byteArrayOf(2, 0, 1))
        assertEquals(0, st.phaseOrd)
        assertFalse(st.running)
        assertNull(st.speed)
        assertEquals(180, st.fastSec)
    }
}