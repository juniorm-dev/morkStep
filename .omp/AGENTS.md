# morkStep — Kotlin LSP usage

## Git workflow

- **Never commit directly to `main`.** Any auto-commit goes on a feature branch
  (e.g. `fix/<short-description>`), pushed to origin, then merged into `main` (or handed
  off for review first). Exception: the user explicitly asks to commit/tag on `main`
  (e.g. running `release.bat`, which tags and pushes `main` itself).

This repo uses a **Kotlin-LSP-first development approach**. Kotlin code intelligence runs on
`kotlin-lsp` = JetBrains `intellij-server` (2026.3 EAP, ILS-263.4421.0), launched via the
`kotlin-lsp.cmd` wrapper on PATH. Configured in `~/.omp/agent/lsp.json` (global) and
`.omp/lsp.json` (project).

## Work is structured LSP-first — manual edits are the last resort

Plan and execute every Kotlin change so the LSP server does as much of the editing as
possible, not as a check performed after hand-editing:

- **Choose edit orders that keep the server usable.** Rename/refactor first (while the
  symbol graph is intact) via `lsp rename` / `lsp rename_file` / `lsp code_actions`
  (imports, quick-fixes, intentions); only then make additive changes (new params, new
  files) that LSP cannot invent. Do NOT hand-rename a declaration and then leave call sites
  broken, because the server cannot rename a symbol whose usages are already unresolved.
- **Reach for the text `edit` tool only when LSP cannot express the change**: brand-new
  files, additive parameters/plumbing, large rewrites, moving members across types. Even
  then, run `lsp references` first so no callsite is dropped, and follow the edit
  immediately with `lsp diagnostics`.
- **Batch imports through the server.** After any text-edit churn, restore import hygiene
  with `lsp code_actions` → "Organize Imports" on the affected files rather than editing
  import lines by hand.
- The spirit: the server is the primary editing instrument; text tools fill the gaps LSP
  cannot cover, then the server re-syncs and verifies.

## MANDATORY editing gate (do not skip)

Before ANY Kotlin edit in this repo, in this order:

1. `lsp status` — if `kotlin-lsp` is `ready`, proceed; if warming, wait for `ready`
   (cold start is seconds; up to 600 s for the Gradle import) rather than falling back
   to text search.
2. For every symbol your edit touches (rename, signature, field, call, or behavior):
   run `lsp references` (and `lsp definition` / `lsp implementation` when relevant) to
   enumerate every callsite BEFORE changing anything.
3. Edit — prefer `lsp rename`/`lsp rename_file`/`lsp code_actions` for symbol moves,
   renames, imports, and server-known refactors. Use the text `edit` tool only for new
   files, large rewrites, or moves LSP cannot express — and even then only after step 2.
4. After editing: `lsp diagnostics` on the changed files, then `gradlew assembleDebug` /
   `testDebugUnitTest` as the authority on type errors.

NEVER use `grep`/`read` to find or trace a Kotlin symbol that the server can resolve.
Treat LSP as a hard gate on editing — the same way the build is a hard gate on yielding.
- Use the `lsp` tool for diagnostics / definition / type-definition / implementation /
  references / rename / symbols / hover / code actions; completion via `lsp request`
  (`textDocument/completion`).

- The server imports the Gradle 9.4 / AGP 9.0.1 workspace itself. For that import it needs
  a JDK ≤ Gradle's ceiling registered in IntelliJ's JDK registry (`~/.jdks/jbr-21` —
  `JAVA_HOME` is NOT consulted). Cross-file features (references, implementation) only work
  after the import has synced; `lsp status` shows when the server is `ready`.
- Cold-start diagnostics can be noisy (`Unresolved reference` while the index warms) —
  re-request before judging. `gradlew assembleDebug` / `testDebugUnitTest` is the authority
  on type errors.
- APK outputs are versioned by post-packaging rename tasks: app →
  `morkStep-<versionName>-<buildType>.apk`, wear → `morkStep-wear-<versionName>-<buildType>.apk`
  (see README "Upgrade caveats").

## Code conventions

- **Tuning values live in `app/src/main/java/com/morkstep/Constants.kt`, not inline.**
  Any behavior-adjustable number — thresholds, offsets, windows, intervals, seed
  deltas — goes in `Constants` under the matching `// ---- section ----` region with a
  doc comment naming the behavior it tunes, and the calling code references the
  constant by name. Never hardcode such a value as a bare literal in engine/UI/sensor
  logic. (Example: the phase-average seed offset `PHASE_AVG_SEED_OFFSET`, the
  `MIN_VALID_*` signal floors, `PACE_WINDOW_MS`.)
- **Per-profile, user-facing toggles belong on `WorkoutProfile`** (`data/Config.kt`) with
  a doc comment quoting the Settings label, wired through the Settings screen's
  per-profile `rememberSaveable` state + `Save profile` copy. This is the established
  pattern (`audioMode`, `vibrationMode`, `resetPhaseAverages`).
- When introducing a new behavior knob, check `Constants.kt` / `WorkoutProfile` first
  for an existing parameter that should cover it before adding a new one.