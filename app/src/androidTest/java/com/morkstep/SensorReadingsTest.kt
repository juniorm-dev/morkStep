package com.morkstep

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression test for live sensor readings with simulated sensors.
 *
 * Catches the "no sensor readings" bug: starts a real workout with simulated
 * sensors ON (set directly in the DataStore via ConfigStore, no UI-toggling
 * ambiguity) and asserts the live speed/HR/pace values actually stream into
 * the Workout screen — not blank dashes.
 */
@RunWith(AndroidJUnit4::class)
class SensorReadingsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun setSimulated(on: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MorkApplication
        runBlocking { app.container.configStore.setSimulatedSensors(on) }
    }

    /** Always leave simulated OFF so sibling tests see a clean setup. */
    @After
    fun resetFlag() {
        setSimulated(false)
    }

    /** Set the DataStore flag, back to Home, and start the workout once ready. */
    private fun startSimulatedWorkout() {
        setSimulated(true)
        // Make sure we are on Home. The start button can read "Start workout" or
        // "Start baseline" (a prior BaselineFlowTest may have left the baseline
        // profile active); both start a running session.
        rule.onAllNodesWithText("Home").onFirst().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Start workout").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Start baseline").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Start workout").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Start workout").performClick()
        } else {
            rule.onNodeWithText("Start baseline").performClick()
        }
        // The Workout screen's banner is unique to simulated mode; waiting on it
        // guarantees the async DataStore -> rebuildSources() chain landed.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("no live hardware readings", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun simulatedWorkout_showsLiveSensorReadings() {
        startSimulatedWorkout()

        // Live speed (mph), heart rate (bpm) and pace (spm) must appear — not dashes.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("mph", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("mph", substring = true).fetchSemanticsNodes().isNotEmpty()
        rule.onAllNodesWithText("bpm", substring = true).fetchSemanticsNodes().isNotEmpty()
        rule.onAllNodesWithText("spm", substring = true).fetchSemanticsNodes().isNotEmpty()
        // A dash ("–") means a reading never reached the UI.
        assert(rule.onAllNodesWithText("–").fetchSemanticsNodes().isEmpty()) {
            "a sensor card shows a dash instead of a live reading"
        }

        // Tear down: Discard so no foreground service leaks.
        rule.onNodeWithText("Discard").performClick()
    }
}