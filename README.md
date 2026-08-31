# morkStep

Modern Android fitness app for **Interval Walking Training** (IWT / "Japanese walking").

IWT alternates brisk "push" intervals with slower "recovery" intervals. morkStep guides a session through a configurable plan, gives live pace and heart-rate feedback, plays audio cues when you drift from your pace or heart-rate targets, and records every completed workout to a history log.

## Features

- **Profiles** — save unlimited named workout configurations; pick the active one on the **home screen** or in **Settings**, which also lists every saved profile and can **clone** or **delete** any of them. Defaults to *Adhoc*.
- **Workout-length modes** — choose how each workout is bounded:
  - **Rounds** — a fixed number of push/recovery pairs plus warm-up/cool-down.
  - **Distance** — runs until a target distance (miles) is covered.
  - **Time** — runs for a target duration (minutes).
  - **Adhoc** — no preset length; ends when you tap Finish.
- **Configurable intervals** — warm-up length, push interval, recovery interval, cool-down length (per profile), adjustable in **15-second** steps (e.g. 30 s is selectable).
- **Pause / resume** — freeze a workout mid-session (elapsed time, distance and audio cues stop) and continue where you left off; paused wall-clock time is excluded from the recorded duration. Discard and Finish still work while paused, and pausing never alters the length plan.
- **Pace limits (mph)** — a per-profile *ceiling* and *floor* (miles per hour). Push targets the ceiling; recovery targets the floor.
- **Heart rate** — a *ceiling* and *floor* (bpm). Push cues "Speed up" while HR is below the ceiling; recovery cues "Slow down" while HR is above the floor.
- **Audio cues** —
    - per-phase spoken announcements plus beeps on transitions,
    - spoken warning cues — during **push**, "Speed up" when pace or HR is below the push *ceiling*; during **recovery**, "Slow down" when pace or HR is above the recovery *floor*. Pace and HR share one cue per phase so they never double-fire, and a 0 reading (no signal — 0 BPM or 0 MPH) never triggers a cue. A cue repeats at most once per a configurable threshold in seconds, shared by push and recovery; the **first** warning after each phase transition is suppressed so a stale sensor reading from the previous phase does not trigger a spurious cue,
    - a cue on **each quarter**, measured on the chosen length dimension — round count for **Rounds**, miles for **Distance**, minutes for **Time** ("One quarter done", "Halfway there", "Three quarters done"),
  - for **Adhoc** workouts, a cue every N completed push rounds (configurable, N=0 off).
- **Workout history** — every completed session is auto-saved (date, duration, push count, distance *mi*, seconds over ceiling) plus **per-phase averages** — average pace and HR for **push**, **recovery**, and **overall** — listed in a History screen. Averages are 1 Hz samples accumulated by the engine and bucketed by phase.
- **Runs with the screen locked** — a running session starts a foreground service (`WorkoutService`) that holds a partial wake lock so the 1 Hz ticker keeps firing on schedule (audio cues stay on time) and posts an ongoing notification, so the session survives backgrounding and process pressure. The service stops on finish, discard, or profile change tear-down. Saving a profile in Settings confirms with a "Profile saved" snackbar and returns to Home.
- **Workout plan at a glance** — the home screen shows the active profile's push/recovery and warm-up/cool-down durations as `m:ss` (plain seconds under a minute) instead of rounded minutes, plus the configured vibration mode.
- **Vibration** — per-profile haptics chosen in Settings: **Off**, **On phase change** (warm-up, push, recovery, cool-down, finish), or **All cues** (also quarter, push-round, and warning cues, mirroring audio). The watch can mirror them too: turn on **Vibrate watch** and the paired Wear companion buzzes alongside the phone.

## Requirements

| Tool | Version |
| ---- | ------- |
| Android Studio JBR (JDK) | 21 |
| Android SDK | compileSdk 36 (Android 16), targetSdk 36, minSdk 26 |
| Gradle | 8.11.1 (wrapper) |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.0.21 (Compose compiler plugin 2.0.21) |
| Play Services (Fused Location) | play-services-location 21.3.0 |

`local.properties` must set `sdk.dir` to your SDK path.

