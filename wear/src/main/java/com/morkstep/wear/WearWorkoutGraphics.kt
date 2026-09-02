package com.morkstep.wear

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** Graphics options for the running session, mirroring the phone's phase tracker. */
enum class WearGraphicsView(val label: String) {
    OFF("Off"),
    BARS("Bars"),
    BAND("Band"),
    GAUGE("Gauge"),
}

/**
 * Session state for the watch graphics, decoded from the phone's `/morkstep/state`
 * relay (35-byte payload; see MainViewModel.sendWatchState in the app module).
 */
data class WearSessionState(
    val phaseOrd: Int = 0,
    val paused: Boolean = false,
    val running: Boolean = false,
    val secondsInPhase: Int = 0,
    val pace: Float? = null,
    val fastDone: Int = 0,
    val fastTotal: Int? = null,
    val fastSec: Int = Constants.DEFAULT_FAST_SEC,
    val slowSec: Int = Constants.DEFAULT_SLOW_SEC,
    val paceFloor: Float = Constants.DEFAULT_PACE_FLOOR_MPH,
    val paceCeiling: Float = Constants.DEFAULT_PACE_CEILING_MPH,
)

/** Decode the phone's state payload; a short/empty payload yields defaults. */
fun decodeWearSessionState(data: ByteArray): WearSessionState {
    if (data.size < Constants.STATE_PAYLOAD_BYTES) return WearSessionState()
    return runCatching {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        WearSessionState(
            phaseOrd = buf.get().toInt(),
            paused = buf.get().toInt() == 1,
            running = buf.get().toInt() == 1,
            secondsInPhase = buf.int,
            pace = buf.float.takeIf { !it.isNaN() },
            fastDone = buf.int,
            fastTotal = buf.int.takeIf { it >= 0 },
            fastSec = buf.int,
            slowSec = buf.int,
            paceFloor = buf.float,
            paceCeiling = buf.float,
        )
    }.getOrDefault(WearSessionState())
}

private enum class TargetStatus { NONE, ON_TARGET, OFF_TARGET }

private fun WearSessionState.targetStatus(): TargetStatus {
    val p = pace ?: return TargetStatus.NONE
    return when (WearPhase.from(phaseOrd)) {
        WearPhase.FAST -> if (p >= paceFloor) TargetStatus.ON_TARGET else TargetStatus.OFF_TARGET
        WearPhase.SLOW -> if (p <= paceCeiling) TargetStatus.ON_TARGET else TargetStatus.OFF_TARGET
        else -> TargetStatus.NONE
    }
}

private fun WearSessionState.statusCaption(): String = when (targetStatus()) {
    TargetStatus.NONE -> when (WearPhase.from(phaseOrd)) {
        WearPhase.FAST -> "Push — target $paceFloor mph floor"
        WearPhase.SLOW -> "Recovery — target $paceCeiling mph ceiling"
        WearPhase.WARMUP -> "Warm-up — no pace target"
        WearPhase.COOLDOWN -> "Cooldown — no pace target"
        null -> ""
    }
    TargetStatus.ON_TARGET -> when (WearPhase.from(phaseOrd)) {
        WearPhase.FAST -> "On target — pace ≥ $paceFloor mph floor"
        else -> "On target — pace ≤ $paceCeiling mph ceiling"
    }
    TargetStatus.OFF_TARGET -> when (WearPhase.from(phaseOrd)) {
        WearPhase.FAST -> "Speed up — pace < $paceFloor mph floor"
        else -> "Slow down — pace > $paceCeiling mph ceiling"
    }
}

private fun statusColor(status: TargetStatus, subdued: Color): Color = when (status) {
    TargetStatus.ON_TARGET -> Constants.OK_COLOR
    TargetStatus.OFF_TARGET -> Constants.PHASE_FAST_COLOR
    TargetStatus.NONE -> subdued
}

private fun segFrac(secondsIn: Int, segLength: Int): Float =
    if (segLength <= 0) 0f else (secondsIn.toFloat() / segLength).coerceIn(0f, 1f)

