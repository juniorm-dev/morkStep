# Ad monetization — options survey (recorded 2026-09-15)

Do NOT implement without explicit approval. This is a survey of what is available, not a
plan of record; nothing below is wired into the app.

**Scope.** Ad/mediation SDKs, which formats can legally and sensibly go on which morkStep
surface, and the store-policy gates this app has to clear first. Google SDK artifact names
and versions were read from the vendor docs on 2026-09-15 and will rot — re-check them
before building.

**Status (0.16.0):** implemented **test-only, behind the hidden Debug switch** (§6). The GMA
Next-Gen SDK is wired and two placements exist (Home banner, History native); there is no
interstitial, no rewarded ad, no mediation adapter, no billing library, no live ad unit ID
and no `AD_ID` declaration. With the switch off the SDK is never initialized and nothing is
requested.

## 0. What the app already has (evidence)

| Fact | Where |
| --- | --- |
| `android.permission.INTERNET` already declared | `app/src/main/AndroidManifest.xml` |
| `minSdk 26` / `compileSdk 36` / `targetSdk 36`, Kotlin 2.2.10 | `app/build.gradle.kts` |
| Ads code at the survey's first pass: none (the `grep -i "ads\|AdView\|AdMob\|billing\|Purchase\|premium\|Advert"` sweep over `app/src`, `wear/src`, both build files and `settings.gradle.kts` matched only "re**ads**"). 0.16.0 added the framework described in §6 | repo |
| Two modules: phone `:app`, standalone Wear companion `:wear` (Wearable message layer, `com.google.android.wearable.standalone = true`) | `settings.gradle.kts`, `wear/src/main/AndroidManifest.xml` |
| Health data in play: `health.READ_HEART_RATE`, `health.READ_HEALTH_DATA_IN_BACKGROUND` on `:app`; `BODY_SENSORS` on `:wear` | both manifests |
| No privacy policy document in the repo (`docs/` holds this survey and `pace-future-improvements.md`) | repo |

The manifest permissions and SDK levels clear every prerequisite below; the missing pieces
are an ad account, a privacy policy, and the Play Console declarations.

## 1. Google's own stack — two live variants

| | Artifact / version (docs 2026-09-15) | Floors | Notes |
| --- | --- | --- | --- |
| **GMA Next-Gen SDK** (the recommended one) | `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0` | minSdk 24, compileSdk 35, Kotlin ≥ 1.9 | App ID is passed **in code** — `MobileAds.initialize(context, InitializationConfig.Builder("ca-app-pub-…").build()) { … }` — not as manifest meta-data. Must be called on a background thread: the docs call out an ANR otherwise. |
| **Google Mobile Ads SDK (legacy)** | `com.google.android.gms:play-services-ads:25.4.0` | minSdk 23, compileSdk 35 | Carries a **maintenance-mode banner**: "For the latest updates and features, migrate and set up GMA Next-Gen SDK". App ID goes in the manifest as `com.google.android.gms.ads.APPLICATION_ID`; SDK ≥ 20.4.0 self-declares `com.google.android.gms.permission.AD_ID`. Legacy docs are what the mediation adapter catalogue still points at. |
| **Google Ad Manager (GAM)** | same SDKs, GAM account instead of AdMob | as above | Only needed for direct-sold inventory, price floors, or running your own sales. |

Both expose the same format set: **banner, interstitial, native, rewarded, rewarded
interstitial, app open** (plus native custom rendering). Mediation then attaches third-party
demand without changing the app-facing call sites.

Only `:app` would get the dependency; `:wear` has no ad surface (see §3).

Worth checking at implementation time: the ads SDK pulls `play-services-*` transitively, and
`:app` already pins `play-services-location:21.3.0` / `play-services-wearable:20.0.1`.
Aligning those is a Gradle-level task, not a design one. [INFERENCE — no conflict observed,
just untested version mixing.]

## 2. Third-party demand — two routes

