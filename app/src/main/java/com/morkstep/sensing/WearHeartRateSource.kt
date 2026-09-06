package com.morkstep.sensing

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.morkstep.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Heart rate relayed from the paired morkStep Wear companion app.
 *
 * The Wear app reads live HR on the watch (Wear Health Services) and pushes
 * each sample to this phone app over the Wearable message layer
 * ([WearHeartRateSource.HR_PATH]). This source simply surfaces the latest
 * relayed value as a [StateFlow], so the interval engine sees it just like
 * any other HR source.
 *
 * If no Wear companion is connected, [hr] stays `null` — there is no
 * simulated fallback.
 */
class WearHeartRateSource(
    context: Context,
    /** App-wide debug log; null disables logging. */
    private val log: DebugLog? = null,
) : HeartRateSource {
    private val _hr = MutableStateFlow<Int?>(null)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val messageClient: MessageClient = Wearable.getMessageClient(context.applicationContext)

    private var registered = false
    /** Last HR logged to the trace, so unchanged samples don't spam the screen. */
    private var lastLogHr = -1

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == HR_PATH) {
            val bpm = event.data.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xff }
            if (bpm != null) {
                _hr.value = bpm
                if (bpm != lastLogHr) {
                    lastLogHr = bpm
                    log?.log("[hr-wear] msg ${event.data.size}B -> $bpm bpm")
                }
            } else {
                log?.log("[hr-wear] msg empty")
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            messageClient.addListener(messageListener)
            log?.log("[hr-wear] listening on ${HR_PATH}")
        } catch (_: Exception) {
            log?.log("[hr-wear] addListener failed")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            messageClient.removeListener(messageListener)
        } catch (_: Exception) {
        }
        log?.log("[hr-wear] relay stopped")
    }

    companion object {
        /** Path the Wear companion pushes HR values on. Must match the wear app. */
        const val HR_PATH = "/morkstep/hr"
    }
}
