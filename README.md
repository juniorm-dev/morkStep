# morkStep

Modern Android fitness app for **Interval Walking Training** (IWT / "Japanese walking").

IWT alternates brisk "push" intervals with slower "recovery" intervals. morkStep guides a session through a configurable plan, gives live pace and heart-rate feedback, plays audio cues when you drift out of your target bands, and records every completed workout to a history log.

## Features

- **Configurable intervals** — warm-up length, push interval, recovery interval, number of push rounds, cool-down length.
- **Pace band** — a walker-configurable *ceiling* and *floor* (km/h). Push intervals target the band.
- **Heart-rate band** — a *ceiling* and *floor* (bpm) that the push phase aims to stay within.
- **Audio cues** — per-phase spoken announcements plus beeps on transitions, and spoken guidance when pace drops below the floor or HR rises above the ceiling during push.
- **Workout history** — every completed session is auto-saved (date, duration, push count, average pace, average HR, seconds over ceiling) and listed in a History screen.

## Requirements

| Tool | Version |
| ---- | ------- |
| Android Studio JBR (JDK) | 21 |
| Android SDK | compileSdk 35, minSdk 26 |
| Gradle | 8.11.1 (wrapper) |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.21 (Compose compiler plugin 2.0.21) |

`local.properties` must set `sdk.dir` to your SDK path.

## Build & run

```bash
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # run unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew` is the wrapper (Gradle 8.11.1). Set `JAVA_HOME` to your JDK 21 if it is not already on PATH.

---

## Architecture

All code lives under `app/src/main/java/com/morkstep/`, organised by responsibility.

### Module map

| Package | Responsibility |
| ------- | -------------- |
| `engine/` | Pure interval session state machine and cue logic (no Android deps except a clock) |
| `sensing/` | Pace & heart-rate *source* abstractions + a simulated implementation |
| `audio/` | TTS + tone cues |
| `data/` | Domain models, config persistence (DataStore), workout history (Room) |
| `ui/` | Jetpack Compose screens + ViewModel wiring |

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

The core is a **pure, deterministic state machine** that maps elapsed wall-clock seconds to a position in the configured segment plan. It is deliberately *push-only*: its owner drives time forward with `tick()` and it reads instantaneous pace/HR, then writes an immutable `LiveState` snapshot plus cues.

The pure segment-mapping helpers (`segmentIndexFor`, `secondsInSegment`, `completedFastSegments`) have **no Android dependencies**, so they are unit-tested on the JVM without instrumentation. Wall-clock is behind a `SessionClock` interface so tests can fake time and assert exact phase transitions.

**Why this shape:**

- **Time is wall-clock, not tick-counted.** The ticker runs at ~1 Hz only to refresh the UI. Elapsed time is recomputed from `SystemClock.elapsedRealtime()`, so a dropped or late tick never corrupts the running total — `completedFastSegments` is derived from the plan, not from counting observed transitions, so progress is accurate even if ticks are skipped.
- **Pull sensors, push state.** The engine observes pace/HR via `StateFlow` and emits aggregated state downstream. The UI never drives the engine's truth; it only renders `LiveState`.

### Sensing — `sensing/Sensors.kt`

Pace and HR are defined as two narrow interfaces (`PaceSource`, `HeartRateSource`) returning `StateFlow`. The app ships **`SimulatedSensors`**, which random-walk toward phase-appropriate targets (e.g. ~6.2 km/h / ~138 bpm during push). This makes the cue path and history work on the Android emulator, which has neither GPS nor a BLE heart-rate strap.

A real implementation (Fused Location for pace, a Bluetooth LE health-service client for HR) can be dropped in behind the same interfaces with no change to the engine or UI.

### Storage — `data/`

- **`ConfigStore`** — `IntervalConfig` persisted through Jetpack DataStore (Preferences). Chosen for JSON-free, coroutine-native, atomic reads. A deliberate round-trip rule: a zero-length warm-up/cool-down is real user intent ("none"), so raw seconds are persisted and re-read skips them, rather than silently resurrecting defaults.
- **`WorkoutHistory.kt`** — Room `@Entity`/`@Dao`. History is a relational, time-ordered list with a stable primary key, which is Room's sweet spot; completed workouts streaming into the History screen via `Flow`.

### UI — `ui/`

Jetpack Compose + Material 3 with a bottom navigation shell (`Home`, `History`, `Settings`) and a separate full-screen `Workout` route. State comes from a single `MainViewModel` exposing `config` and `live` as `StateFlow`, collected with `collectAsStateWithLifecycle` so recomposition tracks the running session. No XML layouts.

## Tools & language servers used

**Build toolchain**

| Tool | Version | Why |
| ---- | ------- | --- |
| Gradle 8.11.1 (wrapper) | 8.11.1 | Chili supports AGP 8.7.2; pinned via the wrapper for reproducible builds |
| Android Gradle Plugin | 8.7.2 | Stable release compatible with Gradle 8.11.1 and Compose BOM 2024.10.01 |
| Kotlin | 2.0.21 | Compose compiler now ships with Kotlin (the `compose` compiler Gradle plugin), so build and toolchain stay in lockstep |
| Jetpack Compose BOM | 2024.10.01 | Bundles Compose material/UI versions together |
| KSP | 2.0.21-1.0.28 | Room compile-time codegen |
| JDK | 21 (Android Studio JBR) | Gradle 8.x + AGP 8.x require JDK 17+; the Studio-bundled JBR is available offline |
| Room | 2.6.1 | Type-safe SQLite DAO for workout history |
| DataStore Preferences | 1.0.0 | Coroutine-backed config persistence |

**Language servers (LSP)**
| Server | Version | Notes |
| ------ | ------- | ----- |
| `kotlin-lsp` (fwcd/kotlin-language-server) | 1.3.13 | Served Kotlin `.kt`/`.kts` — navigation, symbols, refactors, diagnostics. Downloaded to `.tools/`, launched with the Android Studio JBR 21 via `<project>/.omp/lsp.json`. Reports expectations (outer unresolved Android/Compose references until Gradle classpath is loaded) but is live and issuing diagnostics for the source tree. |

The harness also auto-loads built-in `pylsp` for Python regardless.

**Runtime environment**
- Emulator: `Medium_Phone_API_36.1` AVD driven via `adb`.
- OS: Windows 11 Pro x64.

`app/build.gradle.kts` pins versions to ones cached/available offline where possible to keep cold builds reproducible without a large dependency download.

## Test plan

`app/src/test/java/com/morkstep/engine/SessionEngineTest.kt` covers:
- time→segment mapping and within-segment counts
- phase advance + transition beep + phase announcement
- finish marking and deterministic fast-segment counting
- pace-below-floor cue
- HR-above-ceiling cue + over-ceiling counter
- cooldown announcement
- default IWT plan shape (3-min warm-up; 5×3/3; 2-min cool-down)

Run with `./gradlew testDebugUnitTest`.