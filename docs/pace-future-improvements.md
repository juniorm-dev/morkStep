# Pace sensor — future improvements (recorded 2026-09-06, findings updated 2026-09-10)

Do NOT implement without explicit approval. These are design changes, not bug fixes.

**Scope.** §1–§3 are `PhonePaceSource` artifacts. While a paired Wear companion is streaming
`STEPS_PER_MINUTE`, that watch value is what the engine sees — `FallbackPaceSource` passes a
phone sample through only after 15 s of watch silence — so the phone estimators are bypassed
entirely and §1–§3 do not apply; they return with the phone path if the watch stream stalls.
§4 is about the merged stream itself and applies on both paths (and specifically to a stalled
watch).

**Status (0.13.3):** §1 open · §2 open · §3 open for mid-phase samples; its
transition-adjacent case is mitigated engine-side by the
`Constants.PHASE_TRANSITION_SETTLE_MS` warning mute (README → "Level out phase
transitions") · §4 open. No other item implemented.

## 1. Stop floor for displayed pace (raw-rate deadband)

**Log evidence** (`tmp/morkStep-debug-1788695494536.txt`):
```
12:49:58 counter -> 35 spm (+16 steps, total 247)
12:50:20 counter -> 39 spm (+16 steps, total 263)   # 0.5*35 + 0.5*(16*60k/22s = 43.6)
12:50:53 counter -> 21 spm (+2 steps, total 265)    # 0.5*39 + 0.5*(2*60k/33s = 3.6)
```
- The smoothed chain above the raw rate reproduces exactly; the pipeline is honest.
- The 12:50:53 value reads 21 spm from **2 steps in 33 s** — the user was standing
  (GPS agrees: 0.3–0.8 mph). No human walk cadence is below ~40; sub-20 is
  sensor artifact / standing.
- GPS blip 12:50:13 `speed 5.3 mph` is GPS noise (a 5 mph jog is ~170 spm; the
  counter right after shows 43) — separate, not part of this improvement.

**Proposed change** (`PhonePaceSource.kt`, `PaceCounterEstimator.sample`):
- Reject-samples whose raw interval rate is below a stop floor (e.g. 20–30 spm
  from `dCount * 60_000 / dt`) — treat as "not walking": do NOT move `lastSpm`
  toward it; keep emitting the previous cadence, and let the value decay to
  `null` (dash) after a standing period instead of showing sub-20 fake pace.
- Mirrors the detector path's span floor (`PACE_ESTIMATOR_MIN_SPAN_MS`): that
  floor holds sub-1.5 s gaps; the counter path has no equivalent floor for
  the *opposite* extreme (very long gaps).
- Display contract: `MIN_VALID_PACE_SPM = 10` currently gates cues only; the
  LiveState/UI shows any non-null value as "pace". A standing stall should read
  `–`, not `21 spm`.
- Keep the timing/baseline advance: a rejected slow sample must still advance
  `lastCount`/`lastCountAt` so a genuine restart is detected on the next valid
  interval (only the *rate* is deadbanded, not the counter position).

**Not changed yet** — recorded only. Same estimator as §3: a change to its blend weighting
must keep this display deadband in mind (a stop floor is a *display* contract;
`MIN_VALID_PACE_SPM = 10` already gates cues).

## 2. Optional: GPS speed sanity floor (separate from pace)

- The 5.3 mph single-sample blip at 12:50:13 while the user is otherwise
  0.3–1.8 mph is classic fused-location noise. If speed cues ever feel wrong,
  consider a max single-sample delta or a median filter in `GpsSpeedSource`
  (mirrors `MIN_VALID_SPEED_MPH`'s low-end gate with a high-end clamp).
- Lower priority; pace is the reported concern.

## 3. Counter rate overshoot on batched step-counter delivery (recorded 2026-09-10)

**Log evidence** (`tmp/morkStep-debug-1789044514060.txt`):
```
13:48:25 [pace-phone] step counter -> 78 spm (+1 steps, total 13240)
13:48:26 [pace-phone] step counter -> 130 spm (+3 steps, total 13243)
13:48:27 [warncue] Slow down: pace 130 > 110
13:48:27 [pace-phone] step counter -> 95 spm (+1 steps, total 13244)
```
- The 50/50 chain reproduces exactly: `(78 + 3·60_000/1_000) / 2 = 129` → the logged 130.
- Four steps across those two seconds is ~120 spm on average, so the smoothed value
  overshot the true cadence by ~10 spm for exactly one tick — enough to cross a 110 spm
  recovery cap from a walk that never exceeded it. The warning vanished on the next
  sample (95).
- Cause: `PaceCounterEstimator` divides the delta by the *delivery interval* and blends
  each sample 50/50 regardless of `dt`, so a short batched interval (the 500 ms floor up
  to ~1.5 s) carries the same weight as a 30 s one, while `raw = dCount · 60_000 / dt` is
  very noisy there — at a true 95 spm, `P(dCount = 3)` in a 1 s interval is ≈ 0.2, i.e.
  raw 180 on roughly one second in five.
- Proposed (not implemented): weight each sample by `dt / PACE_WINDOW_MS` instead of a
  fixed 0.5, and/or raise the minimum accepted interval from 500 ms toward
  `PACE_ESTIMATOR_MIN_SPAN_MS`. Tradeoff: the counter path's "reacts within one step"
  responsiveness slides toward the detector's windowed lag, which is the documented
  reason the counter is authoritative while it is alive.
- Transition-adjacent instances of this spike are already mitigated (0.13.3) engine-side by
  the warning settle window — `Constants.PHASE_TRANSITION_SETTLE_MS`, armed when the profile
  levels out transitions; the live `state.pace` display still shows them. Mid-phase
  instances are not mitigated at all, and neither is the display.
- With a live Wear pace relay the item disappears: the phone estimator never feeds the merge
  (see Scope above).

**Not changed yet** — recorded only. The recommendation above stands as the implementation
proposal (time-weighting and/or a longer minimum interval) if phone-only workouts should
stop crossing a ceiling for a single mid-phase sample.

## 4. No staleness expiry on the merged pace (recorded 2026-09-10)

**Finding (code, both paths).** `FallbackPaceSource` only ever writes a non-null phone or
watch sample into its `StateFlow` — nothing clears the value on silence, and neither source
emits `null` when its own stream stops (`PhonePaceSource` nulls only in `stop()`;
`WearPaceSource` logs and ignores a `0`/absent sample without emitting). `StateFlow` also
does not re-emit an unchanged value, so the engine keeps reading the same number.

**Consequence.** A stalled watch relay (watch dozing or off-wrist, message-layer hiccup,
watch app killed) leaves its last `STEPS_PER_MINUTE` driving `LiveState.pace`, the phase
averages, and the warning verdict until the phone fallback takes over 15 s later — and the
phone path has the same property while no steps arrive (a real standstill emits no counter
samples). A value frozen above the recovery ceiling therefore repeats "Slow down" every
`warningThresholdSec` instead of reading blank, and the on-screen log will show no fresh
`[pace-wear]` / `[pace-phone]` line to explain it.

**Proposed (not implemented).** Give the merge a freshness deadline: stamp each accepted
sample and publish `null` once the newest accepted sample is older than a threshold (e.g.
`PACE_COUNTER_PREFERRED_MS` for the phone path, and a watch-specific bound), so the engine
sees "no pace signal" rather than a stale one. Needs care not to blank the display during
slow walking, where the counter legitimately delivers few samples.

**Not changed yet** — recorded only.
