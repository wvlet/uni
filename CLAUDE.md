# CLAUDE.md

## Project Overview

**Uni** is an essential Scala utilities, refined for Scala 3 with minimal dependencies.

## Modules

- **uni-core**: Pure-Scala essential libraries shared with uni-test
- **uni**: Main library collection (logging, DI, JSON/MessagePack, RPC/HTTP)
- **uni-test**: Unit testing framework

The build runs on **sbt 2.x** (Scala 3 metabuild). Cross-platform — JVM, Scala.js, Scala Native — via uni's own [`sbt-uni-crossproject`](sbt-uni-crossproject/) plugin (`crossProject`, `CrossType.Pure`), which replaced the unported portable-scala plugins. Platform-specific code in `.jvm`, `.js`, `.native` folders.

Note (sbt 2.x): use `%%` (not the old `%%%`) for Scala.js/Native deps, and wrap a non-serializable `Test / jsEnv` in `Def.uncached(...)`.

## Commands

```bash
./sbt compile                              # Compile all
./sbt test                                 # Test all
./sbt coreJVM/test                         # Test specific module
./sbt "coreJVM/testOnly *DesignTest"       # Test specific class
./sbt "coreJVM/testOnly * -- -l debug"     # With debug logging
./sbt scalafmtAll                          # Format (CI checks this)
pnpm docs:dev                              # Start docs server (http://localhost:5173)
```

## Testing (UniTest)

Avoid mocks. Use `shouldBe`, `shouldNotBe`, `shouldContain`, `shouldMatch`.

```scala
// Comparison operators
(value >= 1) shouldBe true  // NOT: should be >= 1

// Type checking
result shouldMatch { case x: ExpectedType => }  // NOT: .asInstanceOf[X]
```

See `.github/instructions/unitest.instructions.md` for more.

## Coding Style

- Scala 3 syntax only
- Omit `new`: `StringBuilder()` not `new StringBuilder()`
- String interpolation: always use `${...}` with brackets
- Avoid `Try[A]` return types
- Config classes: `withXXX(...)` for all fields, `noXXX()` for optional fields
- uni: minimal dependencies only

## Git Workflow

Save plan documents to plans/YYYY-MM-DD-(topic).md files

### Branches and PRs
- Never push directly to main. All changes require PRs.
- Create branch FIRST: `git switch -c <prefix>/<description>`
- Prefixes: `feature/`, `fix/`, `chore/`, `deps/`, `docs/`, `test/`, `breaking/`
- Use `gh` for PR management
- Merge with: `gh pr merge --squash --auto` (branch protection requires `--auto`)
- Never enable auto-merge without user approval

### Commits
- Focus on "why" not "what"
- Example: `feature: Add XXX to improve user experience`

### Code Reviews
Gemini reviews PRs. Address feedback before merging.

## Architecture decisions