**Route A — through AdMob/GAM mediation** (add adapters, keep one call site). Ad-source
catalogue as of the vendor page's own "Last updated 2026-09-14 UTC":

- **SDK-required, open-sourced adapters:** AppLovin, BidMachine, BIGO Ads, Chartboost,
  DT Exchange, i-mobile, InMobi, ironSource Ads, Liftoff Monetize, LY Ads Network, maio,
  **Meta Audience Network**, Mintegral, Moloco, myTarget, Pangle, PubMatic OpenWrap,
  Unity Ads, Vpon, Zucks.
- **Bidding-only, no third-party SDK:** Ad Generation, Bidease, Chocolate Platform,
  Equativ, Fluct, Improve Digital, Index Exchange, InMobi Exchange, Magnite DV+, Media.net,
  MobFox, Nativo, Nexxen, OneTag Exchange, OpenX, PubMatic, Rise, Sharethrough, Smaato,
  Sonobi, TripleLift, Verve Group, Yieldmo, YieldOne.
- Anything not listed → **custom events** (your own adapter).

**Route B — a different primary mediation platform**, replacing the Google SDK rather than
sitting under it: **AppLovin MAX**, **Unity LevelPlay** (ironSource), **Amazon Publisher
Services**. More fill/eCPM upside, more work: your own consent flow, account, reporting
webhooks, and a second adapter set.

For this app the practical middle ground is Route A: AdMob alone first, add adapters only
if fill/revenue justifies the integration and policy review each network carries.

## 3. Formats × surfaces (what is legal and sensible here)

Google Play's Ads policy is explicit about the patterns that get apps rejected — the ones
that matter for morkStep:

- **No interstitial at the start of a content segment or during gameplay**, and none before
  a splash/loading screen. A running workout is this app's "gameplay": **no interstitial may
  fire during a session** — not at start, not on a phase change, not on the finish line
  while audio cues are still playing.
- **Full-screen interstitials must be dismissible within 15 s** (rewarded ads are exempt
  from that rule).
- **"Made for ads"**: no interstitials chained after consecutive user actions.
- No lockscreen monetization, no ads rendered outside the app, no false dismiss buttons, and
  ad content must fit the app's content rating.

| Surface | Viable format | Reasoning |
| --- | --- | --- |
| Home (`ui/HomeScreen.kt`) | banner or a native card below the profile/plan cards | Passive, above the nav bar, never between the user and *Start workout* |
| History list (`ui/HistoryScreen.kt`) | native, rendered into the list | Matches the existing card layout; scroll-safe |
| After finish / discard | interstitial **after** the summary and snackbar, never during | Natural break in the flow; the finish path already navigates |
| Optional extras (extra chart views, on-demand export, re-calibration) | rewarded / rewarded interstitial | The only formats where a > 15 s unskippable ad is allowed |
| Workout screen (`ui/WorkoutScreen.kt`) | **none by placement** | Live session plus spoken cues (`audio/CueSpeaker.kt`); policy and UX both exclude a full-screen ad there, and nothing opens an interstitial. The **Pinned ads (debug)** placement does put the shared bottom-bar banner on this route too — see §6 |
| Wear companion (`:wear`) | **none available** | No Wear OS form factor or format appears anywhere in the GMA platform/format docs (Android, iOS, Unity, Flutter, Android-Legacy). The watch screens stay ad-free. |

## 4. Policy and compliance gates (blocking, not optional)

**Health data may not drive ads.** Play's *Android Health Permissions: Guidance and FAQs*
lists under **Prohibited uses of Android Health and Fitness data → Commercial exploitation
and advertising**:

> Transferring or selling user health or fitness data to third parties like advertising
> platforms, data brokers, or any information resellers.
>
> Transferring, selling, or using user health and fitness data for serving ads, including
> personalized or interest-based advertising.

morkStep reads HR through Health Connect and steps/sensors on both modules, so this binds it
directly: ads are permitted, but **no targeting, profiling, or audience-building from
HR/pace/workout data**, and no health data handed to an ad SDK. In practice that means
contextual / limited-ads serving only, and a network-by-network read of the data-sharing
terms in any mediation adapter before enabling it. The same guidance states it applies to
Wear OS apps too.

