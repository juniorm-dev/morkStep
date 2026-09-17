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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * The collapsed card's averages: one line per metric, each naming the pooled
 * value per phase type beside the whole-session one, `push 140 · rec 118 ·
 * overall 128` — push and recovery are what the plan targets, so a card showing
 * the overall alone would hide the numbers the session was run against. A value
 * the session (or its Health Connect backfill) never recorded is left out, and a
 * metric with no value at all draws no line.
 */
internal fun overallAverages(w: WorkoutEntity): List<String> = buildList {
    metricLine(
        label = "speed mph",
        push = w.avgPushSpeed?.let { "%.1f".format(it) },
        recovery = w.avgRecoverySpeed?.let { "%.1f".format(it) },
        overall = w.avgOverallSpeed?.let { "%.1f".format(it) },
    )?.let(::add)
    metricLine(
        label = "pace spm",
        push = w.avgPushPace?.toString(),
        recovery = w.avgRecoveryPace?.toString(),
        overall = w.avgOverallPace?.toString(),
    )?.let(::add)
    hrLine(w)?.let(::add)
}

/**
 * The collapsed card's HR line: the pooled push/recovery averages beside the
 * whole-session one, else the min–max pair when that is all Health Connect held.
 * A backfilled row can hold only part of what the read asked for — the aggregate
 * and the per-minute buckets are separate reads, and a provider that rejects the
 * statistical pair is re-read for the average alone — and a row that recorded
 * heart rate at all has to say so without the card being opened.
 */
internal fun hrLine(w: WorkoutEntity): String? =
    metricLine(
        label = "HR bpm",
        push = w.avgPushHr?.toString(),
        recovery = w.avgRecoveryHr?.toString(),
        overall = w.avgOverallHr?.toString(),
    ) ?: if (w.minHr != null && w.maxHr != null) "HR bpm:  min–max ${w.minHr}–${w.maxHr}" else null

/** One card line: `label:  push 140 · rec 118 · overall 128`, or null when the workout holds no value for it. */
private fun metricLine(label: String, push: String?, recovery: String?, overall: String?): String? {
    val parts = listOfNotNull(
        push?.let { "push $it" },
        recovery?.let { "rec $it" },
        overall?.let { "overall $it" },
    )
    return if (parts.isEmpty()) null else "$label:  " + parts.joinToString(" · ")
}

/** HR extremes and time above the push-min HR — the session's non-average stats. */
private fun extraStats(w: WorkoutEntity): String? {
    val parts = buildList {
        if (w.minHr != null && w.maxHr != null) add("min–max HR ${w.minHr}–${w.maxHr}")
        if (w.overPushMinSec > 0) add("above push min ${w.overPushMinSec}s")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Workout history: every finished session as one card. Opening a card reveals
 * the session's HR extremes, its time above the push min and the per-phase
 * breakdown — and [onWorkoutOpened] is where MainViewModel re-reads Health
 * Connect for that row, so a workout whose HR only reached Health Connect after
 * the automatic passes (finish-line read, retry chain, History sweep) still
 * fills in on demand.
 *
 * Two ad shapes, both decided by the hidden Debug switches: the inline ad card
 * above the list ([adsEnabled]), or — under the pinned placement — a full-page
 * native ad over the whole screen on the accesses [fullPageAd] names, closed by
 * its own button or the system back gesture ([onFullPageAdDismiss]).
 */
@Suppress("FunctionName")
@Composable
fun HistoryScreen(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onWorkoutOpened: (WorkoutEntity) -> Unit,
    /** Whether the inline ad card is drawn (hidden Debug switch); off draws nothing and requests nothing. */
    adsEnabled: Boolean = false,
    /** Whether this access opens the full-page native ad instead of the inline card. */
    fullPageAd: Boolean = false,
    /** The user closed the full-page ad. */
    onFullPageAdDismiss: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MorkApplication
    val dao = app.container.workoutDao
    val workouts by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    // At most one card is open at a time, and the open one survives rotation.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (fullPageAd) {
        // Its own window, so the ad covers the bottom bar too and back closes it.
        Dialog(
            onDismissRequest = onFullPageAdDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) {
            FullPageNativeAdSlot(enabled = true, onDismiss = onFullPageAdDismiss)
        }
    }

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

        // One ad card, above the list and never over a row.
        NativeAdSlot(enabled = adsEnabled, modifier = Modifier.padding(bottom = 12.dp))

        if (workouts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No workouts yet", style = MaterialTheme.typography.titleMedium)
                Text("Finish a session and it will appear here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // The list takes what the ad above it leaves, rather than the whole parent: with a
            // full-size ad card in the column, `fillMaxSize` laid the list out past the bottom
            // edge, where no row could be seen or reached.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(workouts, key = { it.id }) { w ->
                    WorkoutRow(
                        w = w,
                        expanded = expandedId == w.id,
                        onToggle = {
                            val opening = expandedId != w.id
                            expandedId = if (opening) w.id else null
                            if (opening) onWorkoutOpened(w)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One history entry. Collapsed, it reads the session's headline numbers and its
 * averages — the pooled push/recovery values beside the overall, per metric.
 * Tapped open it adds the HR extremes and the time above the push min, then the
 * per-phase breakdown: every warm-up, push, recovery and cool-down with its own
 * speed/pace/HR average, over the phase line chart.
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
            // Profile and length on one line — what the session ran under, and the
            // length it was run to ("Default · 5 rounds").
            listOfNotNull(w.profileName, w.lengthLabel).joinToString(" · ")
                .takeIf { it.isNotEmpty() }
                ?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.secondary) }
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
