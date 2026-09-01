package com.morkstep.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Workout phase as relayed from the phone during an active session. */
enum class WearPhase(val label: String) {
    WARMUP("Warm-up"),
    FAST("Push"),
    SLOW("Recovery"),
    COOLDOWN("Cooldown");

    companion object {
        fun from(ordinal: Int): WearPhase? = entries.getOrNull(ordinal - 1)
    }
}

private fun wearPhaseColor(phase: WearPhase): Color = when (phase) {
    WearPhase.WARMUP -> Color(0xFF58A05C)
    WearPhase.FAST -> Color(0xFFD1402A)
    WearPhase.SLOW -> Color(0xFF2E7AC4)
    WearPhase.COOLDOWN -> Color(0xFF7B8A99)
}

/** Live heart-rate relay: reads HR on the watch (Wear Health Services) and pushes
 *  each sample to the paired phone over the Wearable message layer. */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var relay: HrRelay? = null
    private var vibrateRelay: VibrateRelay? = null
    private var stateRelay: StateRelay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val appVersion = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            var granted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            var hr by remember { mutableStateOf<Int?>(null) }
            var status by remember { mutableStateOf<String?>(null) }
            // Session state relayed from the paired phone while a workout runs.
            var phase by remember { mutableStateOf<WearPhase?>(null) }
            var paused by remember { mutableStateOf(false) }
            var workoutActive by remember { mutableStateOf(false) }
            // Watch-local haptics mute: the phone keeps sending its vibrations,
            // this toggle just stops the watch from acting on them.
            var suppressVibrations by rememberSaveable { mutableStateOf(false) }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { result -> granted = result }

            DisposableEffect(Unit) {
                val relay = HrRelay(context, scope) { value, s -> hr = value; s?.let { status = it } }
                this@MainActivity.relay = relay
                if (granted) relay.start() else launcher.launch(Manifest.permission.BODY_SENSORS)
                val vibrateRelay = VibrateRelay(context) { suppressVibrations }
                this@MainActivity.vibrateRelay = vibrateRelay
                vibrateRelay.start()
                val stateRelay = StateRelay(context) { ordinal, p, running ->
                    phase = WearPhase.from(ordinal)
                    paused = p
                    workoutActive = running
                }
                this@MainActivity.stateRelay = stateRelay
                stateRelay.start()
                onDispose {
                    relay.stop()
                    vibrateRelay.stop()
                    stateRelay.stop()
                }
            }

            val sendPause = { pause: Boolean ->
                val payload = byteArrayOf((if (pause) 1 else 0).toByte())
                val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrNull()
                if (nodes != null) {
                    val messageClient = Wearable.getMessageClient(context)
                    nodes.forEach { node ->
                        runCatching { messageClient.sendMessage(node.id, PauseSender.PAUSE_PATH, payload).await() }
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("morkStep Wear", style = MaterialTheme.typography.titleMedium)
                    Text("v$appVersion", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "HR: ${hr?.toString() ?: "--"} bpm",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (hr == null) Color.Gray else MaterialTheme.colorScheme.primary,
                    )
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))

                    if (workoutActive) {
                        // Phase indicator: what the workout is doing right now.
                        Surface(
                            shape = CircleShape,
                            color = if (paused) Color(0xFF9E9E9E) else phase?.let { wearPhaseColor(it) } ?: Color(0xFF9E9E9E),
                        ) {
                            Text(
                                if (paused) "PAUSED" else phase?.label ?: "…",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { sendPause(!paused) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(if (paused) "Resume" else "Pause")
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vibrate", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Switch(
                            checked = !suppressVibrations,
                            onCheckedChange = { suppressVibrations = !it },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        relay?.stop()
        vibrateRelay?.stop()
        stateRelay?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private class HrRelay(
        private val context: android.content.Context,
        private val scope: CoroutineScope,
        private val onHr: (Int?, String?) -> Unit,
    ) {
        private val measureClient by lazy { HealthServices.getClient(context).measureClient }
        private var registered = false

        private val callback = object : MeasureCallback {
            override fun onRegistered() {
                registered = true
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                onHr(null, "Heart rate service unavailable on this device")
            }

            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability,
            ) = Unit

            override fun onDataReceived(data: DataPointContainer) {
                val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                    ?.toInt()
                if (bpm != null) {
                    onHr(bpm, "Streaming to connected phone…")
                    scope.launch { sendBpm(bpm) }
                }
            }
        }

        fun start() {
            if (registered) return
            try {
                measureClient.registerMeasureCallback(
                    DataType.HEART_RATE_BPM,
                    androidx.core.content.ContextCompat.getMainExecutor(context),
                    callback,
                )
            } catch (_: Exception) {
                onHr(null, "Failed to start heart-rate measurement")
            }
        }

        fun stop() {
            if (!registered) return
            registered = false
            try {
                measureClient.unregisterMeasureCallbackAsync(
                    DataType.HEART_RATE_BPM,
                    callback,
                )
            } catch (_: Exception) {
            }
        }

        private fun sendBpm(bpm: Int) {
            val nodes: List<Node> = Wearable.getNodeClient(context).connectedNodes.await()
            val messageClient: MessageClient = Wearable.getMessageClient(context)
            val payload = byteArrayOf(bpm.toByte())
            nodes.forEach { node ->
                runCatching { messageClient.sendMessage(node.id, HR_PATH, payload).await() }
            }
        }

        companion object {
            const val HR_PATH = "/morkstep/hr"
        }
    }

    /** Buzzes when the phone relays a cue (path "/morkstep/vibrate"). */
    private class VibrateRelay(
        private val context: android.content.Context,
        private val suppressed: () -> Boolean,
    ) {
        private val messageClient: MessageClient = Wearable.getMessageClient(context)
        private var registered = false

        private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == VIBRATE_PATH && !suppressed()) vibrate(event.data)
        }

        fun start() {
            if (registered) return
            registered = true
            try {
                messageClient.addListener(listener)
            } catch (_: Exception) {
            }
        }

        fun stop() {
            if (!registered) return
            registered = false
            try {
                messageClient.removeListener(listener)
            } catch (_: Exception) {
            }
        }

        private fun vibrate(data: ByteArray) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            }
            // payload: [kind, intensity 0..255] (intensity 0 = watch default
            // strength, used when the phone sent the legacy 1-byte payload).
            val intensity = eventIntensity(data)
            val amplitude = if (intensity in 1..255) intensity else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(300L, amplitude))
        }

        private fun eventIntensity(data: ByteArray): Int =
            data.getOrNull(1)?.toInt()?.and(0xFF) ?: 0

        companion object {
            /** Path the phone relays gated cue vibrations on. Must match the phone app. */
            const val VIBRATE_PATH = "/morkstep/vibrate"
        }
    }

    /** Receives the phone's workout session state (path "/morkstep/state"). */
    private class StateRelay(
        private val context: android.content.Context,
        private val onState: (phaseOrdinal: Int, paused: Boolean, running: Boolean) -> Unit,
    ) {
        private val messageClient: MessageClient = Wearable.getMessageClient(context)
        private var registered = false

        private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == STATE_PATH && event.data.size >= 3) {
                onState(
                    event.data[0].toInt(),
                    event.data[1].toInt() == 1,
                    event.data[2].toInt() == 1,
                )
            }
        }

        fun start() {
            if (registered) return
            registered = true
            try {
                messageClient.addListener(listener)
            } catch (_: Exception) {
            }
        }

        fun stop() {
            if (!registered) return
            registered = false
            try {
                messageClient.removeListener(listener)
            } catch (_: Exception) {
            }
        }

        companion object {
            /** Path the phone streams its live session state on. Must match the phone app. */
            const val STATE_PATH = "/morkstep/state"
        }
    }

    /** Sends pause/resume commands back to the phone (path "/morkstep/pause"). */
    private object PauseSender {
        /** Path the phone listens on for pause/resume commands. Must match the phone app. */
        const val PAUSE_PATH = "/morkstep/pause"
    }
}

/** Await a Google Play Services [com.google.android.gms.tasks.Task]. */
private fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    com.google.android.gms.tasks.Tasks.await(this)