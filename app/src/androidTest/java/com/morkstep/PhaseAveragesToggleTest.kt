package com.morkstep

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose test for the experimental per-profile "Level out phase
 * transitions" toggle (resets each phase's average to just inside its target
 * band on phase entry — push min + 1 / recovery max - 1).
 *
 * Guards the full UI → DataStore round-trip: the switch renders on for a fresh
 * profile (the default), toggling flips it in the UI, and Save profile
 * persists the new value into the active profile. The engine-side seeding
 * arithmetic itself is covered by the JVM unit tests; this guards the switch
 * wiring and default that decides whether seeding runs.
 *
 * Not part of the default build/test lifecycle. Run explicitly:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PhaseAveragesToggleTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun activeProfile() = runBlocking {
        val store = (rule.activity.application as MorkApplication).container.configStore
        val activeId = store.activeId.first()
        store.profiles.first().first { it.id == activeId }
    }

    @Test
    fun phaseAveragesToggle_togglesAndPersistsPerProfile() {
        // Fresh app state seeds the default profile with the toggle ON.
        assertTrue("default profile must have resetPhaseAverages on", activeProfile().resetPhaseAverages)

        rule.onAllNodesWithText("Settings").onFirst().performClick()
        rule.onNodeWithText("Profile settings").assertExists()

        // The switch sits beside the "Level out phase transitions" label;
        // only the Switch node is toggleable, so this matches exactly one node.
        val toggle = rule.onNode(
            isToggleable() and hasAnySibling(hasText("Level out phase transitions"))
        )
        toggle.performScrollTo().assertIsOn()

        // Flip it off and save.
        toggle.performClick()
        toggle.assertIsOff()
        rule.onNodeWithText("Save profile").performScrollTo().performClick()

        // Save returns Home; the snackbar confirms, then the value lands in the
        // active profile's persisted record.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Save profile").fetchSemanticsNodes().isEmpty()
        }
        assertFalse(
            "toggling off must persist resetPhaseAverages=false to the profile",
            activeProfile().resetPhaseAverages,
        )
    }
}