package com.morkstep

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose smoke tests for the phone app.
 *
 * These are NOT part of the default build/test lifecycle (`assembleDebug` and
 * `testDebugUnitTest` never compile or run them). Run explicitly when needed:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * against a booted emulator/device. `clearPackageData` (set in
 * app/build.gradle.kts) wipes app data before the run, so startup is
 * deterministic: a fresh DataStore seeds exactly one default profile and the
 * history is empty, which is what the assertions below rely on.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsHomeScreen() {
        rule.onNodeWithText("morkStep").assertExists()
        rule.onNodeWithText("Interval Walking Training").assertExists()
    }

    @Test
    fun homeShowsProfilesAndStartButton() {
        // Fresh app state seeds exactly one profile; its name is "Default".
        rule.onNodeWithText("Select profile").assertExists()
        rule.onNodeWithText("Default").assertExists()
        // The plan card renders for the seeded profile.
        rule.onNodeWithText("Start workout").assertExists()
    }

    @Test
    fun navigateToHistoryShowsEmptyState() {
        // "History" appears twice (bottom nav + Home button) — act on the first.
        rule.onAllNodesWithText("History").onFirst().performClick()
        rule.onNodeWithText("No workouts yet").assertExists()
        rule.onNodeWithText("Finish a session and it will appear here.").assertExists()
    }

    @Test
    fun configShowsVersionFooter() {
        val versionName = rule.activity.packageManager
            .getPackageInfo(rule.activity.packageName, 0).versionName
        rule.onAllNodesWithText("Settings").onFirst().performClick()
        rule.onNodeWithText("Profile settings").assertExists()
        rule.onNodeWithText("morkStep  v$versionName").assertExists()
    }
}