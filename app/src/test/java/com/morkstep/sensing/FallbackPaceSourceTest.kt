package com.morkstep.sensing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Watch-preferred, phone-fallback switch logic in [FallbackPaceSource]. */
class FallbackPaceSourceTest {

    private class FakePace : PaceSource {
        override val pace = MutableStateFlow<Int?>(null)
    }

    private fun merge(wear: PaceSource, phone: PaceSource, now: FakeNow, scope: CoroutineScope) =
        FallbackPaceSource(wear, phone, staleAfterMs = 15_000L, nowMs = { now.value })

    private class FakeNow {
        var value = 0L
    }

    @Test
    fun phoneDrivesPaceUntilWatchAppears() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val wear = FakePace()
        val phone = FakePace()
        val now = FakeNow()
        val m = merge(wear, phone, now, scope)
        assertNull(m.pace.value)

        m.start(scope)
        phone.pace.value = 100
        assertEquals(100, m.pace.value)

        // Watch starts streaming: it wins immediately.
        now.value = 5_000L
        wear.pace.value = 120
        assertEquals(120, m.pace.value)

        // Phone value while the watch is fresh is suppressed.
        now.value = 10_000L
        phone.pace.value = 105
        assertEquals(120, m.pace.value)
    }

    @Test
    fun phoneTakesOverAfterWatchGoesSilent_andHandsBack() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val wear = FakePace()
        val phone = FakePace()
        val now = FakeNow()
        val m = merge(wear, phone, now, scope)
        m.start(scope)

        now.value = 1_000L
        wear.pace.value = 110
        assertEquals(110, m.pace.value)

        now.value = 5_000L // 4 s since the watch sample: still fresh
        phone.pace.value = 90
        assertEquals(110, m.pace.value)

        now.value = 20_000L // 19 s since the watch sample: stale
        phone.pace.value = 95
        assertEquals(95, m.pace.value)

        // Watch resumes: it takes over again instantly.
        now.value = 21_000L
        wear.pace.value = 118
        assertEquals(118, m.pace.value)
    }

    @Test
    fun nullWatchSampleDoesNotBlankPhoneValue() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val wear = FakePace()
        val phone = FakePace()
        val m = merge(wear, phone, FakeNow(), scope)
        m.start(scope)
        phone.pace.value = 100
        assertEquals(100, m.pace.value)
        // The watch connects but sends nothing: its initial null must not
        // overwrite the phone-driven value.
        wear.pace.value = null
        assertEquals(100, m.pace.value)
    }
}