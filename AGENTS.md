# Repository Guidelines

morkStep — a modern Android (Wear OS) fitness app for **Interval Walking Training (IWT)**.
IWT alternates brisk "push" intervals with slower "recovery" intervals. morkStep guides a
session through a configurable plan, gives live speed and heart-rate feedback, plays audio
cues when you drift from your speed/HR targets, and records every completed workout to a
history log.

Built with Kotlin coroutines (`kotlinx.coroutines`) + `androidx.compose` (Material 3). No
XML views, no Frontend. Two independent Gradle Android application modules.

> **LSP editing gate**: Kotlin editing in this repo is gated on the JetBrains language
> server. See `.omp/AGENTS.md` — it contains the MANDATORY pre-edit workflow
> (`lsp status` → `lsp references` → `lsp rename`/`code_actions` → `lsp diagnostics` →
> `gradlew`), the JDK registry requirement, and the versioned-APK caveat.

---

## Architecture & Data Flow

Two independent APKs communicate over Google **Wearable `MessageClient`** (async
send/await + `onMessageReceived` listeners). There is no shared library and no
cross-module Gradle dependency; the cross-device protocol constants are manually kept in
sync in both modules (`wear/src/main/java/com/morkstep/wear/Constants.kt` and the phone's
`MainViewModel`).

**Phone** (`app/`, `com.morkstep`) — the full app.
```
MainActivity → MorkApp (Scaffold + bottom nav) → Home/Config/History/Workout screens
                                       │ read StateFlows from
                                 MainViewModel  ◀── owns EVERYTHING
   config in:  ConfigStore (DataStore Flows) → rebuildSources()/setupEngine()
   sensors:    SpeedSource/HeartRateSource StateFlow<Float?/Int?> hot streams
                 • GpsSpeedSource  (Play Services Fused Location, 1 Hz)
                 • BleHeartRateSource (BLE strap HR service 0x180D/0x2A37)
                 • WearHeartRateSource (HR relayed from paired watch, /morkstep/hr)
                 • SimulatedSensors (dev toggle, seeded Random(42), phase-driven)
   engine:     SessionEngine(profile, speedSrc, hrSrc, SpeakerSink)
                 • start() combines speed+hr into LiveState StateFlow
                 • MainViewModel.tickerJob: viewModelScope.launch { delay(1000); engine.tick() }
   output:     CueSink → CueSpeaker (TTS + beeps) + phone Vibrator haptics + watch haptics
   finish:     onFinished() → Room WorkoutEntity row → Health-Connect HR backfill → Baseline re-derive
   keepalive:  WorkoutService foreground service (wake lock + notification)
```
Sensors are hardware-callback-driven on the main Looper (BLE `BluetoothGattCallback`, GPS
`LocationCallback`, Health-Connect / Health Services `MeasureCallback`); all of them funnel
into `StateFlow`s. All IO and state mutation runs in `viewModelScope.launch { }` coroutines.

**Watch** (`wear/`, `com.morkstep.wear`, standalone) — relay/driver companion.
`HrRelay` pushes watch HR (`/morkstep/hr`) to the phone; `StateRelay` decodes
`/morkstep/state` (35-byte big-endian payload) and renders the phase tracker;
`VibrateRelay` buzzes on `/morkstep/vibrate`; a Pause button sends `/morkstep/pause` back
to the phone where `wearPauseListener` calls `engine.pause()/resume()`. Vibration gating
happens on the phone.

---

## Key Directories

| Path | Purpose |
|---|---|
| `app/src/main/java/com/morkstep/` | Phone app, grouped by responsibility |
| `…/engine/` | `SessionEngine` — pure IWT state machine + cues; `CueSink`/`CueVibration` interfaces |
| `…/sensing/` | `SpeedSource` / `HeartRateSource` interfaces + GPS/BLE/Wear/Simulated/Health-Connect providers |
| `…/data/` | `ConfigStore` (DataStore), `WorkoutHistory` (Room DB), `Config.kt` models/enums, `Baseline.kt`, `Transfer.kt` (JSON backup) |
| `…/ui/` | Compose screens + `MainViewModel` (state hub) |
| `…/audio/` | `CueSpeaker` (TTS + tones) |
| `app/src/test/` `app/src/androidTest/` | Phone unit tests / instrumented UI tests |
| `wear/src/main/java/com/morkstep/wear/` | Watch app: `MainActivity`, `WearWorkoutGraphics`, `Constants.kt` (protocol) |
| `wear/src/test/` `wear/src/androidTest/` | Watch unit / instrumented tests |
| `.omp/` | LSP config + the Kotlin editing-gate `AGENTS.md` (committed) |

