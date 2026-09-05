# morkStep

Modern Android fitness app for **Interval Walking Training** (IWT / "Japanese walking").

IWT alternates brisk "push" intervals with slower "recovery" intervals. morkStep guides a session through a configurable plan, gives live speed, pace and heart-rate feedback, plays audio cues when you drift from your targets, and records every completed workout to a history log.

## Features

- **Profiles** — save unlimited named workout configurations; pick the active one on the **home screen** or in **Settings**, which also lists every saved profile and can **clone** or **delete** any of them. Defaults to *Adhoc*.
- **Workout-length modes** — choose how each workout is bounded:
  - **Rounds** — a fixed number of push/recovery pairs plus warm-up/cool-down.
  - **Distance** — runs until a target distance (miles) is covered.
  - **Time** — runs for a target duration (minutes).
  - **Adhoc** — no preset length; ends when you tap Finish.
- **Configurable intervals** — warm-up length, push interval, recovery interval, cool-down length (per profile), adjustable in **15-second** steps (e.g. 30 s is selectable).
- **Pause / resume** — freeze a workout mid-session (elapsed time, distance and audio cues stop) and continue where you left off; paused wall-clock time is excluded from the recorded duration. Discard and Finish still work while paused, and pausing never alters the length plan.
- **Speed limits (mph)** — a per-profile *Push Min* floor and *Recovery Max* ceiling (miles per hour). Push keeps speed above the Push Min; recovery keeps speed below the Recovery Max.
- **Pace limits (steps/min)** — a pedometer metric. With a paired **Wear** companion the watch reads its step cadence (`STEPS_PER_MINUTE` via Wear Health Services) and streams it to the phone; without a watch, the **phone's own step sensor** measures cadence directly (no permission needed), falling back automatically after 15 s of watch silence. A per-profile *Push Min* floor and *Recovery Max* ceiling (spm) guide cadence the same way speed does. Pace shares one cue with speed and heart rate, so any single unmet target raises it. **Watch vs phone cadence:** the watch stream delivers an *instant* value as fast as Wear Health Services emits it (typically ~1 s, already smoothed by the watch firmware) — there is no rolling window and nothing to tune on that path. The phone fallback derives cadence itself over a rolling `Constants.PACE_WINDOW_MS` window (default 5 s — see its doc for the responsiveness-vs-stability options). Both paths feed the same per-phase averages and cue thresholds.
- **Heart rate** — a *Push Min* and *Recovery Max* (bpm). During push, HR should stay at or above the Push Min; during recovery, HR should stay at or below the Recovery Max (the Recovery Max is lower than the Push Min, since recovery targets a lower effort than push). Push cues "Speed up" while HR is below the Push Min; recovery cues "Slow down" while HR is above the Recovery Max.
- **Baseline profile** — **Create baseline** in Settings installs a short calibration workout (3 rounds: 45 s push / 45 s recovery, 20 s warm-up). The Baseline profile is hidden from the home profile list while it is active, so the home button reads **Start baseline**. When the workout ends (naturally or via **Finish early**) it is re-derived into a calibrated 30-minute baseline — 120 s push / 120 s recovery, warm-up 30 s, cool-down 30 s, with the speed, pace and heart-rate bands taken from the session's actual push/recovery averages (clamped to the slider ranges). The app then jumps to **Settings**, shows a **"Baseline created"** snackbar, and you can **Clone** it into your own profile (the current haptics settings are carried into the baseline).
- **Audio cues** —
    - per-phase spoken announcements plus beeps on transitions,
    - spoken warning cues — during **push**, "Speed up" when speed is below the *Push Min*, pace below the *Push Min* spm, or HR is below the *Push Min* bpm; during **recovery**, "Slow down" when speed is above the *Recovery Max*, pace above the *Recovery Max* spm, or HR is above the *Recovery Max* bpm. Speed, pace and HR share one cue per phase so they never double-fire, and a reading without a meaningful signal never triggers a cue — 0 BPM, speed at/below 1.5 mph (GPS noise when standing still), or pace at/below 10 spm (walking that slowly is deliberate — a rest break, not a missed target — so warnings would be noise). Phase-change cues take precedence over every other cue — a transition announcement is never clobbered by a warning or a workout-length cue (quarter / ADHOC every-Nth-push), which wait until the following tick. A cue repeats at most once per a configurable threshold in seconds, shared by push and recovery; the **first** warning after each phase transition is suppressed so a stale sensor reading from the previous phase does not trigger a spurious cue,
    - a cue on **each quarter**, measured on the chosen length dimension — round count for **Rounds**, miles for **Distance**, minutes for **Time** ("One quarter done", "Halfway there", "Three quarters done"),
  - for **Adhoc** workouts, a cue every N completed push rounds (configurable, N=0 off).
