package com.morkstep.wear

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the wear graphics panel (Off/Bars/Band/Gauge).
 *
 * Rendered directly via setContent (no activity): the panel is only shown
 * while a workout is active, which requires the phone relay — on a lone
 * emulator there is no paired source, so these assert the panel itself.
 */
@RunWith(AndroidJUnit4::class)
class WearGraphicsPanelTest {

    @get:Rule
    val rule = createComposeRule()

    // FAST phase, on target: speed 4.8 >= ceiling 4.5, pace 115 >= floor 100.
    private val session = WearSessionState(
        phaseOrd = 2,
        running = true,
        secondsInPhase = 30,
        speed = 4.8f,
        pace = 115,
        fastDone = 1,
        fastTotal = 5,
        fastSec = 60,
        slowSec = 60,
        speedFloor = 3.2f,
        speedCeiling = 4.5f,
        paceFloor = 100,
        paceCeiling = 110,
    )

    @Test
    fun barsShowsPushRecoveryAndOnTargetCaption() {
        rule.setContent {
            MaterialTheme {
                WearWorkoutGraphicsPanel(session = session, view = WearGraphicsView.BARS, onViewChange = {})
            }
        }
        rule.onNodeWithText("PUSH").assertExists()
        rule.onNodeWithText("RECOVERY").assertExists()
        rule.onNodeWithText("On target", substring = true).assertExists()
    }

    @Test
    fun bandShowsSpeedAndBandCaption() {
        rule.setContent {
            MaterialTheme {
                WearWorkoutGraphicsPanel(session = session, view = WearGraphicsView.BAND, onViewChange = {})
            }
        }
        rule.onNodeWithText("speed 4.8", substring = true).assertExists()
        rule.onNodeWithText("pace 115", substring = true).assertExists()
        rule.onNodeWithText("On target", substring = true).assertExists()
    }

    @Test
    fun gaugeRendersCaption() {
        rule.setContent {
            MaterialTheme {
                WearWorkoutGraphicsPanel(session = session, view = WearGraphicsView.GAUGE, onViewChange = {})
            }
        }
        // The arc + center text are Canvas-drawn; the status caption is semantic.
        rule.onNodeWithText("On target", substring = true).assertExists()
    }

    @Test
    fun offHidesVisuals() {
        rule.setContent {
            MaterialTheme {
                WearWorkoutGraphicsPanel(session = session, view = WearGraphicsView.OFF, onViewChange = {})
            }
        }
        rule.onNodeWithText("PUSH").assertDoesNotExist()
        rule.onNodeWithText("RECOVERY").assertDoesNotExist()
        rule.onNodeWithText("On target", substring = true).assertDoesNotExist()
    }
}