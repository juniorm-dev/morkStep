package com.morkstep

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose tests for the Baseline flow (phone app).
 *
 * Not part of the default build/test lifecycle (`assembleDebug` /
 * `testDebugUnitTest` never compile or run these). Run explicitly:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * `clearPackageData` wipes app data before the run, so each test starts with
 * a fresh DataStore (one default profile, nothing else).
 */
@RunWith(AndroidJUnit4::class)
class BaselineFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsStartWorkoutForDefaultProfile() {
        // Deterministic regardless of run order: if a prior test left the
        // Baseline profile active, switch back to Default first.
        if (rule.onAllNodesWithText("Start baseline").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Default").performClick()
        }
        rule.onNodeWithText("Start workout").assertExists()
        rule.onNodeWithText("Start baseline").assertDoesNotExist()
    }

    @Test
    fun createBaseline_activatesCalibrationAndShowsStartBaselineOnHome() {
        // Settings → Create baseline.
        rule.onAllNodesWithText("Settings").onFirst().performClick()
        rule.onNodeWithText("Profile settings").assertExists()
        rule.onNodeWithText("Create baseline").performScrollTo().performClick()

        // The save flow returns to Home with the Baseline profile active: the
        // start button reads "Start baseline" and the calibration plan shows.
        // Save triggers async navigation (DataStore -> snackbar -> Home); wait for it.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Start baseline").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Start baseline").assertExists()
        // Baseline is hidden from the profile list by design; the plan card title proves it is active.
        rule.onNodeWithText("Workout plan", substring = true).assertExists()
        rule.onNodeWithText("45s push / 45s recovery", substring = true).assertExists()

        // Restore the default profile so this test leaves no active-baseline state behind
        // for sibling tests (data persists for the rest of the run without orchestrator).
        rule.onNodeWithText("Default").performClick()
    }
}