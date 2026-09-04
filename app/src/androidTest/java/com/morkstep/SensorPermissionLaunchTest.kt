package com.morkstep

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression test for the "crash on restart after granting sensor
 * permissions" bug.
 *
 * Pre-fix, the in-app "Grant sensor permissions" flow (location + Bluetooth
 * scan/connect) made every subsequent launch crash: `BleHeartRateSource`'
 * scan start threw inside the config collector during activity startup and
 * only `SecurityException` was caught (`IllegalArgumentException: callback is
 * null` on Android 16 emulator stacks). Granting the same runtime permissions
 * in [grantSensorPermissions] before the activity launches reproduces the
 * restart condition exactly; the rule's activity launch fails this test if
 * startup throws again.
 */
@RunWith(AndroidJUnit4::class)
class SensorPermissionLaunchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWhenAllSensorPermissionsGranted() {
        // The app must reach the home screen with location + Bluetooth granted.
        rule.onNodeWithText("morkStep").assertExists()
    }

    companion object {
        /** Same runtime grants the Settings screen's sensor button requests. */
        @BeforeClass
        @JvmStatic
        fun grantSensorPermissions() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).forEach { permission ->
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }
}