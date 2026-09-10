package com.morkstep.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morkstep.data.PhaseAverages
import com.morkstep.data.PhaseType
import kotlin.math.ceil

/** One plotted metric: legend wording, color and its value per phase (null = no sample). */
private data class PhaseSeries(
    val label: String,
    val unit: String,
    val color: Color,
    val values: List<Float?>,
    val format: (Float) -> String,
)

/**
 * One label per phase occurrence in [phases], numbered per phase type:
 * "Push 2"/"Recovery 1" for the card's breakdown rows, "P2"/"R1" for the
 * chart's x-axis.
 */
internal fun phaseOccurrenceLabels(phases: List<PhaseAverages>, short: Boolean): List<String> {
    var push = 0
    var recovery = 0
    return phases.map { p ->
        when (p.phase) {
            PhaseType.WARMUP -> if (short) "W" else "Warm-up"
            PhaseType.FAST -> if (short) "P${++push}" else "Push ${++push}"
            PhaseType.SLOW -> if (short) "R${++recovery}" else "Recovery ${++recovery}"
            PhaseType.COOLDOWN -> if (short) "C" else "Cool-down"
        }
    }
}

/**
 * Line chart of a workout's per-phase averages: one line each for speed, pace
 * and heart rate, in phase order (warm-up → push/recovery pairs → cool-down).
 *
 * The three metrics share no unit, so each line is scaled to its own min–max
 * and the legend prints every series' range. A metric with no sample in any
 * phase is left out entirely — no line is drawn for data the workout never
 * recorded — and a phase without a sample is a gap in the line rather than a
 * plotted zero.
 */
@Suppress("FunctionName")
@Composable
fun PhaseLineChart(phases: List<PhaseAverages>) {
    val colors = MaterialTheme.colorScheme
    val series = listOf(
        PhaseSeries("speed", "mph", colors.primary, phases.map { it.avgSpeedMph }, format = { "%.1f".format(it) }),
        PhaseSeries("pace", "spm", colors.tertiary, phases.map { it.avgPaceSpm?.toFloat() }, format = { "%.0f".format(it) }),
        PhaseSeries("HR", "bpm", colors.error, phases.map { it.avgHrBpm?.toFloat() }, format = { "%.0f".format(it) }),
    ).filter { s -> s.values.any { it != null } }
    if (series.isEmpty()) return
    val labels = phaseOccurrenceLabels(phases, short = true)
    val axisColor = colors.outlineVariant
    val mutedColor = colors.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Per-phase chart",
            style = MaterialTheme.typography.labelMedium,
            color = colors.secondary,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = "Phase chart" },
        ) {
            val h = size.height
            val padX = 8.dp.toPx()
            val padTop = 8.dp.toPx()
            val labelBand = 16.dp.toPx()
            val plotBottom = h - labelBand
            val plotW = (size.width - 2 * padX).coerceAtLeast(1f)
            val plotH = (plotBottom - padTop).coerceAtLeast(1f)
            val n = phases.size
            fun xAt(i: Int): Float = if (n <= 1) padX + plotW / 2f else padX + plotW * i / (n - 1)

            // One gridline per phase occurrence, so each point reads against the
            // phase it belongs to.
            for (i in 0 until n) {
                drawLine(
                    color = axisColor.copy(alpha = 0.6f),
                    start = Offset(xAt(i), padTop),
                    end = Offset(xAt(i), plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawLine(
                color = axisColor,
                start = Offset(padX, plotBottom),
                end = Offset(padX + plotW, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            series.forEach { s ->
                val present = s.values.mapIndexedNotNull { i, v -> v?.let { i to it } }
                val min = present.minOf { it.second }
                val max = present.maxOf { it.second }
                fun yAt(v: Float): Float =
                    if (max - min <= 0f) padTop + plotH / 2f
                    else plotBottom - plotH * (v - min) / (max - min)
                present.zipWithNext { a, b ->
                    drawLine(
                        color = s.color,
                        start = Offset(xAt(a.first), yAt(a.second)),
                        end = Offset(xAt(b.first), yAt(b.second)),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                present.forEach { (i, v) ->
                    drawCircle(s.color, radius = 2.5.dp.toPx(), center = Offset(xAt(i), yAt(v)))
                }
            }

            // Phase tags under the plot, thinned so a many-round plan stays legible.
            val stride = if (n <= 8) 1 else ceil(n / 8.0).toInt()
            val paint = Paint().apply {
                isAntiAlias = true
                color = mutedColor.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 10.sp.toPx()
            }
            for (i in 0 until n step stride) {
                drawContext.canvas.nativeCanvas.drawText(labels[i], xAt(i), h - 4.dp.toPx(), paint)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            series.forEach { s ->
                val present = s.values.filterNotNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(s.color, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${s.label} ${s.format(present.min())}–${s.format(present.max())} ${s.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor,
                    )
                }
            }
        }
        Text(
            "Each line is scaled to its own range.",
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor,
        )
    }
}