## Build & run

```bash
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # run unit tests
adb install -r app/build/outputs/apk/debug/morkStep-0.5.0-debug.apk # APK name includes the app version
```

### Release (signed) build

```bash
./gradlew assembleRelease        # build a signed release APK
adb install -r app/build/outputs/apk/release/morkStep-0.5.0-release.apk # versioned artifact
```

Release signing reads a **gitignored** `keystore.properties` at the repo root:

```
storeFile=release.keystore
storePassword=<your store password>
keyAlias=morkstep
keyPassword=<your key password>
```

It points at the `release.keystore` in the repo root (also gitignored). To create it once:

```bash
keytool -genkeypair -v -keystore release.keystore -alias morkstep \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=morkStep Dev, OU=morkStep, O=morkStep, L=, ST=, C=US"
```

If `keystore.properties` is absent/incomplete, `assembleRelease` still succeeds but produces an **unsigned** `morkStep-release-unsigned.apk` (cannot be installed on a device) — the build never hard-fails on a missing secret. Present credentials → a signed, sideloadable `morkStep-release.apk`. Keep the keystore and its password safe and private: they are the app's release identity and are **not** backed up or committed.

---
## Architecture

All code lives under `app/src/main/java/com/morkstep/`, organised by responsibility.

### Module map

| Package | Responsibility |
| ------- | -------------- |
| `engine/` | Pure interval session state machine and cue logic (no Android deps except a clock) |
| `sensing/` | Pace & heart-rate source abstractions + a simulated implementation |
| `audio/` | TTS + tone cues |
| `data/` | Domain models, profiles + config persistence (DataStore), workout history (Room) |
| `ui/` | Jetpack Compose screens + ViewModel wiring |
| `wear/` | Wear OS companion app: live heart-rate relay (Wear Health Services → phone) + vibration relay (phone → watch) |

### Data flow

```
ConfigScreen ──save──▶ DataStore (IntervalConfig)
                            │
                            ▼
                   SessionEngine (wall-clock
                         state machine)
                        │               │
   pace/hr sensors ─────┤               ├──▶ audio CueSink (beeps/TTS)
                        │               │
                        ▼               ▼
                     LiveState       on-finish
                        │               │
               WorkoutScreen ◀──▶ Room (history)
```

### Interval engine — `engine/SessionEngine.kt`

The core is a **pure, deterministic state machine** (`phaseAt`, `planFor`, `completedFastIn`, `progressAt`) that maps elapsed wall-clock seconds + accumulated distance to a position in the active profile's plan. It is deliberately *push-only*: its owner drives time forward with `tick()`, it reads instantaneous pace/HR, and writes an immutable `LiveState` snapshot plus cues.

The pure mapping helpers have **no Android dependencies**, so they are unit-tested on the JVM without instrumentation. Wall-clock is behind a `SessionClock` interface so tests can fake time and assert exact transitions.

**Why this shape:**

- **One engine, every length mode.** `planFor` yields a `(coreEndSec, finishSec)` pair: ROUNDS sums the cycle, TIME targets a fixed duration and reserves cool-down at the end, DISTANCE latches the end live when the distance target is crossed, and ADHOC never finishes on its own. Elapsed time is recomputed from `SystemClock.elapsedRealtime()` each tick, so a dropped or late tick never corrupts the running total; progress and fast-segment counts are plan-derived, not transition-counted.
- **Distance is integrated, not sampled.** Each tick adds `pace × dt / 3600` miles, so distance tracks the live pace signal smoothly and its accumulation works even with irregular tick cadence (unit-tested).
- **Pull sensors, push state.** The engine observes pace/HR via `StateFlow` and emits aggregated state downstream. The UI never drives the engine's truth; it only renders `LiveState`.

### Sensing — `sensing/`

Pace and HR are two narrow interfaces (`PaceSource`, `HeartRateSource`) returning `StateFlow`; the engine and UI depend only on those. Production sources:

