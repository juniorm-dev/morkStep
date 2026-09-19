package com.morkstep.ads

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.morkstep.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's single ad gate.
 *
 * Nothing is requested, loaded or shown unless [setEnabled] has been called with `true`,
 * which is driven by the hidden **Test ads (debug)** switch in Settings → General → Debug
 * (`ConfigStore.testAds`). With the switch off the placements in `ui/AdSlots.kt` compose
 * nothing at all and no request is made, so the app is ad-free by default.
 *
 * A second hidden switch, **Pinned ads (debug)** (`ConfigStore.pinnedAds`), only picks the
 * *placement*: off keeps the original layout (a banner at the end of the Home column, a native
 * card above the History list), on hosts the banner in the app's bottom bar so every screen
 * shows the same pinned one and gives History a full-page native ad on every third access.
 * It never enables serving on its own — this gate still decides that.
 *
 * Serving is **test-only** today: requests go to Google's demo ad units [AdUnits] under the
 * sample AdMob app ID, so there is no live inventory and no account attached. Going live
 * means real IDs, Google's consent flow for the EEA/UK/Switzerland before the first
 * request, and the Play policy gates in `docs/ad-monetization-options.md` — above all the
 * prohibition on using health and fitness data for advertising, which is why no placement
 * here may be targeted with heart rate, pace or workout data.
 */
object Ads {
    /**
     * Whether a placement may load right now: the **Test ads (debug)** gate is on *and* the SDK
     * has finished initializing. This, not the switch alone, is what the placements are gated
     * on.
     *
     * The distinction matters because `MobileAds.initialize` blocks until initialization is done
     * but runs off the main thread: a banner composed in the meantime calls `AdView.loadAd`
     * before the SDK is ready, and the SDK answers with
     * `IllegalStateException: MobileAds.initialize must be called before using the Google Mobile
     * Ads SDK.` — a crash, since the load happens inside a `DisposableEffect`. Waiting for
     * [serving] removes the window entirely.
     */
    private val _serving = MutableStateFlow(false)
    val serving: StateFlow<Boolean> = _serving.asStateFlow()

    /** Guards against a second `initialize` while the first is still running. */
    @Volatile
    private var initializing = false

    private var log: DebugLog? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Turns ad serving on or off for the whole app. Enabling initializes the SDK once, off
     * the main thread — the SDK documents an ANR if `initialize` runs on it. Disabling stops
     * every placement: their composables drop their views and no further request is made.
     *
     * Serving does not begin until `initialize` returns; a failure leaves it off rather than
     * letting a placement call into an uninitialized SDK. A later enable retries.
     *
     * [debugLog] is the app's trace, used only to record ad lifecycle lines under the `[ads]`
     * tag while "Debug tracing" is on.
     */
    fun setEnabled(context: Context, enabled: Boolean, debugLog: DebugLog? = null) {
        if (debugLog != null) log = debugLog
        if (!enabled) {
            _serving.value = false
            note("serving disabled")
            return
        }
        if (_serving.value) return
        if (MobileAds.isInitialized) {
            _serving.value = true
            note("serving enabled (SDK already initialized)")
            return
        }
        if (initializing) return
        initializing = true
        note("initializing SDK (test inventory ${AdUnits.APP_ID})")
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                MobileAds.initialize(appContext, InitializationConfig.Builder(AdUnits.APP_ID).build())
            }.onSuccess {
                // initialize() blocks, so the SDK reports itself initialized by the time this
                // runs — placements may load from here on.
                note("SDK initialized")
                _serving.value = true
            }.onFailure {
                note("SDK initialization failed: ${it.message}")
            }
            initializing = false
        }
    }

    /** Records an `[ads]` line in the app trace; a no-op while debug tracing is off. */
    fun note(message: String) {
        log?.log("[ads] $message")
    }
}
