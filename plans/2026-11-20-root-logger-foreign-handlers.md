# Keep another Uni copy's live log router when Logger initializes

## Goals

- When a second copy of uni-core initializes `Logger` in one JVM, it should keep a log router that another copy's uni-test put on the root `java.util.logging` logger and still uses.
- Captured-log tests stop flaking, and suite logs stop escaping sbt's per-suite block, when sbt runs several projects' tests in one JVM.

## Background

- sbt runs the tests of this repo's projects in sbt's own JVM (no `Test / fork`), with one class loader per project. uni, uni-netty, and the others each load their own copy of uni-core and uni-test, since these come from inter-project dependencies (#1017 relied on this). The root JUL logger is JVM-wide. A downstream build that takes Uni as library jars may share one copy through sbt's library layer, and is then not affected.
- `Logger`'s companion object initializes `rootLogger` with `initLogger("", handlers = Seq(defaultHandler))`, which clears every root handler first. The clear is meant to replace the JDK's default `ConsoleHandler` with Uni's `ConsoleLogHandler`. It assumes only one copy of Uni owns the root logger.
- When this happens in real sbt runs:
  - In CI, or with `--log-capture` or log-level flags, a `UniTestRunner` initializes its copy's `Logger` in its constructor (`TestLogCapture.install()`, `TestConfig.apply`). In a plain local run, the first suite that uses `LogSupport` or `captureLogs` initializes it.
  - Test tasks of different projects run at the same time, so either moment can fall while another project's suites are running, possibly with a capture open.
  - From then on, the earlier runner's router is no longer on the root logger. In CI (routing on), that runner's suite logs print directly to the console for the rest of its run. With or without routing, its open `captureLogs` sees nothing until its next capture installs a new router.
- Reproduced: a new test in `OutputCaptureClassLoaderTest` initializes the second copy's `Logger` inside a `captureLogs`. The capture ends up empty and the warning prints to the console.

## Design

Lane: Standard. This changes how uni-core's `Logger` sets up the root logger, and the issue left open which layer to fix.

- If the root logger holds a live router of another Uni copy, `Logger` initialization leaves the root handlers as they are. Otherwise it replaces them with its own `ConsoleLogHandler`, as today.
  - A live router is a root handler whose class is in a `wvlet.uni.` package, comes from a different class loader, and implements `Supplier[Array[Handler]]` with `get()` returning null. This is the JDK-typed contract that routers already follow across class loaders (the 2026-10-27 log-capture ADR): `get()` returns the wrapped handlers only after the router has been removed.
  - Only live routers are kept. A plain handler of an older copy (its `ConsoleLogHandler`, a removed router) is still replaced, so `Logger` init never adopts a finished runner's handlers. A later copy's records print through the first copy's console handler, which may format differently if the copies are different Uni versions. If the later copy's own router wraps the first copy's, this lasts until the later runner finishes. Router nesting could already lead there before.
- The check runs only on the JVM, in the JVM `LogEnv`. Scala.js and Native have a single class loader and keep today's behaviour.
- Explicit calls (`Logger.init`, `setDefaultHandler`, `clearAllHandlers`) still reset the root handlers. That is the documented way for test code to turn routing off.
- The router nesting from the 2026-11-20 amendment is unchanged: each later runner's `install()` wraps whatever router is already on the root logger.
- Tests: the new uni-test repro, plus uni-core tests that load a second uni-core copy and check that a live foreign router is kept and a plain foreign handler is replaced. A new uni-core logging ADR records the rule, and the capture ADR points to it. The existing class-loader tests drop the comment saying initialization resets the handlers.

## Alternatives and Why Not?

- **The router re-installs itself (checking on each capture or suite start).** JUL has no notice when a handler is removed, so records logged before the next check are lost or printed. It also leaves the actual cause in place.
- **Root init never clears existing handlers.** This changes behaviour for every app (the JDK `ConsoleHandler` from `logging.properties` would print each record twice). The problem is specific to copies of Uni.
- **Keep every handler from another Uni copy.** Needs no router contract, but a finished copy's `ConsoleLogHandler` would stay on the root logger for good, keeping its (possibly closed) class loader alive, both in a long-lived sbt server and in hosts with one class loader per app.
- **Re-initialize Logger in a test-only hook.** Only hides the symptom in tests, and the request rules this out.
