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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * The app's ad placements. [enabled] is false in every one of them while the hidden **Test ads
 * (debug)** switch is off: the composable emits nothing and makes no request, so that switch
 * is what decides whether the app talks to the ad SDK at all.
 *
 * Two layouts share these slots, chosen by the hidden **Pinned ads (debug)** switch:
 *
 * - off (the default, and the original layout) — an anchored adaptive banner at the end of the
 *   Home column and a native card above the History list;
 * - on — the banner is hosted by the app's bottom bar instead, so no screen's scrolling can
 *   carry it away, and the History card is replaced by a **full-page** native ad that opens on
 *   every third History access (`MainViewModel.onHistoryOpened`) and stays until it is closed.
 *
 * Every slot is destroyed when it leaves composition, and each history ad carries its own
 * close control **outside** the `NativeAdView`, so it is never mistakable for an ad asset.
 */

/**
 * Anchored adaptive banner. [horizontalInsetDp] is the per-side gutter the hosting surface
 * leaves — the screens inset their own content by [SCREEN_PADDING_DP], the bottom bar by
 * nothing — so the ad may not exceed the width that is left. The ad is registered into the
 * [AdView] that hosts it (the supported path — `BannerAd.load`/`getView` are deprecated) and
 * the view is destroyed when the slot leaves composition.
 */
@Suppress("FunctionName")
@Composable
fun BannerAdSlot(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    horizontalInsetDp: Int = SCREEN_PADDING_DP,
) {
    if (!enabled) return
    val activity = LocalContext.current.findActivity() ?: return
    // The SDK sizes an anchored adaptive banner from an explicit width.
    val widthDp = LocalConfiguration.current.screenWidthDp - 2 * horizontalInsetDp
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
 * is why each one is registered on the [NativeAdView] before the ad is shown. The card is
 * closed for the rest of the visit by its own **Close ad** button — a control of the app's,
 * drawn above the `NativeAdView` and never over it — and destroyed when the slot leaves
 * composition.
 */
@Suppress("FunctionName")
@Composable
fun NativeAdSlot(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val context = LocalContext.current
    var loadedAd by remember { mutableStateOf<NativeAd?>(null) }
    var dismissed by remember { mutableStateOf(false) }
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
    if (dismissed) return
    loadedAd?.let { ad ->
        Card(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                AdCloseRow { dismissed = true }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx -> nativeAdView(ctx, ad, titleColor, bodyColor, ctaColor) },
                )
            }
        }
    }
}

/**
 * Native ad over the whole screen — the **Pinned ads (debug)** placement's History surface. It
 * is drawn above everything the app has (see `MorkApp`), including the bottom bar, and stays
 * until the user closes it: the [onDismiss] control is the only way off it, so an ad can never
 * trap the user on the screen that opened it. Nothing is drawn while the ad is still loading
 * or fails to load — the screen underneath simply stays usable.
 */
@Suppress("FunctionName")
@Composable
fun FullPageNativeAdSlot(enabled: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!enabled) return
    val context = LocalContext.current
    var loadedAd by remember { mutableStateOf<NativeAd?>(null) }
    DisposableEffect(Unit) {
        NativeAdLoader.load(
            NativeAdRequest.Builder(AdUnits.NATIVE, listOf(NativeAd.NativeAdType.NATIVE)).build(),
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    loadedAd = nativeAd
                    Ads.note("full-page native ad loaded")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Ads.note("full-page native ad failed: ${adError.message}")
                }
            },
        )
        onDispose {
            loadedAd?.destroy()
            loadedAd = null
        }
    }
    val titleColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val ctaColor = MaterialTheme.colorScheme.primary.toArgb()
    loadedAd?.let { ad ->
        // Opaque, so the screen the ad was opened over is not visible under it.
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                AdCloseRow(onDismiss)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx -> nativeAdView(ctx, ad, titleColor, bodyColor, ctaColor, fullPage = true) },
                )
            }
        }
    }
}

/** The **Close ad** control: a row above the ad, never overlapping it. */
@Suppress("FunctionName")
@Composable
private fun AdCloseRow(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onClose,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("Close ad", style = MaterialTheme.typography.labelSmall)
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
    /** The whole-screen treatment: the content fills its host and the media takes the slack. */
    fullPage: Boolean = false,
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
        if (fullPage) {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        addView(attribution)
        addView(header, params(topMarginDp = 8))
        addView(body, params(topMarginDp = 12))
        addView(advertiser, params(topMarginDp = 4))
        // Full page: the media takes every pixel the text leaves, so the ad reads as a page
        // rather than a card floating in one.
        if (fullPage) {
            addView(
                media,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(12)
                },
            )
        } else {
            addView(media)
        }
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
