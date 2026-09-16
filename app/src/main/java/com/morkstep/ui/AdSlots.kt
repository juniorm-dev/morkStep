package com.morkstep.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.morkstep.ads.AdUnits
import com.morkstep.ads.Ads

/** Padding the hosting screens inset their content by, in dp (the ad cards match it). */
private const val SCREEN_PADDING_DP = 16

/** Icon edge length of a native ad, in dp. */
private const val AD_ICON_DP = 40

/**
 * The app's ad placements — the two surfaces `docs/ad-monetization-options.md` recommends
 * (Home banner, History native). Both are **inert while [enabled] is false**: the composable
 * emits nothing and makes no request, so the switch in Settings → General → Debug is what
 * decides whether the app talks to the ad SDK at all.
 *
 * No placement touches a running session: the workout screen and the Wear companion carry no
 * ad code, and nothing loads an interstitial.
 */

/**
 * Anchored adaptive banner for the Home screen, sized to the screen width minus the screen's
 * own horizontal padding. The ad is registered into the [AdView] that hosts it (the supported
 * path — `BannerAd.load`/`getView` are deprecated) and the view is destroyed when the slot
 * leaves composition.
 */
@Suppress("FunctionName")
@Composable
fun BannerAdSlot(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val activity = LocalContext.current.findActivity() ?: return
    // The SDK sizes an anchored adaptive banner from an explicit width; the screens inset
    // their content by SCREEN_PADDING_DP on each side, so the banner may not exceed what is left.
    val widthDp = LocalConfiguration.current.screenWidthDp - 2 * SCREEN_PADDING_DP
    val adSize = remember(widthDp) {
        AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
    }
    val adView = remember { AdView(activity) }
    DisposableEffect(Unit) {
        adView.loadAd(
            BannerAdRequest.Builder(AdUnits.BANNER, adSize).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    adView.registerBannerAd(ad, activity)
                    Ads.note("banner loaded")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Ads.note("banner failed: ${adError.message}")
                }
            },
        )
        onDispose { adView.destroy() }
    }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(adSize.height.dp),
        factory = { adView },
    )
}

/**
 * Native ad for the History screen, laid out as one more card above the list. The SDK owns
 * click/impression recording and the AdChoices overlay; the app owns the asset views, which
 * is why each one is registered on the [NativeAdView] before the ad is shown. The ad is
 * destroyed when the slot leaves composition.
 */
@Suppress("FunctionName")
@Composable
fun NativeAdSlot(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val context = LocalContext.current
    var loadedAd by remember { mutableStateOf<NativeAd?>(null) }
    DisposableEffect(Unit) {
        NativeAdLoader.load(
            NativeAdRequest.Builder(AdUnits.NATIVE, listOf(NativeAd.NativeAdType.NATIVE)).build(),
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    loadedAd = nativeAd
                    Ads.note("native ad loaded")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Ads.note("native ad failed: ${adError.message}")
                }
            },
        )
        onDispose {
            loadedAd?.destroy()
            loadedAd = null
        }
    }
    // The asset views are built with plain Views, so the theme's colors are read here and
    // handed to the builder.
    val titleColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val ctaColor = MaterialTheme.colorScheme.primary.toArgb()
    loadedAd?.let { ad ->
        Card(modifier = modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx -> nativeAdView(ctx, ad, titleColor, bodyColor, ctaColor) },
            )
        }
    }
}

/**
 * Builds the ad's view tree, populating and registering every asset the loaded [ad] carries.
 * Assets the ad does not carry are left `INVISIBLE` rather than `GONE`, so the card keeps a
 * stable shape from one ad to the next.
 */
private fun nativeAdView(
    context: Context,
    ad: NativeAd,
    titleColor: Int,
    bodyColor: Int,
    ctaColor: Int,
): NativeAdView {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun params(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, topMarginDp: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(topMarginDp)
        }

    val attribution = text(11f, bodyColor).apply { text = "Ad" }
    val icon = ImageView(context).apply {
        adjustViewBounds = true
        setImageDrawable(ad.icon?.drawable)
    }
    val headline = text(16f, titleColor, bold = true).apply { text = ad.headline }
    val body = text(14f, bodyColor).apply { text = ad.body }
    val advertiser = text(12f, bodyColor).apply { text = ad.advertiser }
    val media = MediaView(context).apply { layoutParams = params(topMarginDp = 12) }
    val callToAction = text(14f, ctaColor, bold = true).apply { text = ad.callToAction }

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon, LinearLayout.LayoutParams(dp(AD_ICON_DP), dp(AD_ICON_DP)))
        addView(
            headline,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
    }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP))
        addView(attribution)
        addView(header, params(topMarginDp = 8))
        addView(body, params(topMarginDp = 12))
        addView(advertiser, params(topMarginDp = 4))
        addView(media)
        addView(callToAction, params(topMarginDp = 12))
    }

    // An asset the ad does not carry keeps its place but shows nothing.
    listOf(
        attribution to "Ad",
        headline to ad.headline,
        body to ad.body,
        advertiser to ad.advertiser,
        icon to ad.icon,
        callToAction to ad.callToAction,
    ).forEach { (view, asset) ->
        view.visibility = if (asset == null) View.INVISIBLE else View.VISIBLE
    }

    return NativeAdView(context).apply {
        addView(content)
        headlineView = headline
        bodyView = body
        advertiserView = advertiser
        iconView = icon
        callToActionView = callToAction
        registerNativeAd(ad, media)
    }
}

/** The [Activity] a Composition is hosted in, unwrapping any `ContextWrapper` on the way. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
