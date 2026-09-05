package com.morkstep

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.morkstep.data.WorkoutProfile
import com.morkstep.data.isBaselineProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose tests for the Baseline flow (phone app).
 *
 * Covers the full lifecycle: creating the calibration profile, and finishing a
 * calibration workout (with simulated sensors) into the calibrated 30-minute
 * baseline that returns to Settings.
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

    @Test
    fun baselineWorkoutFinish_calibratesProfileAndReturnsToSettings() {
        // Deterministic regardless of run order: if a prior test left the
        // Baseline profile active, switch back to Default first.
        if (rule.onAllNodesWithText("Start baseline").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Default").performClick()
        }

        // Settings → Create baseline → auto-returns Home with Baseline active.
        rule.onAllNodesWithText("Settings").onFirst().performClick()
        rule.onNodeWithText("Profile settings").assertExists()
        rule.onNodeWithText("Create baseline").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Start baseline").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Start baseline").assertExists()

        // Simulated sensors so the calibration session records speed/pace/HR
        // averages without GPS/BLE hardware (deterministic phase targets).
        // Set via DataStore directly, matching the sensor-reading tests.
        runBlocking {
            (rule.activity.application as MorkApplication)
                .container.configStore.setSimulatedSensors(true)
        }

        // Speed up this test: override the active Baseline's calibration
        // intervals (5s warm-up, 15s push, 30s recovery, no cool-down) instead
        // of the 20/45/45 defaults — same phase-averages contract, ~75 s less
        // wall-clock. The plan card re-renders once the DataStore write lands,
        // which is the sync point before starting.
        runBlocking {
            val store = (rule.activity.application as MorkApplication).container.configStore
            val activeId = store.activeId.first()
            store.saveProfiles(
                store.profiles.first().map { p ->
                    if (p.id == activeId) p.copy(warmupSec = 5, pushSec = 15, slowSec = 30, cooldownSec = 0) else p
                }
            )
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("15s push / 30s recovery", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Start the calibration workout (3 rounds, now 5/15/30 s intervals).
        rule.onNodeWithText("Start baseline").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Simulated sensors (debug) — no live hardware readings")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Reach the first RECOVERY phase (5s warm-up + 15s push) and ride most of
        // it so the recovery averages decay toward the simulated targets
        // (~2.5 mph / ~95 spm / ~117 bpm — firmly inside the recovery band).
        // Exact "RECOVERY" matches two nodes once the phase circle shows it: the
        // static BarsView segment label plus the live phase circle (the circle
        // reads WARM UP / PUSH / RECOVERY / COOL DOWN).
        rule.waitUntil(timeoutMillis = 120_000) {
            rule.onAllNodesWithText("RECOVERY").fetchSemanticsNodes().size >= 2
        }
        Thread.sleep(22_000)
        rule.onNodeWithText("Finish early").performClick()

        // onFinished re-derives the calibrated baseline; the app jumps to
        // Settings and confirms with a snackbar.
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Profile settings").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Baseline created").fetchSemanticsNodes().isNotEmpty()
        }

        // The calibrated profile is persisted: 30 min, 120/120/30/30 s, and the
        // speed/pace/HR bands derived from the simulated phase averages. With
        // the shortened intervals (15 push ticks toward 4.0 mph / 118 spm /
        // 138 bpm, ~22 recovery ticks toward 2.2 mph / 92 spm / 112 bpm), the
        // session means sit at ~ (3.3 mph / ~112 spm / ~127 bpm) for push and
        // ~ (2.5 mph / ~95 spm / ~117 bpm) for recovery — each inside its band
        // and each moved from the calibration defaults (4.5/3.2 mph, 110/100
        // spm, 150/120 bpm).
        val app = rule.activity.application as MorkApplication
        val deadline = System.currentTimeMillis() + 15_000
        var baseline: WorkoutProfile? = null
        while (System.currentTimeMillis() < deadline) {
            baseline = runBlocking { app.container.configStore.profiles.first() }
                .firstOrNull { isBaselineProfile(it) }
            if (baseline?.timeMinutes == 30) break
            Thread.sleep(250)
        }
        assertNotNull("baseline should be re-derived to 30 min after the workout", baseline)
        val bp = baseline!!
        assertEquals(30, bp.timeMinutes)
        assertEquals(120, bp.pushSec)
        assertEquals(120, bp.slowSec)
        assertEquals(30, bp.warmupSec)
        assertEquals(30, bp.cooldownSec)
        // Speed band follows the averages (calibration defaults: 4.5 floor / 3.2 cap).
        assertTrue("push floor from push avg (~3.3) is below default 4.5", bp.pushSpeedFloorMph < 4.5)
        assertTrue("recovery cap from recovery avg (~2.5) is below default 3.2", bp.recoverySpeedCapMph < 3.2)
        // Pace band follows the averages (calibration defaults: 110 floor / 100 cap).
        // The push mean approaches 118 from ~97 and saturates mid-phase, so it
        // lands inside a bounded window and differs from the default floor.
        assertTrue("push pace floor from push avg is in (105, 118)", bp.pushPaceFloorSpm in 106..117)
        assertTrue("recovery pace cap from recovery avg (~96) is below default 100", bp.recoveryPaceCapSpm < 100)
        // HR band follows the averages (calibration defaults: 150 min / 120 max).
        assertTrue("push HR min from push avg (~127) is below default 150", bp.hrPushMin < 150)
        assertTrue("recovery HR max from recovery avg (~117) is below default 120", bp.hrRecoveryMax < 120)
        assertTrue("push HR min stays above recovery HR max", bp.hrPushMin > bp.hrRecoveryMax)

        // Home plan card reflects the calibrated plan.
        rule.onAllNodesWithText("Home").onFirst().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("Workout plan · Baseline", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("30 min · 2:00 push / 2:00 recovery", substring = true).assertExists()
        rule.onNodeWithText("Warm-up 30s · cool-down 30s", substring = true).assertExists()

        // Restore the default profile and turn simulated sensors off so sibling
        // tests stay deterministic (data persists for the rest of the run).
        rule.onNodeWithText("Default").performClick()
        runBlocking {
            (rule.activity.application as MorkApplication)
                .container.configStore.setSimulatedSensors(false)
        }
    }
}