package com.morkstep.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleHeartRateSourceTest {
    @Test
    fun parseHeartRate_8bit() {
        // flags 0x00, HR 0x54 = 84
        assertEquals(84, BleHeartRateSource.parseHeartRate(byteArrayOf(0x00, 0x54)))
    }

    @Test
    fun parseHeartRate_16bit() {
        // flags 0x01 (16-bit), HR little-endian 0x02 0x5E → 0x5E02 = 24066? HR 16-bit is raw uint16 → 410? 
        // 0x5E02 = 24066 — implausible HR but parse must be exact: (0x02) | (0x5E << 8)
        assertEquals(0x5E02, BleHeartRateSource.parseHeartRate(byteArrayOf(0x01, 0x02, 0x5E)))
    }

    @Test
    fun parseHeartRate_emptyReturnsNull() {
        assertNull(BleHeartRateSource.parseHeartRate(byteArrayOf()))
    }

    @Test
    fun parseHeartRate_short16bitReturnsNull() {
        assertNull(BleHeartRateSource.parseHeartRate(byteArrayOf(0x01, 0x02)))
    }
}