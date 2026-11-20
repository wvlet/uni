# Root Logger initialization keeps another Uni copy's live log router

## Context

- sbt runs the tests of this repo's projects in its own JVM, one class loader per project, so uni, uni-netty, and the others each load their own copy of uni-core and uni-test. The root `java.util.logging` logger is shared by all of them.
- Initializing `Logger` cleared every root handler and added this copy's `ConsoleLogHandler`. That replaces the JDK's default `ConsoleHandler`, but assumes one copy owns the root logger.
- A runner initializes its copy's `Logger` in its constructor (CI, or log flags) or in its first suite (local). Test tasks of different projects overlap, so this happens while another project's suites run. The clear dropped that project's `TestLogCapture` router: in CI its suite logs printed outside sbt's per-suite block, and its open `captureLogs` received nothing.

## Decision

- On the JVM, `Logger` initialization leaves the root handlers as they are when one of them is a live router of another copy of Uni: a class in a `wvlet.uni.` package, loaded by another class loader, implementing `java.util.function.Supplier` with `get()` returning null. That is the JDK-typed contract routers already follow across class loaders (`2026-10-27-unitest-log-capture.md`): `get()` returns the wrapped handlers only once the router is removed.
- Any other root handlers, including a plain handler or a removed router of another copy, are replaced as before.
- The check lives in `LogEnv` (`keepsRootHandlers`). Scala.js and Native load one copy and keep the old behaviour.

## Why not

- Keep every handler of another Uni copy: a finished runner's `ConsoleLogHandler` would stay on the root logger for good, keeping its class loader, possibly closed by sbt, in use. AirSpec hit the same class-loader lifetime problem with sbt 1.3's layered class loaders.
- Have the router re-install itself on each capture or suite start: JUL gives no notice when a handler is removed, so records logged in between are lost or printed.
- Never clear existing root handlers: an app's JDK `ConsoleHandler` from `logging.properties` would print every record twice.

## Consequences

- While another copy's router is live, this copy's records print through the first copy's console handler, which may format differently if the copies are different Uni versions.
- `Logger.init`, `setDefaultHandler`, and `clearAllHandlers` still reset the root handlers and turn routing off.
