package com.morkstep.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morkstep.MorkApplication
import com.morkstep.data.WorkoutEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}

@Composable
fun HistoryScreen() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MorkApplication
    val dao = app.container.workoutDao
    val workouts by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

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
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(workouts, key = { it.id }) { w ->
            WorkoutRow(w)
        }
    }
}

@Composable
private fun WorkoutRow(w: WorkoutEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(formatDate(w.startTime), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDuration(w.durationSec)} · ${w.fastSegments} push intervals",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val stats = buildList {
                    w.avgFastPace?.let { add("avg pace %.1f km/h".format(it)) }
                    w.avgHeartRate?.let { add("avg HR $it bpm") }
                    if (w.overCeilingSec > 0) add("above ceiling ${w.overCeilingSec}s")
                }
                if (stats.isNotEmpty()) {
                    Text(stats.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}