- **Workout history** — every completed session is auto-saved (date, duration, push count, distance *mi*, seconds above the push-min HR) plus **per-phase averages** — average speed, pace and HR for **push**, **recovery**, and **overall** — listed in a History screen. Averages are 1 Hz samples accumulated by the engine and bucketed by phase.
- **Runs with the screen locked** — a running session starts a foreground service (`WorkoutService`) that holds a partial wake lock so the 1 Hz ticker keeps firing on schedule (audio cues stay on time) and posts an ongoing notification, so the session survives backgrounding and process pressure. The service stops on finish, discard, or profile change tear-down. Saving a profile in Settings confirms with a "Profile saved" snackbar and returns to Home.
- **Workout plan at a glance** — the home screen shows the active profile's push/recovery and warm-up/cool-down durations as `m:ss` (plain seconds under a minute) instead of rounded minutes, plus the configured vibration mode.
- **Vibration** — per-profile haptics chosen in Settings: **Off**, **On phase change** (warm-up, push, recovery, cool-down, finish), or **All cues** (also quarter, push-round, and warning cues, mirroring audio). The watch can mirror them too: turn on **Vibrate watch** and the paired Wear companion buzzes alongside the phone.

## Requirements

| Tool | Version |
| ---- | ------- |
| Android Studio JBR (JDK) | 21 |
| Android SDK | compileSdk 36 (Android 16), targetSdk 36, minSdk 26 |
| Gradle | 9.4.0 (wrapper) |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.2.10 (Compose compiler plugin 2.2.10) |
| KSP | 2.2.10-2.0.2 |
| Room | 2.7.2 |
| Play Services (Fused Location) | play-services-location 21.3.0 |

`local.properties` must set `sdk.dir` to your SDK path.

> Gradle 9.4 runs on JDK 17–26, but KSP 2.2.10 cannot read JDK 26 class files yet
> (`unexpected jvm signature V`), so point `JAVA_HOME` at the Android Studio JBR
> (JDK 21) before running `gradlew` — e.g. on Windows:
> `set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"`.

## Build & run

```bash
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # run unit tests
adb install -r app/build/outputs/apk/debug/morkStep-debug-0.12.4.apk # versioned APK name
```

### Emulator (instrumented) tests — NOT run by default

The Compose smoke suites live in `src/androidTest` and are excluded from the
default `assemble`/`test` lifecycle. Run them explicitly against a booted
emulator when needed:

```bash
./gradlew :app:connectedDebugAndroidTest   # phone app UI smoke tests (13)
./gradlew :wear:connectedDebugAndroidTest  # Wear companion UI smoke tests (2)
```

Each suite installs the app, starts from a clean state (`clearPackageData`),
and asserts the home screen, navigation, and version footer. With multiple
devices attached, Gradle runs the suite on each: the app suite targets a phone
form factor (its nav taps assume a phone-sized display).

### Release (signed) build

