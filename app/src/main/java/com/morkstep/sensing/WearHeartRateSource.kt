package com.morkstep.sensing

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
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
class WearHeartRateSource(context: Context) : HeartRateSource {
    private val _hr = MutableStateFlow<Int?>(null)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val messageClient: MessageClient = Wearable.getMessageClient(context.applicationContext)

    private var registered = false

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == HR_PATH) {
            event.data.takeIf { it.isNotEmpty() }?.let { _hr.value = it[0].toInt() and 0xff }
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
        /** Path the Wear companion pushes HR values on. Must match the wear app. */
        const val HR_PATH = "/morkstep/hr"
    }
}
