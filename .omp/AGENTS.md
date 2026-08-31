# morkStep — Kotlin LSP usage

- Kotlin code intelligence runs on `kotlin-lsp` = JetBrains `intellij-server` (2026.2 EAP),
  launched via the `kotlin-lsp.cmd` wrapper on PATH. Configured in `~/.omp/agent/lsp.json`
  (global) and `.omp/lsp.json` (project).
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