```bash
./gradlew assembleRelease        # build a signed release APK
adb install -r app/build/outputs/apk/release/morkStep-release-0.12.4.apk # versioned artifact
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

### Releasing

**`release.bat`** runs the whole flow in one command (run on `main` after the feature PR has merged):

1. `git fetch --tags` so the version guard sees the latest tags;
2. `assembleRelease` — the **version guard** (`verifyReleaseVersion`) fails the build if `v<versionName>` already exists, i.e. you are about to ship a version you already released without bumping it;
3. tags the built source `v<versionName>` and pushes both the branch and the tag.

The package name is read from the same `app/build.gradle.kts` the guard checked and the tag is created from, so the tag can never describe a different version than the APK it points at. **Bump `versionCode`/`versionName` together in `app/build.gradle.kts` before running it** — if you forget, the guard stops `assembleRelease` with a clear message.

### Sideload the Wear app onto a real watch

The wear companion (`wear/`) is installed on a Wear OS watch (Wear OS 3+, e.g. OnePlus Watch 3) over `adb` — no Play console needed for testing.

```bash
./gradlew :wear:assembleDebug     # build — wear/build/outputs/apk/debug/morkStep-wear-debug-<version>.apk
```

1. **On the watch**, unlock developer options: Settings → About → tap the build/version number 7×, then enable **ADB debugging** and **Wi-Fi debugging** in Settings → Developer options. The watch shows an `IP:PORT` and a 6-digit pairing code. (OnePlus Watch 3: same route under its Settings.)
2. **On the PC** (watch and PC on the same Wi-Fi):

   ```bash
   adb pair <watch-ip>:<pair-port>       # enter the 6-digit code shown on the watch
   adb connect <watch-ip>:<debug-port>   # the port shown after pairing (different from the pair port)
   adb install -r wear/build/outputs/apk/debug/morkStep-wear-debug-<version>.apk
   adb -s <watch-ip>:<debug-port> shell am start -n com.morkstep.wear/.MainActivity
   ```

3. **To exercise the relays** (heart-rate up, session state/pause/vibrate down), pair the watch to the phone running the phone app — OnePlus Watch 3 pairs through the **OHealth** app — and grant the watch app the **body-sensor** permission when it prompts. Watch and phone must stay on the same Wi-Fi for the Wearable Data Layer.

Notes: `adb pair`/`connect` handle the secure handshake, so a mismatched debug build is the main failure mode — uninstall any previous `com.morkstep.wear` install first (`adb uninstall com.morkstep.wear`). A real watch reports live HR (Health Services), unlike the AOSP emulator which has no HR sensor.

---
## Architecture

All code lives under `app/src/main/java/com/morkstep/`, organised by responsibility.

### Module map

| Package | Responsibility |
| ------- | -------------- |
| `engine/` | Pure interval session state machine and cue logic (no Android deps except a clock) |
| `sensing/` | Speed, pace & heart-rate source abstractions + a simulated implementation |
| `audio/` | TTS + tone cues |
| `data/` | Domain models, profiles + config persistence (DataStore), workout history (Room) |
| `ui/` | Jetpack Compose screens + ViewModel wiring |
| `wear/` | Wear OS companion app: live heart-rate relay (Wear Health Services → phone) + vibration relay (phone → watch) |

### Data flow

```
ConfigScreen ──save──▶ DataStore (WorkoutProfile)
                            │
                            ▼
                   SessionEngine (wall-clock
                         state machine)
                        │               │
   speed / pace / hr sensors ──┤               ├──▶ audio CueSink (beeps/TTS)
                        │               │
                        ▼               ▼
                     LiveState       on-finish
                        │               │
               WorkoutScreen ◀──▶ Room (history)
