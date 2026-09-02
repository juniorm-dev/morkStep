# morkStep — Kotlin LSP usage

- Kotlin code intelligence runs on `kotlin-lsp` = JetBrains `intellij-server` (2026.2 EAP),
  launched via the `kotlin-lsp.cmd` wrapper on PATH. Configured in `~/.omp/agent/lsp.json`
  (global) and `.omp/lsp.json` (project).

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