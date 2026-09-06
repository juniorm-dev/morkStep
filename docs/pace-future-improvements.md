# Pace sensor — future improvements (recorded 2026-09-06)

Do NOT implement without explicit approval. These are design changes, not bug fixes.

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

**Not changed yet** — recorded only.

## 2. Optional: GPS speed sanity floor (separate from pace)

- The 5.3 mph single-sample blip at 12:50:13 while the user is otherwise
  0.3–1.8 mph is classic fused-location noise. If speed cues ever feel wrong,
  consider a max single-sample delta or a median filter in `GpsSpeedSource`
  (mirrors `MIN_VALID_SPEED_MPH`'s low-end gate with a high-end clamp).
- Lower priority; pace is the reported concern.