---

## Development Commands

Build/test with the wrapper (JDK on `compileOptions` is JVM 17; Gradle is pinned 9.4.0).

```bash
# Build compile+runtime checks (authority on type errors)
gradlew assembleDebug

# JVM unit tests (fast, no device)
gradlew testDebugUnitTest          # or :app:testDebugUnitTest / :wear:…, :testReleaseUnitTest

# Instrumented UI/Compose tests — NOT in the default lifecycle; need device/emulator
gradlew :app:connectedDebugAndroidTest
gradlew :wear:connectedDebugAndroidTest

# Package both APKs
gradlew :app:assembleRelease :wear:assembleRelease
# help for any task
gradlew :app:help
```

Wear release minification is enabled (R8 strips Guava/health-services); app release
minification is disabled. Release packaging also runs `VerifyVersionTag` (see below).
`release.bat` orchestrates a full release build + signing at the root.

---

## Code Conventions & Common Patterns

- **Async model**: `kotlinx.coroutines`. UI is `@Composable` functions rendering
  `StateFlow`s via `collectAsStateWithLifecycle()`; all side-effecting IO runs in
  `viewModelScope.launch { … }` coroutines (`delay`, `suspend` IO). Sensors expose
  `StateFlow<Float?>`/`StateFlow<Int?>` hot streams; hardware/permission callbacks funnel
  into them.
- **Engine**: `SessionEngine` computes phase schedule *purely* from elapsed time
  (`phaseAt(t, profile, …)`, `planFor(p)`, `progressAt(…)` are `internal fun`s — unit-testable).
  It is advanced by a manual 1 Hz ticker, not a timer. Pause excludes paused wall-clock.
  Transient phase transitions are detected by `phaseAt().phase != lastPhase`, not an enum.
- **Sensor contract**: anything providing speed implements `SpeedSource { val speed: StateFlow<Float?> }`;
  pace implements `PaceSource { val pace: StateFlow<Int?> }` (steps/min, from the Wear pedometer);
  HR implements `HeartRateSource { val hr: StateFlow<Int?> }` (in `sensing/Sensors.kt`).
- **Output contract**: engine emits into `CueSink` (`beep()` / `speak(text)` / `vibrate(kind)`);
  `SpeakerSink` (in `MainViewModel`) bridges to `CueSpeaker` + haptics. Haptics are gated by
  `VibrationMode` (`OFF | PHASE_CHANGE | ALL`).
- **Persistence**: Room SQLite for history (reactive `Flow` observers +
  suspend `@Insert`/`@Update`); Preferences DataStore (`context.dataStore.edit {}`) for config;
  SAF URI streams for JSON backup/restore. Config/entities are `@Serializable` data classes
  (kotlinx.serialization).
- **Naming**: tests `camelCase_describesBehavior`; parse helpers and pure functions are
  top-level `fun`s; enums `UPPER_SNAKE`. Background coroutines use `…Job` /
  `viewModelScope.launch { … }`.
- **Known historical naming**: `speedCeilingMph` caps *recovery* ("Slow down"),
  `speedFloorMph` floors *push* ("Speed up"). Pace mirrors these as `paceCeilingSpm` /
  `paceFloorSpm`. Heart rate mirrors the same phase roles but is named by target
  instead: `hrPushMin` (push keeps HR at/above, default 150), `hrRecoveryMax`
  (recovery keeps HR at/below, default 120 — lower than the push min by design).
  `Consume` for signal validity floors:
  `MIN_VALID_HR_BPM`, `MIN_VALID_SPEED_MPH`, `MIN_VALID_PACE_SPM`.
- **Versioning**: per-module `versionCode`/`versionName` in each `build.gradle.kts`;
  releases tagged `v<versionName>`. `VerifyVersionTag` (app, release variants) fails
  `packageRelease` if the git tag already exists — bump version before release. `VersionApk`
  (both modules, via `androidComponents.onVariants` `finalizedBy` on `package<BuildType>`)
  copies APKs to `morkStep-<version>-<buildType>.apk` / `morkStep-wear-…`, preserving
  `-unsigned`/`-signed` suffixes.

---

## Important Files

