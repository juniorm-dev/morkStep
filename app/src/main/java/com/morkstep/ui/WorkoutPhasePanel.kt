package com.morkstep.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutProfile
import com.morkstep.engine.LiveState
import kotlin.math.cos
import kotlin.math.sin

/** Selectable phase-tracker visuals on the workout screen. */
enum class WorkoutPhaseView(val label: String) {
    OFF("Off"),
    BARS("Bars"),
    BAND("Band"),
    GAUGE("Gauge"),
}

private val PUSH_COLOR = Color(0xFFD1402A)
private val RECOVERY_COLOR = Color(0xFF2E7AC4)
private val NEUTRAL_COLOR = Color(0xFF7B8A99)
private val OK_GREEN = Color(0xFF2E9E4F)

/**
 * Phase tracker for the running session: how the push/recovery segments are
 * progressing, plus how the live speed compares to the phase target (floor
 * during push, ceiling during recovery). Pure render over [LiveState].
 */
@Suppress("FunctionName")
@Composable
fun WorkoutPhasePanel(
    live: LiveState,
    profile: WorkoutProfile,
    view: WorkoutPhaseView,
    onViewChange: (WorkoutPhaseView) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkoutPhaseView.entries.forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { onViewChange(v) },
                    label = { Text(v.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (view) {
            WorkoutPhaseView.OFF -> Unit
            WorkoutPhaseView.BARS -> BarsView(live, profile)
            WorkoutPhaseView.BAND -> BandView(live, profile)
            WorkoutPhaseView.GAUGE -> GaugeView(live, profile)
        }
    }
}

// ---- shared helpers ----

private fun segmentLengthSec(phase: PhaseType, profile: WorkoutProfile): Int = when (phase) {
    PhaseType.FAST -> profile.fastSec
    PhaseType.SLOW -> profile.slowSec
    PhaseType.WARMUP -> profile.warmupSec
    PhaseType.COOLDOWN -> profile.cooldownSec
}

/** 0..1 how far through the current segment the session is. */
private fun segmentProgress(live: LiveState, profile: WorkoutProfile): Float {
    val len = segmentLengthSec(live.phase, profile)
    if (len <= 0) return 0f
    return (live.secondsInPhase.toFloat() / len).coerceIn(0f, 1f)
}

private enum class TargetStatus { NONE, ON_TARGET, OFF_TARGET }

/** Compare the live speed to the phase target (floor in PUSH, ceiling in RECOVERY). */
private fun targetStatus(live: LiveState, profile: WorkoutProfile): TargetStatus {
    val speed = live.speed ?: return TargetStatus.NONE
    return when (live.phase) {
        PhaseType.FAST -> if (speed >= profile.speedFloorMph.toFloat()) TargetStatus.ON_TARGET else TargetStatus.OFF_TARGET
        PhaseType.SLOW -> if (speed <= profile.speedCeilingMph.toFloat()) TargetStatus.ON_TARGET else TargetStatus.OFF_TARGET
        else -> TargetStatus.NONE
    }
}

private fun statusCaption(live: LiveState, profile: WorkoutProfile): String = when (targetStatus(live, profile)) {
    TargetStatus.NONE -> when (live.phase) {
        PhaseType.FAST -> "Push — target ${profile.speedFloorMph} mph floor"
        PhaseType.SLOW -> "Recovery — target ${profile.speedCeilingMph} mph ceiling"
        PhaseType.WARMUP -> "Warm-up — no speed target"
        PhaseType.COOLDOWN -> "Cooldown — no speed target"
    }
    TargetStatus.ON_TARGET -> when (live.phase) {
        PhaseType.FAST -> "On target — speed ≥ ${profile.speedFloorMph} mph floor"
        else -> "On target — speed ≤ ${profile.speedCeilingMph} mph ceiling"
    }
    TargetStatus.OFF_TARGET -> when (live.phase) {
        PhaseType.FAST -> "Speed up — speed < ${profile.speedFloorMph} mph floor"
        else -> "Slow down — speed > ${profile.speedCeilingMph} mph ceiling"
    }
}

private fun statusColor(status: TargetStatus, subdued: Color): Color = when (status) {
    TargetStatus.ON_TARGET -> OK_GREEN
    TargetStatus.OFF_TARGET -> PUSH_COLOR
    TargetStatus.NONE -> subdued
}

// ---- Bars: push / recovery segment progress ----

@Suppress("FunctionName")
@Composable
private fun BarsView(live: LiveState, profile: WorkoutProfile) {
    val pushFrac: Float = when (live.phase) {
        PhaseType.FAST -> segmentProgress(live, profile)
        PhaseType.SLOW, PhaseType.COOLDOWN -> 1f
        else -> 0f
    }
    val recFrac: Float = when (live.phase) {
        PhaseType.SLOW -> segmentProgress(live, profile)
        PhaseType.FAST -> 1f
        else -> 0f
    }
    val total = live.fastRoundsTotal
    val pushCount = if (total != null && total > 0) {
        if (live.phase == PhaseType.FAST) "PUSH ${live.fastSegmentsDone + 1}/$total"
        else "PUSH ${live.fastSegmentsDone}/$total"
    } else "PUSH"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BarRow(pushCount, pushFrac, PUSH_COLOR)
        BarRow("RECOVERY", recFrac, RECOVERY_COLOR)
        Text(
            statusCaption(live, profile),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(targetStatus(live, profile), MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun BarRow(label: String, frac: Float, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
    }
}

// ---- Band: speed vs floor/ceiling band ----

@Suppress("FunctionName")
@Composable
private fun BandView(live: LiveState, profile: WorkoutProfile) {
    val maxMph = maxOf(profile.speedCeilingMph, profile.speedFloorMph, (live.speed ?: 0f).toDouble()) * 1.2
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val captionColor = statusColor(targetStatus(live, profile), onSurfaceVariant)
    val targetColor = when (live.phase) {
        PhaseType.FAST -> PUSH_COLOR
        PhaseType.SLOW -> RECOVERY_COLOR
        else -> NEUTRAL_COLOR
    }
    val speed = live.speed

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val w = size.width
            val h = size.height
            val corner = 14.dp.toPx()
            val bandTop = 2.dp.toPx()
            val bandH = h - 4.dp.toPx()

            // Track.
            drawRoundRect(
                color = trackColor,
                cornerRadius = CornerRadius(corner),
            )
            // Band between the two targets (rendered min..max so an inverted
            // band — push floor above the recovery cap — still shows).
            val xLow = (minOf(profile.speedFloorMph, profile.speedCeilingMph) / maxMph).toFloat() * w
            val xHigh = (maxOf(profile.speedFloorMph, profile.speedCeilingMph) / maxMph).toFloat() * w
            drawRoundRect(
                color = targetColor.copy(alpha = 0.22f),
                topLeft = Offset(xLow.coerceIn(0f, w), bandTop),
                size = Size((xHigh - xLow).coerceAtLeast(0f), bandH),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            // Target boundary (push floor / recovery cap).
            val targetFrac = when (live.phase) {
                PhaseType.FAST -> (profile.speedFloorMph / maxMph).toFloat()
                PhaseType.SLOW -> (profile.speedCeilingMph / maxMph).toFloat()
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
            // Live speed needle.
            val needleFrac = if (speed != null) (speed / maxMph).toFloat().coerceIn(0f, 1f) else 0f
            val nx = (needleFrac * w).coerceIn(0f, w)
            drawLine(
                color = Color.White,
                start = Offset(nx, bandTop),
                end = Offset(nx, bandTop + bandH),
                strokeWidth = 3.dp.toPx(),
            )
        }
        Text(
            buildString {
                append("speed ${speed?.let { "%.1f".format(it) } ?: "--"} mph")
                append("   band ${profile.speedFloorMph}–${profile.speedCeilingMph}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
        Text(
            statusCaption(live, profile),
            style = MaterialTheme.typography.bodySmall,
            color = captionColor,
        )
    }
}

// ---- Gauge: circular segment progress + speed ----

@Suppress("FunctionName")
@Composable
private fun GaugeView(live: LiveState, profile: WorkoutProfile) {
    val phaseColor = when (live.phase) {
        PhaseType.FAST -> PUSH_COLOR
        PhaseType.SLOW -> RECOVERY_COLOR
        else -> NEUTRAL_COLOR
    }
    val progress = segmentProgress(live, profile)
    val speed = live.speed
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val valueColor = statusColor(targetStatus(live, profile), onSurfaceVariant)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(160.dp)) {
                val stroke = Stroke(width = 14.dp.toPx())
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                val topLeft = Offset(stroke.width / 2, stroke.width / 2)
                val startAngle = 150f
                val sweepTotal = 240f

                // Background track.
                drawArc(
                    color = outlineVariant.copy(alpha = 0.7f),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                // Segment progress.
                drawArc(
                    color = phaseColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                // Floor / ceiling tick marks on the arc.
                val scaleMax = maxOf(profile.speedCeilingMph, profile.speedFloorMph) * 1.2
                listOf(profile.speedFloorMph, profile.speedCeilingMph).forEach { mph ->
                    val frac = (mph / scaleMax).coerceIn(0.0, 1.0).toFloat()
                    val angle = Math.toRadians((startAngle + sweepTotal * frac).toDouble())
                    val r0 = size.width / 2 - 13.dp.toPx()
                    val r1 = size.width / 2 - 1.dp.toPx()
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

                // Center: speed + phase.
                val textPaint = Paint().apply {
                    isAntiAlias = true
                    color = valueColor.toArgb()
                    textAlign = Paint.Align.CENTER
                    textSize = 30.sp.toPx()
                }
                val cx = size.width / 2
                val cy = size.height / 2
                drawContext.canvas.nativeCanvas.drawText(
                    speed?.let { "%.1f".format(it) } ?: "--",
                    cx,
                    cy - 4.dp.toPx(),
                    textPaint,
                )
                val subPaint = Paint().apply {
                    isAntiAlias = true
                    color = onSurfaceVariant.toArgb()
                    textAlign = Paint.Align.CENTER
                    textSize = 12.sp.toPx()
                }
                drawContext.canvas.nativeCanvas.drawText(
                    when (live.phase) {
                        PhaseType.FAST -> "mph · PUSH"
                        PhaseType.SLOW -> "mph · RECOVERY"
                        PhaseType.WARMUP -> "mph · warm-up"
                        PhaseType.COOLDOWN -> "mph · cool-down"
                    },
                    cx,
                    cy + 22.dp.toPx(),
                    subPaint,
                )
            }
            Text(
                statusCaption(live, profile),
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
            )
        }
    }
}