package com.morkstep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published build folder holds several versions at once, so the check must pick the
 * highest one by numeric version — never by listing order or file date — and must not be
 * tripped by the Wear APK or the debug-log files that share the folder.
 */
class UpdateCheckTest {

    @Test
    fun picksTheHighestVersionWhenTheFolderHoldsSeveral() {
        val body = listing(
            "morkStep-debug-0.16.3.apk",
            "morkStep-debug-0.16.9.apk",
            "morkStep-debug-0.15.2.apk",
        )
        assertEquals("0.16.9", UpdateCheck.latestVersionIn(body))
    }

    @Test
    fun comparesVersionComponentsNumericallyNotAsText() {
        val body = listing("morkStep-debug-0.16.10.apk", "morkStep-debug-0.16.9.apk")
        assertEquals("0.16.10", UpdateCheck.latestVersionIn(body))
    }

    @Test
    fun ignoresTheWearApkAndTheLogFiles() {
        val body = listing(
            "morkStep-wear-debug-0.6.1.apk",
            "morkStep-debug-0.14.2-1789304278028.txt",
        )
        assertNull(UpdateCheck.latestVersionIn(body))
    }

    @Test
    fun toleratesASuffixedFileName() {
        assertEquals("0.16.4", UpdateCheck.latestVersionIn(listing("morkStep-debug-0.16.4 (1).apk")))
    }

    @Test
    fun isNewerComparesDottedVersions() {
        assertTrue(UpdateCheck.isNewer("0.16.4", "0.16.3"))
        assertTrue(UpdateCheck.isNewer("0.16.10", "0.16.9"))
        assertFalse(UpdateCheck.isNewer("0.16.3", "0.16.3"))
        assertFalse(UpdateCheck.isNewer("0.16.2", "0.16.3"))
    }

    /** A stand-in for the listing body's `"name"` fields. */
    private fun listing(vararg names: String) = names.joinToString(",") { """{"name":"$it"}""" }
}