Other gates:

- **Data safety (Play Console)**: declare Advertising ID and "Advertising or marketing"
  collection. `AD_ID` comes auto-declared with SDK ≥ 20.4.0; the declaration is still yours.
- **Advertising ID rules**: honor "Opt out of Ads Personalization" / a deleted AD_ID on
  every access; never join AAID to SSAID/MAC/IMEI.
- **Consent (EEA/UK/Switzerland)**: Google's UMP SDK must complete before
  `MobileAds.initialize()` / before any ad load. Ads can be preloaded by the SDK at
  initialization, so consent has to be settled first.
- **`app-ads.txt`** published for the app's store listing domain in the AdMob flow.
- **Privacy policy** linked from the store listing and the app — required once AAID is
  collected, and required anyway by the health-data guidance. Not in the repo today.
- **Location**: policy forbids requesting location *for ads*; the app's `ACCESS_FINE_LOCATION`
  is the GPS pace source (`sensing/GpsSpeedSource.kt`), which stays compliant as long as
  location is never handed to the ad stack.
- **Content rating**: ad creative must match the app's rating; the app is a general-audience
  fitness app, not Designed for Families.

## 5. Non-SDK monetization (worth weighing before an SDK)

- **Play Billing one-time unlock / subscription** ("remove ads", premium analysis), paired
  with ads, is the standard shape and adds no SDK policy surface beyond billing itself.
- **Sponsor / affiliate cards** (shoe, strap, race) avoid the SDK, the AAID and the
  health-data review surface entirely, at the cost of manual sales.

## 6. What shipped (0.16.0) — and what is still open

Implemented **test-only**, behind the hidden **Test ads (debug)** switch (Settings → General
→ Debug, itself revealed by the six-tap gesture). Nothing is live and nothing is requested
with the switch off.

- **SDK** — GMA Next-Gen `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0`,
  initialized once and off the main thread the first time the switch is turned on; the sample
  AdMob app ID is passed in code, as the Next-Gen guide requires (no manifest meta-data).
- **Gate** — `ads/Ads.kt` owns the SDK lifecycle and `ads/AdUnits.kt` the demo unit IDs.
  With the switch off nothing initializes and no placement composes, so the app issues no ad
  request at all (verified: an empty SDK log after a cold start with the switch off).
