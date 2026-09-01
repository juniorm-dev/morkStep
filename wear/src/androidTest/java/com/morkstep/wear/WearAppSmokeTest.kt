package com.morkstep.wear

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose smoke tests for the Wear companion app.
 *
 * NOT part of the default build/test lifecycle — run explicitly when needed:
 *
 *   ./gradlew :wear:connectedDebugAndroidTest
 *
 * against a booted Wear emulator/device. BODY_SENSORS is pre-granted so the
 * runtime permission dialog never blocks the assertions; the app renders
 * "HR: -- bpm" because an emulator has no heart-rate sensor (null HR is kept
 * deliberately, never simulated).
 */
@RunWith(AndroidJUnit4::class)
class WearAppSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BODY_SENSORS)

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsTitleAndVersion() {
        composeRule.onNodeWithText("morkStep Wear").assertExists()
        val versionName = composeRule.activity.packageManager
            .getPackageInfo(composeRule.activity.packageName, 0).versionName
        composeRule.onNodeWithText("v$versionName").assertExists()
    }

    @Test
    fun hrShowsPlaceholderWithoutSensor() {
        // No physical sensor on an emulator: the app intentionally stays "unknown".
        composeRule.onNodeWithText("HR: -- bpm").assertExists()
    }
}