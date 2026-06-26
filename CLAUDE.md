# CLAUDE.md

## Project Overview

**Uni** is an essential Scala utilities, refined for Scala 3 with minimal dependencies.

## Modules

- **uni-core**: Pure-Scala essential libraries shared with uni-test
- **uni**: Main library collection (logging, DI, JSON/MessagePack, RPC/HTTP)
- **uni-test**: Unit testing framework

Cross-platform: JVM, Scala.js, Scala Native via sbt-crossproject. Platform-specific code in `.jvm`, `.js`, `.native` folders.

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
