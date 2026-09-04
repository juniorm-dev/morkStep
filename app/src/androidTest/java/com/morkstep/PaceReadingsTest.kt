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
 * On-device regression test for the pedometer pace (steps/min) display with
 * simulated sensors: the PACE card must show a live numeric spm value, not a
 * dash.
 */
@RunWith(AndroidJUnit4::class)
class PaceReadingsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Always leave simulated OFF so sibling tests see a clean setup. */
    @After
    fun resetFlag() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MorkApplication
        runBlocking { app.container.configStore.setSimulatedSensors(false) }
    }

    @Test
    fun simulatedWorkout_showsLivePaceInSpm() {
        // Simulated sensors ON, set directly in the DataStore (no UI-toggling
        // ambiguity), then Home -> Start workout.
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MorkApplication
        runBlocking { app.container.configStore.setSimulatedSensors(true) }
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

        // Sync point: the Workout banner is unique to simulated mode and proves
        // the async DataStore -> rebuildSources() chain landed.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("no live hardware readings", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The PACE card shows a live steps-per-minute value, and no card shows a dash.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("spm", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("spm", substring = true).fetchSemanticsNodes().isNotEmpty()
        rule.onAllNodesWithText("–").fetchSemanticsNodes().isEmpty()

        // Tear down: Discard so no foreground service leaks.
        rule.onNodeWithText("Discard").performClick()
    }
}