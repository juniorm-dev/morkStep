package com.morkstep.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.Availability
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Live heart-rate relay: reads HR on the watch (Wear Health Services) and pushes
 *  each sample to the paired phone over the Wearable message layer. */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var relay: HrRelay? = null
    private var vibrateRelay: VibrateRelay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var granted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            var hr by remember { mutableStateOf<Int?>(null) }
            var status by remember { mutableStateOf<String?>(null) }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { result -> granted = result }

            DisposableEffect(Unit) {
                val relay = HrRelay(context, scope) { value, s -> hr = value; s?.let { status = it } }
                this@MainActivity.relay = relay
                if (granted) relay.start() else launcher.launch(Manifest.permission.BODY_SENSORS)
                val vibrateRelay = VibrateRelay(context)
                this@MainActivity.vibrateRelay = vibrateRelay
                vibrateRelay.start()
                onDispose {
                    relay.stop()
                    vibrateRelay.stop()
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
                    Text(
                        "HR: ${hr?.toString() ?: "--"} bpm",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (hr == null) Color.Gray else MaterialTheme.colorScheme.primary,
                    )
                    if (status != null) {
                        Text(status!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        relay?.stop()
        vibrateRelay?.stop()
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

        private suspend fun sendBpm(bpm: Int) {
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
    private class VibrateRelay(private val context: android.content.Context) {
        private val messageClient: MessageClient = Wearable.getMessageClient(context)
        private var registered = false

        private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == VIBRATE_PATH) vibrate()
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

        private fun vibrate() {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        companion object {
            /** Path the phone relays gated cue vibrations on. Must match the phone app. */
            const val VIBRATE_PATH = "/morkstep/vibrate"
        }
    }
}


/** Await a Google Play Services [com.google.android.gms.tasks.Task] as a suspend result. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    com.google.android.gms.tasks.Tasks.await(this)