| File | Why it matters |
|---|---|
| `app/src/main/java/com/morkstep/MorkApplication.kt` | App entry; builds `AppContainer` (Room DB + ConfigStore) |
| `app/src/main/java/com/morkstep/ui/MainViewModel.kt` | Central state hub + engine/sensor/workout controller; owns all StateFlows & ticker jobs |
| `app/src/main/java/com/morkstep/ui/MorkApp.kt` | Root composable; nav routes + permission/document launchers |
| `app/src/main/java/com/morkstep/engine/SessionEngine.kt` | IWT state machine; `CueSink`/`CueVibration`/`SessionClock` |
| `app/src/main/java/com/morkstep/data/Config.kt` | Domain models + enums (`WorkoutProfile`, `PhaseType`, `WorkoutLength`, `VibrationMode`, `DarkMode`) |
| `app/src/main/java/com/morkstep/data/WorkoutHistory.kt` | Room DB (v4) + `WorkoutDao` |
| `wear/src/main/java/com/morkstep/wear/Constants.kt` | Cross-device protocol constants — MUST stay in sync with phone |
| `wear/src/main/java/com/morkstep/wear/WearWorkoutGraphics.kt` | `decodeWearSessionState`, graphics panel |
| `app/src/main/AndroidManifest.xml` / `wear/…` | Permissions + activity/service wiring |
| `app/build.gradle.kts` / `wear/build.gradle.kts` | Versions, deps, `VersionApk`/`VerifyVersionTag` tasks |

---

## Runtime / Tooling Preferences

- **Kotlin 2.2.10** (AGP 9 built-in; `kotlin-android` is NOT applied) with
  `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and KSP (Room compiler).
- **JDK**: no toolchain with clean JVM-17 bytecode `source/targetCompatibility` + Kotlin
  `jvmTarget` = JVM_17. Gradle 9.4 / AGP 9.0.1.
- **Android SDK**: `compileSdk`/`targetSdk` 36; `sdk.dir` is machine-local in
  `local.properties` (gitignored) — not portable.
- **IDE/LSP**: `kotlin-lsp` = JetBrains `intellij-server`, launched via `kotlin-lsp.cmd`
  on PATH, configured in `.omp/lsp.json` + `~/.omp/agent/lsp.json`. Cold-start import of the
  Gradle workspace needs a registered JDK ≤ Gradle's ceiling (`~/.jdks/jbr-21` — `JAVA_HOME`
  is not consulted); cross-file features only work once the server reports `ready`.
- **Package manager**: Gradle wrapper (`gradlew`/`gradlew.bat`); no version catalog —
  dependency versions are inline in each `build.gradle.kts`. Repos: `google()` +
  `mavenCentral()`, `FAIL_ON_PROJECT_REPOS`.
- **Signing**: app release reads gitignored `keystore.properties` + `release.keystore`;
  incomplete/missing → release stays unsigned (never hard-fails). Wear has no signing config.
- No observability/analytics dependencies.

---

## Testing & QA

Two tiers; **`src/test` = pure JVM unit tests (fast, no device)** and
**`src/androidTest` = instrumented Compose tests (real device/emulator, run on demand)**.
`androidTest` is explicitly excluded from the default assemble/test lifecycle — always run
it via `:app/:wear:connectedDebugAndroidTest`.

- **Frameworks**: JUnit 4 (`junit:4.13.2`). Instrumented: `androidx.test` runner 1.6.2,
  `ext:junit` 1.2.1, Compose `ui-test-junit4`, espresso-core 3.7.0 (pinned override — 3.5.1
  crashes on Android 15/16). No Kotest/Mockito/Robolectric.
- **Unit test patterns**: pure helpers and the engine are tested directly with fakes —
  `FakeClock (SessionClock)`, `FakeSensors (SpeedSource+HeartRateSource via MutableStateFlow)`,
  `RecordingCue (CueSink)`. This is the idiomatic way to test logic without Android APIs.
- **Instrumented patterns**: `createAndroidComposeRule<MainActivity>` + Compose
  `onNodeWithText`/`performClick`/`waitUntil`; `testOptions animationsDisabled=true`;
  `clearPackageData=true` so DataStore starts fresh (one seeded `Default` profile).
- **Coverage today**: `engine/` (SessionEngine) is exhaustively covered; pure parsers
  (BLE HR, Wear 35-byte decode) and `Transfer`/`Baseline` logic are covered. **Gaps**:
  `audio/CueSpeaker`, `WorkoutService`, `MainViewModel`, `data/Store` +
  `WorkoutHistory`, `GpsSpeedSource`, `WearHeartRateSource`, and the live-session phone UI
  (`WorkoutScreen`, `WorkoutPhasePanel`) have no direct tests. Follow the fake-based unit
  pattern for logic; use instrumented tests for real Compose surface behavior that needs a
  device.