/** The phase-tracker panel: view selector chips + the selected visual. */
@Suppress("FunctionName")
@Composable
fun WearWorkoutGraphicsPanel(
    session: WearSessionState,
    view: WearGraphicsView,
    onViewChange: (WearGraphicsView) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WearGraphicsView.entries.forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { onViewChange(v) },
                    label = { Text(v.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (view) {
            WearGraphicsView.OFF -> Unit
            WearGraphicsView.BARS -> WearBars(session)
            WearGraphicsView.BAND -> WearBand(session)
            WearGraphicsView.GAUGE -> WearGauge(session)
        }
    }
}

// ---- Bars ----

@Suppress("FunctionName")
@Composable
private fun WearBars(s: WearSessionState) {
    val pushFrac = when (s.phaseOrd) {
        2 -> segFrac(s.secondsInPhase, s.fastSec)
        3 -> 1f
        else -> 0f
    }
    val recFrac = when (s.phaseOrd) {
        3 -> segFrac(s.secondsInPhase, s.slowSec)
        2 -> 1f
        else -> 0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        WearBarRow("PUSH", pushFrac, Constants.PHASE_FAST_COLOR)
        WearBarRow("RECOVERY", recFrac, Constants.PHASE_SLOW_COLOR)
        Text(
            s.statusCaption(),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(s.targetStatus(), MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun WearBarRow(label: String, frac: Float, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
    }
}

// ---- Band ----

@Suppress("FunctionName")
@Composable
private fun WearBand(s: WearSessionState) {
    val maxMph = maxOf(s.paceCeiling, s.paceFloor, s.pace ?: 0f) * Constants.SCALE_HEADROOM
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val captionColor = statusColor(s.targetStatus(), onSurfaceVariant)
    val targetColor = when (WearPhase.from(s.phaseOrd)) {
        WearPhase.FAST -> Constants.PHASE_FAST_COLOR
        WearPhase.SLOW -> Constants.PHASE_SLOW_COLOR
        else -> Constants.PHASE_COOLDOWN_COLOR
    }

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp),
        ) {
            val w = size.width
            val h = size.height
            val bandTop = 2.dp.toPx()
            val bandH = h - 4.dp.toPx()
            drawRoundRect(
                color = surfaceVariant,
                cornerRadius = CornerRadius(12.dp.toPx()),
            )
            val xLow = minOf(s.paceFloor, s.paceCeiling) / maxMph * w
            val xHigh = maxOf(s.paceFloor, s.paceCeiling) / maxMph * w
            drawRoundRect(
                color = targetColor.copy(alpha = 0.22f),
                topLeft = Offset(xLow.coerceIn(0f, w), bandTop),
                size = Size((xHigh - xLow).coerceAtLeast(0f), bandH),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            val targetFrac = when (WearPhase.from(s.phaseOrd)) {
                WearPhase.FAST -> s.paceFloor / maxMph
                WearPhase.SLOW -> s.paceCeiling / maxMph
                else -> null
            }
            targetFrac?.let { fx ->
                val x = (fx * w).coerceIn(0f, w)
                drawLine(
                    color = targetColor,
                    start = Offset(x, bandTop + 1.dp.toPx()),
                    end = Offset(x, bandTop + bandH - 1.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            val nx = ((s.pace ?: 0f) / maxMph * w).coerceIn(0f, w)
            drawLine(
                color = Color.White,
                start = Offset(nx, bandTop),
                end = Offset(nx, bandTop + bandH),
                strokeWidth = 3.dp.toPx(),
            )
        }
        Text(
            "pace ${s.pace?.let { "%.1f".format(it) } ?: "--"} mph · band ${s.paceFloor}–${s.paceCeiling}",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
        Text(
            s.statusCaption(),
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
        )
    }
}

// ---- Gauge ----

@Suppress("FunctionName")
@Composable
private fun WearGauge(s: WearSessionState) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val valueColor = statusColor(s.targetStatus(), onSurfaceVariant)
    val phaseColor = when (WearPhase.from(s.phaseOrd)) {
        WearPhase.FAST -> Constants.PHASE_FAST_COLOR
        WearPhase.SLOW -> Constants.PHASE_SLOW_COLOR
        else -> Constants.PHASE_COOLDOWN_COLOR
    }
    val progress = when (s.phaseOrd) {
        2 -> segFrac(s.secondsInPhase, s.fastSec)
        3 -> segFrac(s.secondsInPhase, s.slowSec)
        else -> 0f
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(110.dp)) {
            val stroke = Stroke(width = 10.dp.toPx())
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = Offset(stroke.width / 2, stroke.width / 2)
            val startAngle = Constants.GAUGE_START_ANGLE_DEG
            val sweepTotal = Constants.GAUGE_SWEEP_DEG

            drawArc(
                color = outlineVariant.copy(alpha = 0.6f),
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = phaseColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            val scaleMax = maxOf(s.paceCeiling, s.paceFloor) * Constants.SCALE_HEADROOM
            listOf(s.paceFloor, s.paceCeiling).forEach { mph ->
                val frac = (mph / scaleMax).coerceIn(0f, 1f)
                val angle = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                val r0 = size.width / 2 - 9.dp.toPx()
                val r1 = size.width / 2 + 1.dp.toPx()
                val cx = size.width / 2
                val cy = size.height / 2
                drawLine(
                    color = onSurfaceVariant.copy(alpha = 0.6f),
                    start = Offset(
                        (cx + r0 * cos(angle)).toFloat(),
                        (cy + r0 * sin(angle)).toFloat(),
                    ),
                    end = Offset(
                        (cx + r1 * cos(angle)).toFloat(),
                        (cy + r1 * sin(angle)).toFloat(),
                    ),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            val cx = size.width / 2
            val cy = size.height / 2
            val valuePaint = Paint().apply {
                isAntiAlias = true
                color = valueColor.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 24.sp.toPx()
            }
            drawContext.canvas.nativeCanvas.drawText(
                s.pace?.let { "%.1f".format(it) } ?: "--",
                cx,
                cy - 2.dp.toPx(),
                valuePaint,
            )
            val subPaint = Paint().apply {
                isAntiAlias = true
                color = onSurfaceVariant.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 10.sp.toPx()
            }
            drawContext.canvas.nativeCanvas.drawText(
                when (WearPhase.from(s.phaseOrd)) {
                    WearPhase.FAST -> "mph · PUSH"
                    WearPhase.SLOW -> "mph · RECOVERY"
                    WearPhase.WARMUP -> "mph · WARM-UP"
                    WearPhase.COOLDOWN -> "mph · COOLDOWN"
                    null -> "mph"
                },
                cx,
                cy + 16.dp.toPx(),
                subPaint,
            )
        }
        Text(
            s.statusCaption(),
            style = MaterialTheme.typography.labelSmall,
            color = valueColor,
        )
    }
}