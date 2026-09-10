package com.morkstep

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end test that a finished workout actually lands in the
 * History screen with its session record (duration, push count, averages) —
 * the Room write path no UI test covered before.
 *
 * Uses simulated sensors (no hardware) and shortens the active profile's
 * intervals so one full push round completes in seconds. The session is
 * finished during recovery, so the row must show 1 completed push and the
 * per-phase speed/HR averages recorded during the session.
 *
 * Not part of the default build/test lifecycle. Run explicitly:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class WorkoutHistoryTest {

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
    fun finishedWorkout_appearsInHistoryWithPushAndAverages() {
        // Fully store-driven setup — no dependence on which screen/active
        // profile sibling tests left behind: discard an open workout screen,
        // force the Default profile active, shorten its intervals (5 s warm-up,
        // 15 s push, 30 s recovery — a full push round completes in ~20 s),
        // and enable simulated sensors (no hardware needed).
        if (rule.onAllNodesWithText("Discard").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Discard").performClick()
        }
        runBlocking {
            val store = (rule.activity.application as MorkApplication).container.configStore
            val defaultId = store.profiles.first().first { it.name == "Default" }.id
            store.saveProfiles(
                store.profiles.first().map { p ->
                    if (p.id == defaultId) p.copy(warmupSec = 5, pushSec = 15, slowSec = 30, cooldownSec = 0) else p
                }
            )
            store.setActive(defaultId)
            store.setSimulatedSensors(true)
        }
        rule.onAllNodesWithText("Home").onFirst().performClick()
        // Sync point: the home plan card re-renders with the shortened plan once
        // the DataStore writes land; starting earlier could begin the session
        // with the stale (3-min warm-up) profile. Sub-minute intervals render in
        // plain seconds ("15s push / 30s recovery").
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("15s push / 30s recovery", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Start workout").assertExists()
        rule.onNodeWithText("Start workout").performClick()

        // Sync point: the simulated banner proves the DataStore → rebuild chain landed.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("no live hardware readings", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Ride past the first push so one full push completes. The phase circle
        // reads PUSH / RECOVERY; "RECOVERY" matching two nodes is exactly the
        // BaselineFlow convention (BarsView label + live phase circle).
        rule.waitUntil(timeoutMillis = 120_000) {
            rule.onAllNodesWithText("RECOVERY").fetchSemanticsNodes().size >= 2
        }
        Thread.sleep(2_000) // a couple of recovery samples too
        rule.onNodeWithText("Finish").performClick()

        // Non-baseline finish pops back to the main shell; wait for the nav shell
        // to be interactive, then open History.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("History").onFirst().performClick()

        // The row exists with the completed push count and per-phase averages —
        // and the empty-state banners are gone.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("push intervals", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodesWithText("1 push intervals", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("speed mph", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("HR bpm", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse("empty-state text must be gone once a workout exists",
            rule.onAllNodesWithText("No workouts yet").fetchSemanticsNodes().isNotEmpty())
        // The entry names the profile the session ran under.
        assertTrue(rule.onAllNodesWithText("Default", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Collapsed, the card gives the overall averages only — no phase rows yet.
        assertTrue(rule.onAllNodesWithText("Show phase breakdown", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse("the per-phase breakdown stays hidden until the card is tapped",
            rule.onAllNodesWithText("Warm-up").fetchSemanticsNodes().isNotEmpty())

        // Tapping the newest card (the top row, this session) opens the per-phase
        // averages and the line chart. Sibling tests may have left rows of their
        // own, so the tap targets the first card rather than a single match.
        rule.onAllNodesWithText("Show phase breakdown").onFirst().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Warm-up").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodesWithText("Push 1").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Recovery 1").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Phase chart").assertExists()
    }
}