package com.morkstep.ads

/**
 * Ad unit and app identifiers.
 *
 * Everything here is **Google's demo inventory**, which is what the app serves while
 * it has no AdMob account: the demo ad units are not tied to any AdMob account, so
 * they cannot generate invalid traffic, and they return test creatives labelled
 * "Test Ad". Going live replaces these three values with the real ones (the app ID is
 * passed to `MobileAds.initialize` in [Ads], the two unit IDs are used by the
 * placements in `ui/AdSlots.kt`) — see `docs/ad-monetization-options.md`.
 */
object AdUnits {
    /** Google's sample AdMob app ID (`com.google.android.libraries.ads.mobile.sdk` diagnostics accept it). */
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713"

    /** Demo unit for anchored adaptive banners (Settings → General → Debug gates serving). */
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"

    /** Demo unit for native ads. */
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
}
