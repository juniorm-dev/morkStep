package com.morkstep.ads

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.morkstep.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    @Volatile
    private var initialized = false

    private var log: DebugLog? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Turns ad serving on or off for the whole app. Enabling initializes the SDK once, off
     * the main thread — the SDK documents an ANR if `initialize` runs on it. Disabling stops
     * every placement: their composables drop their views and no further request is made.
     *
     * [debugLog] is the app's trace, used only to record ad lifecycle lines under the `[ads]`
     * tag while "Debug tracing" is on.
     */
    fun setEnabled(context: Context, enabled: Boolean, debugLog: DebugLog? = null) {
        if (debugLog != null) log = debugLog
        if (!enabled) {
            note("serving disabled")
            return
        }
        if (initialized) return
        initialized = true
        note("initializing SDK (test inventory ${AdUnits.APP_ID})")
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                MobileAds.initialize(
                    appContext,
                    InitializationConfig.Builder(AdUnits.APP_ID).build(),
                ) {}
            }.onSuccess {
                note("SDK initialized")
            }.onFailure {
                note("SDK initialization failed: ${it.message}")
            }
        }
    }

    /** Records an `[ads]` line in the app trace; a no-op while debug tracing is off. */
    fun note(message: String) {
        log?.log("[ads] $message")
    }
}
