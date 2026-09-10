package com.morkstep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morkstep.MorkApplication
import com.morkstep.data.PhaseAverages
import com.morkstep.data.WorkoutEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "$m min"
}

/** Date · duration · push count · distance — the card's one-line summary. */
private fun summaryLine(w: WorkoutEntity): String {
    val base = "${formatDuration(w.durationSec)} · ${w.pushSegments} push intervals"
    return if (w.distanceMiles > 0f) "$base · %.2f mi".format(w.distanceMiles) else base
}

/** Whole-session averages, one line per recorded metric (the collapsed card). */
private fun overallAverages(w: WorkoutEntity): List<String> = buildList {
    w.avgOverallSpeed?.let { add("speed mph:  overall %.1f".format(it)) }
    w.avgOverallPace?.let { add("pace spm:  overall $it") }
    w.avgOverallHr?.let { add("HR bpm:  overall $it") }
}

/** Pooled push/recovery averages — every round of a type taken together, the level above the per-phase rows. */
private fun pooledAverages(w: WorkoutEntity): String? {
    fun pair(push: String?, recovery: String?): String? =
        if (push == null && recovery == null) null else "${push ?: "–"} / ${recovery ?: "–"}"
    val parts = buildList {
        pair(w.avgPushSpeed?.let { "%.1f".format(it) }, w.avgRecoverySpeed?.let { "%.1f".format(it) })
            ?.let { add("speed $it mph") }
        pair(w.avgPushPace?.toString(), w.avgRecoveryPace?.toString())?.let { add("pace $it spm") }
        pair(w.avgPushHr?.toString(), w.avgRecoveryHr?.toString())?.let { add("HR $it bpm") }
    }
    return if (parts.isEmpty()) null else "pooled push / recovery:  " + parts.joinToString(" · ")
}

/** HR extremes and time above the push-min HR — the session's non-average stats. */
private fun extraStats(w: WorkoutEntity): String? {
    val parts = buildList {
        if (w.minHr != null && w.maxHr != null) add("min–max HR ${w.minHr}–${w.maxHr}")
        if (w.overPushMinSec > 0) add("above push min ${w.overPushMinSec}s")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Suppress("FunctionName")
@Composable
fun HistoryScreen(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MorkApplication
    val dao = app.container.workoutDao
    val workouts by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    // At most one card is open at a time, and the open one survives rotation.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.weight(1f),
                enabled = workouts.isNotEmpty(),
            ) {
                Text("Export history")
            }
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.weight(1f),
            ) {
                Text("Import history")
            }
        }
        if (workouts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No workouts yet", style = MaterialTheme.typography.titleMedium)
                Text("Finish a session and it will appear here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(workouts, key = { it.id }) { w ->
                    WorkoutRow(
                        w = w,
                        expanded = expandedId == w.id,
                        onToggle = { expandedId = if (expandedId == w.id) null else w.id },
                    )
                }
            }
        }
    }
}

/**
 * One history entry. Collapsed, it reads the session's headline numbers and its
 * overall averages; tapped open it adds the pooled push/recovery levels and the
 * per-phase breakdown — every warm-up, push, recovery and cool-down with its own
 * speed/pace/HR average — over the phase line chart.
 */
@Suppress("FunctionName")
@Composable
private fun WorkoutRow(w: WorkoutEntity, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(formatDate(w.startTime), style = MaterialTheme.typography.titleMedium)
            w.profileName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(summaryLine(w), style = MaterialTheme.typography.bodyMedium)
            overallAverages(w).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) "Hide phase breakdown" else "Show phase breakdown",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
            )
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                pooledAverages(w)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(2.dp))
                }
                extraStats(w)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(10.dp))
                if (w.phaseAverages.isEmpty()) {
                    Text(
                        "No per-phase samples recorded for this workout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    PhaseBreakdown(w.phaseAverages)
                    Spacer(Modifier.height(12.dp))
                    PhaseLineChart(w.phaseAverages)
                }
            }
        }
    }
}

/** One row per phase occurrence: its own speed / pace / HR average, "—" for a metric it never recorded. */
@Suppress("FunctionName")
@Composable
private fun PhaseBreakdown(phases: List<PhaseAverages>) {
    val labels = phaseOccurrenceLabels(phases, short = false)
    val headers = listOf("Phase", "mph", "spm", "bpm")
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            headers.forEachIndexed { i, h ->
                Text(
                    h,
                    modifier = Modifier.weight(if (i == 0) 1.6f else 1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        phases.forEachIndexed { i, p ->
            val cells = listOf(
                labels[i],
                p.avgSpeedMph?.let { "%.1f".format(it) } ?: "—",
                p.avgPaceSpm?.toString() ?: "—",
                p.avgHrBpm?.toString() ?: "—",
            )
            Row(Modifier.fillMaxWidth()) {
                cells.forEachIndexed { c, cell ->
                    Text(
                        cell,
                        modifier = Modifier.weight(if (c == 0) 1.6f else 1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
