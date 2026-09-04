package com.morkstep.sensing

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pedometer cadence (steps per minute) relayed from the paired morkStep Wear
 * companion app.
 *
 * The Wear app reads live step cadence on the watch (Wear Health Services,
 * `STEPS_PER_MINUTE`) and pushes each sample to this phone app over the
 * Wearable message layer ([WearPaceSource.PACE_PATH]). This source simply
 * surfaces the latest relayed value as a [StateFlow], so the interval engine
 * sees it just like any other pace source.
 *
 * If no Wear companion is connected, [pace] stays `null` — there is no
 * simulated fallback.
 */
class WearPaceSource(context: Context) : PaceSource {
    private val _pace = MutableStateFlow<Int?>(null)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    private val messageClient: MessageClient = Wearable.getMessageClient(context.applicationContext)

    private var registered = false

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        // The relayed payload is a 4-byte big-endian steps-per-minute sample.
        if (event.path == PACE_PATH && event.data.size >= java.lang.Integer.BYTES) {
            val spm = ByteBuffer.wrap(event.data).order(ByteOrder.BIG_ENDIAN).int
            if (spm > 0) _pace.value = spm
        }
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            messageClient.addListener(messageListener)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            messageClient.removeListener(messageListener)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Path the Wear companion pushes pace samples on. Must match the wear app. */
        const val PACE_PATH = "/morkstep/pace"
    }
}