- **`GpsPaceSource`** — real pace via the **Fused Location Provider** (Play Services), `Location.getSpeed()` m/s → mph (×2.23694), 1 s updates.
- **`BleHeartRateSource`** — real HR via a **Bluetooth LE heart-rate strap** (Heart Rate Service `0x180D` / measurement `0x2A37`): scans for the service, connects, subscribes to notifications, parses 8/16-bit HR payloads. Disconnects re-scan automatically; gives up after 60 s.
- **`SimulatedSensors`** — developer-only. Random-walks toward phase targets so cue/history paths can be exercised **when explicitly toggled on** in Settings. It is **never an automatic fallback**: when off, a missing signal simply reads blank (`–`), so real workouts can never be silently polluted by fake readings.
- **`WearHeartRateSource`** — heart rate relayed from the paired **morkStep Wear** companion over the Wearable message layer (path `/morkstep/hr`); the watch reads HR via Wear Health Services and streams each beat-per-minute value on demand. Selected in Settings with the "Heart rate from Wear companion" switch (used instead of BLE when on).

The simulated toggle lives in Settings ("Simulated sensors (debug)", default **off**) and is persisted in DataStore; the Workout screen shows a "no live hardware readings" banner while it is on. Runtime sensor permissions (fine location, BLE scan/connect) are requested from Settings; on Android 16 the app targets `compileSdk`/`targetSdk 36`.

**Wear companion.** `wear/` is a standalone Wear OS app (its own APK, `morkStep-wear-<version>-debug.apk`, e.g. `morkStep-wear-0.2.0-debug.apk`) that streams the watch's live heart rate to the phone and buzzes when the phone relays a cue. Vibration gating happens on the phone — the active profile's vibration mode decides, and the optional **Vibrate watch** setting forwards permitted cues to the watch on path `/morkstep/vibrate`. The watch app also shows the live HR value and its app version on-screen.

### Storage — `data/`

- **`ConfigStore`** — the profile list (JSON via kotlinx.serialization) and the active profile id, persisted through Jetpack DataStore (Preferences): coroutine-native, atomic, lock-free. A deliberate round-trip rule: a zero-length warm-up/cool-down is real user intent ("none"), so raw seconds are persisted and re-read skips them, rather than silently resurrecting defaults.
- **`WorkoutHistory.kt`** — Room `@Entity`/`@Dao` with a v1→v2 migration (`distanceKm` → `distanceMiles`). History is a relational, time-ordered list with a stable primary key; completed workouts stream into the History screen via `Flow`.

### UI — `ui/`

Jetpack Compose + Material 3 with a bottom navigation shell (`Home`, `History`, `Settings`) and a separate full-screen `Workout` route. State comes from a single `MainViewModel` exposing `config` and `live` as `StateFlow`, collected with `collectAsStateWithLifecycle` so recomposition tracks the running session. No XML layouts.

## Tools & language servers used

**Build toolchain**

| Tool | Version | Why |
| ---- | ------- | --- |
| Gradle 8.11.1 (wrapper) | 8.11.1 | Pinned via the wrapper for reproducible builds |
| Android Gradle Plugin | 8.10.1 | Matches the root build; compatible with Gradle 8.11.1 and Compose BOM 2024.10.01 |
| Kotlin | 2.0.21 | Compose compiler now ships with Kotlin (the `compose` compiler Gradle plugin), so build and toolchain stay in lockstep |
| Jetpack Compose BOM | 2024.10.01 | Bundles Compose material/UI versions together |
| KSP | 2.0.21-1.0.28 | Room compile-time codegen |
| JDK | 21 (Android Studio JBR) | Gradle 8.x + AGP 8.x require JDK 17+; the Studio-bundled JBR is available offline |
| Room | 2.6.1 | Type-safe SQLite DAO for workout history |
| DataStore Preferences | 1.0.0 | Coroutine-backed config persistence |
| kotlinx-serialization-json | 1.6.3 | Profile-list JSON persistence (with the Kotlin serialization plugin 2.0.21) |

**Language servers (LSP)**
| Server | Version | Notes |
| ------ | ------- | ----- |
| `kotlin-lsp` (fwcd/kotlin-language-server) | 1.3.13 | Navigation, symbols, references, refactors, diagnostics. Downloaded to `.tools/`, launched with the Android Studio JBR 21 via `<project>/.omp/lsp.json`. See classpath note below. |

