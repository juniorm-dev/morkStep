package com.morkstep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug log serves two readers: the export, which needs the whole retained
 * session trace, and the workout screen, which may only mirror the newest lines.
 * Both views must stay bounded and stay in sync with the same buffer.
 */
class DebugLogTest {

    @Test
    fun keepsTheWholeTraceForExportAndOnlyTheNewestLinesForTheScreen() {
        val log = DebugLog(maxLines = 4, displayLines = 2)
        (1..4).forEach { log.log("event $it") }

        assertTrue(log.text.value.contains("event 1"))
        assertTrue(log.text.value.contains("event 4"))
        // The screen shows the tail of that trace, not all of it.
        assertTrue(log.displayText.value.contains("event 4"))
        assertTrue(log.displayText.value.contains("event 3"))
        assertFalse(log.displayText.value.contains("event 2"))
        assertEquals(2, log.displayText.value.lines().size)
        assertTrue(log.text.value.endsWith(log.displayText.value))
    }

    @Test
    fun dropsTheOldestLineFromBothViewsOnceFull() {
        val log = DebugLog(maxLines = 4, displayLines = 2)
        (1..6).forEach { log.log("event $it") }

        assertEquals(4, log.text.value.lines().size)
        assertFalse(log.text.value.contains("event 2"))
        assertTrue(log.text.value.contains("event 3"))
        assertFalse(log.displayText.value.contains("event 4"))
        assertTrue(log.displayText.value.contains("event 5"))
    }

    @Test
    fun clearingWipesBothViews() {
        val log = DebugLog(maxLines = 4, displayLines = 2)
        log.log("event 1")
        log.clear()

        assertEquals("", log.text.value)
        assertEquals("", log.displayText.value)
    }
}