```

### Interval engine — `engine/SessionEngine.kt`

The core is a **pure, deterministic state machine** (`phaseAt`, `planFor`, `completedPushIn`, `progressAt`) that maps elapsed wall-clock seconds + accumulated distance to a position in the active profile's plan. It is deliberately *push-only*: its owner drives time forward with `tick()`, it reads instantaneous speed/pace/HR, and writes an immutable `LiveState` snapshot plus cues.

The pure mapping helpers have **no Android dependencies**, so they are unit-tested on the JVM without instrumentation. Wall-clock is behind a `SessionClock` interface so tests can fake time and assert exact transitions.

**Why this shape:**

- **One engine, every length mode.** `planFor` yields a `(coreEndSec, finishSec)` pair: ROUNDS sums the cycle, TIME targets a fixed duration and reserves cool-down at the end, DISTANCE latches the end live when the distance target is crossed, and ADHOC never finishes on its own. Elapsed time is recomputed from `SystemClock.elapsedRealtime()` each tick, so a dropped or late tick never corrupts the running total; progress and fast-segment counts are plan-derived, not transition-counted.
- **Distance is integrated, not sampled.** Each tick adds `speed × dt / 3600` miles, so distance tracks the live speed signal smoothly and its accumulation works even with irregular tick cadence (unit-tested).
- **Pull sensors, push state.** The engine observes speed/pace/HR via `StateFlow` and emits aggregated state downstream. The UI never drives the engine's truth; it only renders `LiveState`.

### Sensing — `sensing/`

Speed, pace and HR are narrow interfaces (`SpeedSource`, `PaceSource`, `HeartRateSource`) returning `StateFlow`; the engine and UI depend only on those. Production sources:

- **`GpsSpeedSource`** — real speed via the **Fused Location Provider** (Play Services), `Location.getSpeed()` m/s → mph (×2.23694), 1 s updates.
- **`BleHeartRateSource`** — real HR via a **Bluetooth LE heart-rate strap** (Heart Rate Service `0x180D` / measurement `0x2A37`): scans for the service, connects, subscribes to notifications, parses 8/16-bit HR payloads. Disconnects re-scan automatically; gives up after 60 s.
- **`SimulatedSensors`** — developer-only. Random-walks toward phase targets so cue/history paths can be exercised **when explicitly toggled on** in Settings. It is **never an automatic fallback**: when off, a missing signal simply reads blank (`–`), so real workouts can never be silently polluted by fake readings.
- **`WearHeartRateSource`** — heart rate relayed from the paired **morkStep Wear** companion over the Wearable message layer (path `/morkstep/hr`); the watch reads HR via Wear Health Services and streams each beat-per-minute value on demand. Selected in Settings with the "Heart rate from Wear companion" switch (used instead of BLE when on).
- **`WearPaceSource`** — pedometer cadence relayed from the paired **morkStep Wear** companion (path `/morkstep/pace`); the watch reads `STEPS_PER_MINUTE` via Wear Health Services and streams each steps-per-minute value. Each relayed sample is the *latest value as emitted by Health Services* (typically ~1 s cadence, smoothed by the watch firmware) — an instant value, not a windowed estimate, so no latency dial applies on this path. A **`PhonePaceSource`** falls back to the phone's own step sensor (`TYPE_STEP_DETECTOR`, else `TYPE_STEP_COUNTER`) when the watch is absent or silent for 15 s (`FallbackPaceSource`); the watch wins again instantly when it resumes. Phone cadence is instead derived over a rolling `Constants.PACE_WINDOW_MS` window (default 5 s).

The simulated toggle lives in Settings ("Simulated sensors (debug)", default **off**) and is persisted in DataStore; the Workout screen shows a "no live hardware readings" banner while it is on. Runtime sensor permissions (fine location, BLE scan/connect) are requested from Settings; on Android 16 the app targets `compileSdk`/`targetSdk 36`.

**Workout graphics.** While a session runs, the Workout screen offers a phase-tracker with four views (chip-selected, session-persistent): **Off** hides it, **Bars** shows push/recovery segment progress, **Band** draws the Push Min–Recovery Max speed band with a live needle, and **Gauge** is a circular arc of segment progress with speed in the center. All three report how the live readings compare to the phase targets (Push Min floor during push, Recovery Max ceiling during recovery — for both speed and pace) with a green "On target" / red "Speed up / Slow down" caption.

**Dark mode.** Profile settings has a **Dark mode** switch: on forces the dark theme, off follows the system setting. Applied on Save.

**Backup.** Profile settings and the History screen offer Export/Import of profiles or workout history as versioned JSON files via the system file picker (SAF). Importing profiles restores the list; importing history merges rows. Both reassign any colliding id to a fresh one, so importing a backup over a partially-same device never replaces the existing active row or local workout.

**Baseline profile.** `data/Baseline.kt` owns the lifecycle: `baselineCalibrationProfile()` builds the 3-round calibration workout (preserving the existing baseline's id on re-create and carrying the active profile's vibration mode/intensity), `isBaselineProfile()` identifies it by name, and `updatedBaselineProfile()` re-derives the calibrated 30-minute profile after a workout — the recovery-speed average becomes the Recovery Max ceiling, the push-speed average the Push Min floor, the recovery/push pace averages the pace ceiling/floor, and the recovery/push HR averages the HR cap/floor, each clamped to the Config slider bounds (falls back to the previous targets if an average was not recorded). The re-derive runs in `MainViewModel.onFinished()` (a `.copy()` keeps every other setting); the UI then navigates to Settings and raises a one-shot "Baseline created" message.

**Wear companion.** `wear/` is a standalone Wear OS app (its own APK, `morkStep-wear-debug-0.8.1.apk`) that streams the watch's live heart rate **and pedometer pace** to the phone and buzzes when the phone relays a cue. Vibration gating happens on the phone — the active profile's vibration mode decides, and the optional **Vibrate watch** setting forwards permitted cues to the watch on path `/morkstep/vibrate`. The watch app also shows the live HR and pace values and its app version on-screen. While a phone workout is active, the watch mirrors the phase and offers the same **Off / Bars / Band / Gauge** graphics selector, plus a local **Pause/Resume** button (the engine pause lives on the phone) and a **Vibrate** switch that mutes watch haptics without stopping the phone's relay. HR and pace both stream over the Wearable message layer (`/morkstep/hr`, `/morkstep/pace`); the phone's `/morkstep/state` relay (now 47 bytes) also carries pace and the pace targets so the watch graphics render them.

**Health Connect** (`sensing/HealthConnectHr.kt`). The phone has no HR sensor, so when the Wear relay is off there is no real-time source. With the **Health Connect HR (after workout)** setting on (default), a finished workout is **backfilled** from Health Connect over the exact workout window: statistical aggregates give overall average / min / max, and per-minute buckets mapped through the engine's own `phaseAt` plan give per-phase push/recovery averages. Backfill is read-only, only fills values that are still null (a real-time BLE strap is never overwritten), and degrades cleanly to a no-op when Health Connect is unavailable, `READ_HEART_RATE` is not granted (the flow shows a rationale screen first, `RationaleActivity`), or no HR records exist for the window. It is "not perfect" by design — Health Connect only holds HR that a device or app wrote, samples can be sparse, and by default the read window is 30 days before the first grant (`PERMISSION_READ_HEALTH_DATA_HISTORY` extends it). Requires Health Connect present (Android 14+ built-in; on the API-36 AOSP emulator image it is absent, so the grant/backfill cannot be exercised there).

> **Future feature — write-back.** A natural extension is writing this app's sessions into Health Connect so the user's other health apps see them: insert an `ExerciseSessionRecord` (walking) plus per-sample `HeartRateRecord`s and aggregated stats (`WRITE_EXERCISE` / `WRITE_HEART_RATE` permissions) for each finished workout. Benefits: two-way data — morkStep both *reads* HR (backfill) and *shares* its own sessions; the History screen and Health Connect stay in sync. Costs/considerations: extra write permissions and an expanded rationale flow, correctness of session metadata (`startTime`/`endTime`, exercise type, duration), de-duplication if a session is imported back, and the aggregate-app-priority dedup rule (Health Connect dedups Activity/Sleep by user-set app priority, so a write-back session in another app's totals depends on that app's priority). Out of scope for the current read-only backfill.

### Storage — `data/`

- **`ConfigStore`** — the profile list (JSON via kotlinx.serialization) and the active profile id, persisted through Jetpack DataStore (Preferences): coroutine-native, atomic, lock-free. A deliberate round-trip rule: a zero-length warm-up/cool-down is real user intent ("none"), so raw seconds are persisted and re-read skips them, rather than silently resurrecting defaults.
- **`WorkoutHistory.kt`** — Room `@Entity`/`@Dao` (schema v1). Because the app has not shipped, only the first schema exists: any older database is discarded via `fallbackToDestructiveMigration` rather than migrated, and history is a relational, time-ordered list with a stable primary key. Completed workouts stream into the History screen via `Flow`.

### UI — `ui/`

Jetpack Compose + Material 3 with a bottom navigation shell (`Home`, `History`, `Settings`) and a separate full-screen `Workout` route. State comes from a single `MainViewModel` exposing `config` and `live` as `StateFlow`, collected with `collectAsStateWithLifecycle` so recomposition tracks the running session. No XML layouts.

## Tools & language servers used

**Build toolchain**

| Tool | Version | Why |
| ---- | ------- | --- |
| Gradle 9.4.0 (wrapper) | 9.4.0 | Pinned via the wrapper; runs on JDK 17–26 |
| Android Gradle Plugin | 9.0.1 | New DSL + built-in Kotlin (KGP 2.2.10); no `kotlin-android` plugin needed |
| Kotlin | 2.2.10 | Built-in Kotlin target; Compose compiler ships with Kotlin, so build and toolchain stay in lockstep |
| Jetpack Compose BOM | 2024.10.01 | Bundles Compose material/UI versions together |
| KSP | 2.2.10-2.0.2 | Room compile-time codegen (KSP2) |
| JDK | 21 (Android Studio JBR) | Gradle 9.4 accepts up to JDK 26, but KSP 2.2.10 can't read JDK 26 class files yet — build on the JBR 21 |
| Room | 2.7.2 | Type-safe SQLite DAO for workout history (KSP2/Kotlin 2.2-compatible) |
| DataStore Preferences | 1.0.0 | Coroutine-backed config persistence |
| kotlinx-serialization-json | 1.6.3 | Profile-list JSON persistence (with the Kotlin serialization plugin 2.2.10) |

**Language servers (LSP)**
| Server | Version | Notes |
| ------ | ------- | ----- |
| `kotlin-lsp` (JetBrains `intellij-server`) | 2026.2 EAP | Official Kotlin LSP, IntelliJ-based; resolves Gradle/AGP projects itself. Launched from `$PATH`; configured once globally in `~/.omp/agent/lsp.json`. See note below. |

> **LSP setup (committed, machine-independent).** The JetBrains Kotlin Language Server
> (`intellij-server`, on `$PATH`) does its own Gradle/AGP project resolution — no exported
> classpath, no `exportLspClasspath` task. This is a personal repo, so `.omp/` is **committed**
> (gitignored only for the transient `ui_home.xml` dump) and carries `lsp.json` — the
> `kotlin-lsp` wiring (`command: kotlin-lsp.cmd`, no machine paths) — plus `AGENTS.md`, the
> project context that directs the coding agent to use the LSP server for Kotlin code
> intelligence. The only machine-level pieces are the `kotlin-lsp.cmd` wrapper and its
> `intellij-server` install on `$PATH`, and a JDK ≤ the Gradle ceiling registered where IntelliJ
> finds JDKs (`~/.jdks/` on Windows — `JAVA_HOME` is *not* consulted); keep that copy in sync
> with the build JDK. Known quirk: the **first** diagnostics request after a reload can report
> cascading `Unresolved reference` false positives while the index warms — simply re-request;
> subsequent checks are clean.
> The Gradle build (`assembleDebug`, `testDebugUnitTest`) remains the authority on type
> errors.

## Upgrade caveats

- **LSP classpath task removed.** `:app:exportLspClasspath` is gone along with the generated
  `.classpath.absolute` and machine-specific `.omp/lsp.json`. The JetBrains Kotlin Language
  Server resolves Gradle/AGP projects itself; config is the machine-independent
  `~/.omp/agent/lsp.json` (global) plus the matching `.omp/lsp.json` in this repo.
- **Versioned APK artifact names.** AGP 9 removed `applicationVariants`/`BaseVariantOutputImpl`
  and the public per-output rename (`SingleArtifact.APK` is now a `ContainsMany` directory
  artifact), so both modules add a `versioned` post-packaging task (`rename<Variant>Apk`,
  finalizedBy `package<Variant>`) that copies the packaged APK to
  `morkStep-<versionName>-<buildType>.apk` / `morkStep-wear-<versionName>-<buildType>.apk`,
  preserving a `-unsigned` suffix for the unsigned release. The pristine unversioned
  artifact (`morkStep-debug.apk` etc.) is always kept in place too, and is restored from
  the versioned copy if it ever goes missing — every build ends with both files present.
  Bump `versionCode`/`versionName` together in the module's `build.gradle.kts` — and keep
  the `adb install` paths in this README's Build & run section in sync.

The harness also auto-loads built-in `pylsp` for Python regardless.

**Runtime environment**
- Emulator: `Medium_Phone_API_36.1` AVD driven via `adb`.
- OS: Windows 11 Pro x64.

`app/build.gradle.kts` pins versions to ones cached/available offline where possible to keep cold builds reproducible without a large dependency download.

## Test plan

`app/src/test/java/com/morkstep/engine/SessionEngineTest.kt` covers (39 tests):
- plan computation for ROUNDS / TIME length modes
- time→phase mapping, seconds-in-phase, and phase ordinal (fast=1, slow=2)
- plan-relative fast-segment counting (tick-cadence independent)
- progress fraction for finite modes; `null` for Adhoc; distance-based for DISTANCE
- engine behavior: phase advance + beeps + announcements, finish marking
- TIME mode finishes exactly at the target duration
- ADHOC runs until `endNow()` and cues every Nth completed push round
- quarter cues fire at 25% / 50% / 75%
- push warning cues: "Speed up" when speed is below the Push Min, pace below the Push Min spm, or HR below the Push Min bpm (incl. HR inside the band 120–150); over-max-HR counter (push) without a push cue
- pace (pedometer) warning cues: "Speed up" on push when pace is below the floor and "Slow down" on recovery when pace is above the ceiling; no-signal pace (0 spm) never cues; exactly one shared cue when only pace is off
- haptic vibration cues: `TRANSITION` on phase entry and finish; `GUIDANCE` on quarter and warning cues
- recovery warning cues: "Slow down" when speed is above the Recovery Max, pace above the Recovery Max spm, or HR above the Recovery Max bpm (incl. HR inside the band 120–150)
- warning-repeat threshold in seconds shared by push and recovery
- single shared cue (one speech + one vibration) when multiple readings trigger
- no-signal suppression: 0 BPM / speed ≤ 1.5 mph / 0 spm never cue, on push and recovery
- first warning cue after each phase transition suppressed (FAST and SLOW); phase-change announcements take precedence over both warning cues and workout-length cues (quarter / ADHOC every-Nth-push) on the entry tick — the length cue fires on the following tick
- distance accumulation from speed (mph → miles)
- per-phase average accumulation: push/recovery/overall speed, pace & HR bucketed from 1 Hz samples

Run with `./gradlew testDebugUnitTest`.