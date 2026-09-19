package com.morkstep.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 * Share of the screen height the creative takes in the full-page History ad, in dp. Fixed
 * rather than "whatever the text leaves" so every height in the page is known up front and
 * nothing (the ad's own call-to-action above all) can be pushed past the bottom edge.
 */
private const val FULL_PAGE_AD_MEDIA_SHARE = 0.45f

/**
 * Clearance the full-page ad's own call-to-action keeps from the bottom edge, in dp. The page is
 * drawn edge to edge (the platform enforces that for this target), so the bottom of the screen
 * is the system's gesture area — the ad's button is not put under it.
 */
private const val FULL_PAGE_AD_CTA_BOTTOM_DP = 48

/**
 * Height of the card layout's creative, in dp. A `MediaView` left to itself takes the
 * creative's own size — a tall image ad then filled the History screen and left the list no
 * room — and the SDK's own validator warns below 120 dp. This is the native template's
 * 16:9-ish treatment: bounded, and comfortably above the floor.
 */
private const val INLINE_AD_MEDIA_HEIGHT_DP = 240

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
 *   On the Workout route that bottom-bar banner drops to the fixed 320×50 size, so it takes
 *   less of the live session's height.
 *
 * A third hidden switch, **Small home banner (debug)**, drops the Home route's banner to that
 * same fixed 320×50 size — whichever layout is on, the Home column banner when off and the
 * bottom-bar banner on the Home route when on — so the two sizes can be compared.
 *
 * Every slot is destroyed when it leaves composition, and each history ad carries its own
 * close control **outside** the `NativeAdView`, so it is never mistakable for an ad asset.
 */

/**
 * Anchored adaptive banner. [horizontalInsetDp] is the per-side gutter the hosting surface
 * leaves — the screens inset their own content by [SCREEN_PADDING_DP], the bottom bar by
 * nothing — so the ad may not exceed the width that is left. [large] picks the full-width
 * large anchored adaptive size (the default) or the fixed 320×50 [AdSize.BANNER] used on the
 * Workout route and — under the hidden **Small home banner (debug)** switch — on the Home
 * route, which is the smaller non-deprecated format (the SDK deprecates every
 * non-large anchored adaptive size in favour of the large one). The ad is registered into the
 * [AdView] that hosts it (the supported path — `BannerAd.load`/`getView` are deprecated) and
 * the view is destroyed when the slot leaves composition.
 */
@Suppress("FunctionName")
@Composable
fun BannerAdSlot(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    horizontalInsetDp: Int = SCREEN_PADDING_DP,
    large: Boolean = true,
) {
    if (!enabled) return
    val activity = LocalContext.current.findActivity() ?: return
    // The SDK sizes an anchored adaptive banner from an explicit width.
    val widthDp = LocalConfiguration.current.screenWidthDp - 2 * horizontalInsetDp
    val adSize = remember(widthDp, large) {
        if (large) AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
        else AdSize.BANNER
    }
    // Keyed on the size: a route switch that changes it (large ⇄ 320×50) drops the AdView
    // loaded for the old size and loads a fresh one, rather than reusing a destroyed view.
    key(adSize) {
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
    val mediaHeightPx = with(LocalDensity.current) { INLINE_AD_MEDIA_HEIGHT_DP.dp.roundToPx() }
    if (dismissed) return
    loadedAd?.let { ad ->
        Card(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                AdCloseRow { dismissed = true }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx -> nativeAdView(ctx, ad, titleColor, bodyColor, ctaColor, cardMediaHeightPx = mediaHeightPx) },
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
    // The creative gets a fixed share of the page rather than "whatever the text leaves":
    // every height in the page is then known up front, so nothing can be pushed past the
    // bottom edge (the stretch that took the slack put the ad's own call-to-action off screen).
    val mediaHeightPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenHeightDp * FULL_PAGE_AD_MEDIA_SHARE).dp.roundToPx()
    }
    loadedAd?.let { ad ->
        // Opaque, so the screen the ad was opened over is not visible under it. The content
        // stays inside the safe area — the close control and the ad's call-to-action were
        // running under the system bars with the window's full height.
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                AdCloseRow(onDismiss)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        nativeAdView(ctx, ad, titleColor, bodyColor, ctaColor, fullPageMediaHeightPx = mediaHeightPx)
                    },
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
    /** Fixed height for the card's creative, in px (0 leaves the creative its own size). */
    cardMediaHeightPx: Int = 0,
    /**
     * The whole-screen treatment: the content fills its host, the creative is given exactly
     * [fullPageMediaHeightPx] (0 keeps the card layout).
     */
    fullPageMediaHeightPx: Int = 0,
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
    val fullPage = fullPageMediaHeightPx > 0
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
        if (fullPage) {
            // Text on top, the creative at its own size, the call-to-action at the foot of the
            // page, the slack between them — every height known, so the page can never overflow.
            // The media is measured inside a FrameLayout: a MediaView sizes itself from the
            // creative's own aspect ratio and ignores a height constraint, so adding it straight
            // to the column overflowed the page and pushed the call-to-action off the bottom
            // edge. The frame hands it an exact size.
            val mediaHost = FrameLayout(context).apply {
                addView(
                    media,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            addView(
                mediaHost,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fullPageMediaHeightPx).apply {
                    topMargin = dp(12)
                },
            )
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // The call-to-action keeps a wide margin below it: the page is drawn edge to edge
            // (the platform enforces that for this target), so the bottom of the screen is the
            // system's gesture area, not ours.
            addView(
                callToAction,
                params(topMarginDp = 12).apply { bottomMargin = dp(FULL_PAGE_AD_CTA_BOTTOM_DP) },
            )
        } else {
            addView(
                media,
                params(
                    height = if (cardMediaHeightPx > 0) cardMediaHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT,
                    topMarginDp = 12,
                ),
            )
            addView(callToAction, params(topMarginDp = 12))
        }
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