- **Placements** — two layouts, picked by a second hidden switch, **Pinned ads (debug)**
  (`ConfigStore.pinnedAds`; placement only — the Test-ads gate still decides whether anything
  is served), both in `ui/AdSlots.kt`, each slot destroyed when it leaves composition:
  - **off** (the default) — an anchored adaptive banner at the end of the Home column and a
    native card above the History list;
  - **on** — the banner moves into the app's own bottom bar, above the tabs, so no screen's
    scrolling can carry it away and every route (the Workout route included — a banner, never
    a full-screen ad, and nothing opens during a session from the app's side) shows the same
    pinned banner; on the Workout route that banner uses the fixed **320×50** size rather than
    the large anchored adaptive one, so the live session keeps its height. The History inline
    card is replaced by a **full-page** native ad that opens on every third History access
    (`Constants.HISTORY_FULL_PAGE_AD_EVERY_N_ACCESSES`) and is closed by its own button or the
    system back gesture. Both history ads carry a **Close ad** control of the app's, drawn
    above the `NativeAdView` and never over it.
  No interstitial, no rewarded ad, and no ad code on the watch. The full-page native ad is the
  full-screen shape the 15-second dismissibility rule is written for, and it is closable at
  any moment — the rule that keeps a screen from being trapped.
- **The full-page guard count is persisted.** The History access count behind the every-third
  full-page ad is `ConfigStore.historyAdAccesses` (DataStore, default 0), so it resumes across
  an app restart instead of resetting with the process. It only advances while the pinned
  placement is on.
- **Banner formats the SDK offers** (GMA Next-Gen `ads-mobile-sdk:1.4.0`, read from the AAR on
  2026-09-18). `AdSize` exposes both fixed and adaptive sizes:

  | `AdSize` | Size (dp) | Notes |
  | --- | --- | --- |
  | `BANNER` | 320×50 | the app's Workout-route size |
  | `LARGE_BANNER` | 320×100 | |
  | `FULL_BANNER` | 468×60 | wider than a phone |
  | `LEADERBOARD` | 728×90 | tablet / landscape |
  | `MEDIUM_RECTANGLE` | 300×250 | |
  | `FLUID` | fills its container | |

  Adaptive sizes: `getLargeAnchoredAdaptiveBannerAdSize` / `getLargePortraitAnchoredAdaptiveBannerAdSize`
  / `getLargeLandscapeAnchoredAdaptiveBannerAdSize` (anchored, full-width — what the app uses),
  plus the inline variants `getInlineAdaptiveBannerAdSize(width, maxHeight)` /
  `getCurrentOrientationInlineAdaptiveBannerAdSize`. **Every non-large anchored adaptive size is
  deprecated in 1.4.0** ("Use `AdSize.getLargeAnchoredAdaptiveBannerAdSize` instead"), so the
  smaller banner on the Workout route is the fixed `BANNER` (320×50), not a "standard" anchored
  adaptive one. What an individual request returns still depends on the account's inventory —
  with the demo units everything renders as a labelled "Test Ad".
- **Trace** — ad lifecycle lines land in the debug log under `[ads]` while "Debug tracing" is on.
- **Test device** — Google's demo units are not tied to an AdMob account, so a developer
  cannot generate invalid traffic; the SDK additionally reports the emulator as a test device.

Deliberately not implemented:

- **Rewarded** — there is no in-app reward to attach it to (extra chart views, on-demand
  export and similar are product decisions); an unattached rewarded loader would be dead code.
- **Interstitial** — the format with the most policy surface (15-second dismissibility, no
  unexpected full-screen ads), and the finish flow already navigates and shows a snackbar.

Required before any of this could serve live inventory:

1. An AdMob account, a registered app, and real app/unit IDs replacing the demo ones.
2. Google's consent (UMP) flow for the EEA/UK/Switzerland, completed before the first request.
3. Play Console declarations (Data safety, Advertising ID) and a published privacy policy.
4. `app-ads.txt` published for the store-listing domain.
5. A pass over the placements with a real fill, including the no-fill and offline cases.

## 7. Open decisions

1. Formats beyond the two shipped: rewarded (needs a reward surface first) and interstitial
   (product and policy sign-off).
2. Whether ads ship alongside a paid "remove ads" unlock at the same time.
3. AdMob alone vs AdMob + mediation adapters (each adapter is one more privacy/terms review
   against the health-data prohibition in §4).

## Sources

Read 2026-09-15:

- GMA Next-Gen setup (artifact, floors, in-code app ID, background-thread init) —
  <https://developers.google.com/admob/android/next-gen/quick-start>
- Google Mobile Ads SDK legacy setup (maintenance-mode banner, `play-services-ads:25.4.0`,
  manifest app ID, `AD_ID` note) — <https://developers.google.com/admob/android/quick-start>
- AdMob mediation ad sources (catalogue above; page states "Last updated 2026-09-14 UTC") —
  <https://developers.google.com/admob/android/choose-networks>
- Play Ads policy (interstitial 15 s rule, no ads at content-segment start, made-for-ads,
  lockscreen, AAID rules) —
  <https://support.google.com/googleplay/android-developer/answer/9857753>
- Android Health Permissions guidance (prohibited advertising uses of health data; applies
  to Wear OS) —
  <https://support.google.com/googleplay/android-developer/answer/12991134>
- Permissions and APIs that Access Sensitive Information (location never for ads) —
  <https://support.google.com/googleplay/android-developer/answer/16558241>