- [`adr/2026-05-04-fromsurfaceopt-and-innerweavers.md`](adr/2026-05-04-fromsurfaceopt-and-innerweavers.md) — `Weaver.fromSurfaceOpt` + `Weaver.innerWeavers` for detecting lossy fallbacks anywhere in a derived weaver tree. Read before adding new composite weaver classes.
- [`adr/2026-05-14-nodejs-sync-http.md`](adr/2026-05-14-nodejs-sync-http.md) — Synchronous HTTP on Node.js via `worker_threads` + `Atomics.wait`. Read before touching `NodeSyncHttpChannel` or the JS HTTP channels (covers the `worker_threads` runtime-load, the `Long`-to-JS trap, and why tests run their server in a worker thread).
- [`adr/2026-05-15-cross-platform-init-handoff.md`](adr/2026-05-15-cross-platform-init-handoff.md) — Value-handoff (not callback) for cross-platform `<Area>Compat` init. Read before adding new platform-specific init logic; covers the Scala.js linker DCE / init-cycle traps that bit `Http.defaultChannelFactory` and the `shouldNotBe` vs `shouldNotBeTheSameInstanceAs` test gotcha.
- [`adr/2026-06-20-background-task.md`](adr/2026-06-20-background-task.md) — `wvlet.uni.control.BackgroundTask`: a cross-platform cancellable, progress-pollable worker. Read before touching it; covers why JS runs the body inline (Node `worker_threads` can't host a Scala closure), cooperative-cancel + the `onCancel` escape hatch, catch-`Throwable`+`finally`-signal so `await` never hangs, hook-draining on completion, and why `progressStream` uses `flatMap` not `filter` (and the snapshot uses `AtomicReference` not `RxVar`).
- [`adr/2026-06-24-electron-ipc-transport.md`](adr/2026-06-24-electron-ipc-transport.md) — `wvlet.uni.electron`: Electron desktop apps by tunneling Uni RPC over IPC. Read before touching the electron transport, `RPCDispatcher`, or the `examples/electron-app` reference. Covers value-handoff of `ipcMain`/bridge (no `require("electron")`), renderer-is-async-only, the `js.Dynamic.global` dynamic-name compile trap, the `RPCClient` scalar-param `JSON.parseAny` fix, and why `scalajs-java-securerandom` is now a compile dep.
- [`adr/2026-06-26-esmodule-dom-tests-playwright.md`](adr/2026-06-26-esmodule-dom-tests-playwright.md) — `uni-dom-test` runs in headless Chromium via Playwright (`scala-js-env-playwright`), replacing jsdom so DOM tests run under `ModuleKind.ESModule` like the rest of the JS build. Read before touching the `domTest` jsEnv or the Playwright CI step; covers the Java-Playwright-bundles-its-own-driver (no Node needed) point and the CI `playwright@<ver>` pin that must match the transitive `com.microsoft.playwright` version.
- [`adr/2026-06-26-uni-jsenv-playwright.md`](adr/2026-06-26-uni-jsenv-playwright.md) — the `sbt-uni-playwright/` build: a uni-owned, cats-effect-free Playwright `JSEnv` (`uni-jsenv-playwright`) + an sbt 2.x plugin (`sbt-uni-playwright`), replacing the unmaintained Scala-2.12-only third-party env for sbt 2.x consumers. Read before touching that build; covers the polling com mechanism, the sbt-2.x `%%`/`Def.uncached` changes, and the Playwright driver thread-context-classloader fix in `BrowserSession`.
- [`adr/2026-06-30-sbt-uni-crossproject.md`](adr/2026-06-30-sbt-uni-crossproject.md) — the `sbt-uni-crossproject/` build: a minimal, uni-owned sbt 2.x re-implementation of `portable-scala/sbt-crossproject` (which isn't ported to sbt 2.x), supporting only the `CrossType.Pure` layout uni uses. Read before touching that build; covers the single-plugin-for-all-three-platforms choice, the Scala 3 val-name macro replicating sbt's `KeyMacro.definingValName`, why internal materialization needs `new CrossProject(...)`, and the `given Conversion[Builder, CrossProject]` build trigger.
- [`adr/2026-06-30-sbt2-main-build-migration.md`](adr/2026-06-30-sbt2-main-build-migration.md) — migrating the **main build** to sbt 2.x: swaps the unported third-party plugins for the uni-owned ones (`sbt-uni-crossproject`, `uni-jsenv-playwright`, `sbt-uni` for `sbt-revolver`). Read before touching `build.sbt` / `project/plugin.sbt`; covers the output-dir name collision (root → `uni-root`), `%%%`→`%%` and the `scalajs-test-interface_2.13` single-`%` exception, `Def.uncached` for `jsEnv`, and the `implicitConversions` import.
- [`adr/2026-07-06-plugin-extension-points.md`](adr/2026-07-06-plugin-extension-points.md) — `wvlet.uni.plugin` is built on typed `ExtensionPoint`s (identity-compared singletons; keyed points reject duplicate ids at activation), with `PluginContext` reduced to `contribute` + `onDeactivate`. Read before adding new contribution kinds: define a point next to the contributed type (`Command.point`, `RPCPlugin.routerPoint`) so dependency arrows point into `plugin`, never out of it.