> **LSP setup (standard config, no machine paths committed).** kotlin-lsp cannot discover the
> Android compile classpath itself — its bundled resolver understands plain JVM Gradle
> projects only, and BSP is unavailable for `com.android.application`. One task fixes the
> whole pipeline: `./gradlew :app:exportLspClasspath` regenerates both machine-local files
> kotlin-lsp needs:
>   - `.classpath.absolute` (repo root) — app compile classpath (`debugCompileClasspath`),
>     unit-test classpath (`compileDebugUnitTestKotlin.libraries`), and the
>     `android-36/android.jar` platform jar. AARs can't be indexed by kotlin-lsp (it reads
>     jars/class-dir only), so each `.aar`'s `classes.jar` is extracted into `app/build/lsp`
>     and referenced there.
>   - `.omp/lsp.json` — harness wiring: spawns `java.exe` directly (a `.bat` cannot inherit a
>     pipe stdin on Windows, so a script launcher exits instantly) with `-Xmx1g` and a
>     `-classpath` of the server lib (`server/lib/*` — `org.javacs.kt.MainKt`) plus the
>     project entries above.
> Both files are gitignored and regenerated by the task — a fresh checkout only needs the
> task run once. Two server quirks to know: the **first** diagnostics request after a reload
> can report cascading `Unresolved reference` false positives while the index warms (simply
> re-request — subsequent checks are clean), and **workspace symbol search is broken** in
> kotlin-lsp 1.3.13 (it returns `Location` without `range`, so the harness rejects the
> result; `typeDefinition`/`implementation` are likewise unimplemented server-side). The
> Gradle build (`assembleDebug`, `testDebugUnitTest`) remains the authority on type errors.

## Upgrade caveats

  extras (junit, coroutines-test). AAR entries are unpacked (`classes.jar` → `app/build/lsp/`)
  by the same task because kotlin-lsp indexes jars/class dirs only — the build directory is
  regenerable, so nothing machine-specific is committed.
- **Versioned APK artifacts.** `android.applicationVariants.all` renames outputs to
  `morkStep-$versionName-$buildType.apk`. Bump `versionCode` and `versionName` together in
  `app/build.gradle.kts` — and keep the `adb install` paths in this README's Build & run
  section in sync.

The harness also auto-loads built-in `pylsp` for Python regardless.

**Runtime environment**
- Emulator: `Medium_Phone_API_36.1` AVD driven via `adb`.
- OS: Windows 11 Pro x64.

`app/build.gradle.kts` pins versions to ones cached/available offline where possible to keep cold builds reproducible without a large dependency download.

## Test plan

`app/src/test/java/com/morkstep/engine/SessionEngineTest.kt` covers (37 tests):
- plan computation for ROUNDS / TIME length modes
- time→phase mapping, seconds-in-phase, and phase ordinal (fast=1, slow=2)
- plan-relative fast-segment counting (tick-cadence independent)
- progress fraction for finite modes; `null` for Adhoc; distance-based for DISTANCE
- engine behavior: phase advance + beeps + announcements, finish marking
- TIME mode finishes exactly at the target duration
- ADHOC runs until `endNow()` and cues every Nth completed push round
- quarter cues fire at 25% / 50% / 75%
- push warning cues: "Speed up" when pace or HR is below the push ceiling (incl. HR inside the old band); over-ceiling counter (push) without a push cue
- haptic vibration cues: `TRANSITION` on phase entry and finish; `GUIDANCE` on quarter and warning cues
- recovery warning cues: "Slow down" when pace or HR is above the recovery floor (incl. HR inside the old band)
- warning-repeat threshold in seconds shared by push and recovery
- single shared cue (one speech + one vibration) when both HR and pace trigger
- zero-readout suppression: 0 BPM / 0 MPH never cue, on push and recovery
- first warning cue after each phase transition suppressed (FAST and SLOW)
- distance accumulation from pace (mph → miles)
- per-phase average accumulation: push/recovery/overall pace & HR bucketed from 1 Hz samples

Run with `./gradlew testDebugUnitTest`.