# 2026-05-18: Node `worker_threads`-backed `Task.runRegistered` for blocking `await()`

PR: https://github.com/wvlet/uni/pull/553

## Context

`wvlet.uni.control.Task` ships in three flavours per the design at
`plans/2026-05-18-cross-platform-thread.md`. The cross-platform contract
(uni#552) requires that *every* supported platform — JVM, Scala.js (Node
and browser), Scala Native — implement the same lifecycle API so wvlet's
`SqlConnector` backends (Trino, DuckDB, Snowflake) can write the body
once.

For JVM and Native, blocking `Task.await()` is just `CountDownLatch.await`.
For Scala.js, the body runs cooperatively on the event-loop microtask
queue and `await()` would deadlock — the call stack never unwinds, so the
microtask hosting the body never runs.

wvlet specifically needs `await()` on Node for the synchronous-shaped
REPL/CLI use case ("submit query, block until result"). Browser doesn't
need it (REPL doesn't run there).

## Decision

Add a *second* API surface — `Task.register("id") { body }` plus
`Task.runRegistered("id")` — and implement it on Node with `worker_threads`
+ `Atomics.wait`, mirroring the pattern from
ADR 2026-05-14 (`NodeSyncHttpChannel`).

- The body is registered under a stable string id at module-init time.
- `runRegistered` on Node spawns `new Worker(WorkerScript, { eval: true,
  workerData: { taskId, sab, bundleUrl } })`.
- The worker dynamic-imports the *same* Scala.js bundle (`bundleUrl =
  import.meta.url`), which re-runs module init in the worker isolate and
  re-populates the registry.
- The worker calls `globalThis.__uniTaskInvoke(taskId, sab)` which looks
  up the body and runs it with an SAB-backed `TaskContext`.
- The parent's `await()` does `Atomics.wait` on the state word; `cancel`
  is a CAS into a separate cancel-flag word that the worker reads at
  every `ctx.isCancelled` / `ctx.checkCancelled` call.

`Task.run { body }` (closure-based) stays available everywhere; `await()`
on it throws `UnsupportedOperationException` on Scala.js. Browser
`runRegistered` falls back to the microtask path — `Atomics.wait` is
unavailable on the browser main thread (throws since Chrome 87+).

## Non-obvious points a future reader would otherwise reverse-engineer

### Bundle URL: `js.\`import\`.meta.url`, not `process.argv[1]`

The worker needs to know which file to dynamic-import. `process.argv[1]`
seems natural — it's the script path passed to `node`. But under
`sbt-jsenv-nodejs` it's empty: the test runner dynamic-imports the bundle
rather than running it as the main script. `js.\`import\`.meta.url`
(Scala.js's ESM `import.meta.url` accessor) works across `sbt-jsenv-nodejs`,
direct `node` invocation, Bun, and Deno — it's the URL of the current
module, which is exactly what the worker needs to load.

### The test bundle's `Bridge.start()` throws in the worker

The sbt test bundle ends with `org.scalajs.testing.bridge.Bridge.start()`,
which references the `scalajsCom` global supplied by the test runner.
That global doesn't exist in a worker isolate, so the worker's
`await import(bundleUrl)` rejects.

This would normally be fatal — module-init exports become inaccessible
once the module errors. The fix relies on two things in tandem:

1. ESM side effects that completed *before* the error are persistent in
   the isolate's heap (they just aren't exposed through the namespace
   export, since the module is errored).
2. The worker bootstrap catches the import error and continues if
   `globalThis.__uniTaskInvoke` is present — i.e. the registration side
   effect ran before `Bridge.start()` threw.

In production user bundles (no sbt test bridge), the import succeeds
normally; the try/catch is a safety net.

### Forcing the bootstrap to run *before* the bridge throws

`@JSExportTopLevel` on a `def` doesn't trigger eager evaluation — the
export just creates a function reference. `@JSExportTopLevel` on a `val`
*does* trigger eager evaluation at module init, in declaration order.

The implementation uses a `val _bundleBootstrap: Boolean` in `taskCompat`
whose RHS assigns `invokeInWorker` to `globalThis.__uniTaskInvoke`. The
exported `Boolean` value is unused — it exists only to force the `val`
to be eagerly evaluated, which runs the side effect before the bundle's
tail (where the bridge call lives).

### `js.Dynamic.global.updateDynamic("__uniTaskInvoke")(f)` produces a strict-mode `ReferenceError`

The naïve way to assign to globalThis from Scala.js is:

```scala
js.Dynamic.global.updateDynamic("__uniTaskInvoke")(f)
```

The Scala.js linker rewrites this as a **bare identifier assignment**:

```js
__uniTaskInvoke = f;
```

ES modules are always in strict mode, where assigning to an undeclared
identifier throws `ReferenceError: __uniTaskInvoke is not defined`. The
optimization treats the property name as a known top-level symbol; when
no such top-level binding exists, the emitted code is unsound.

Workaround: use `Object.defineProperty` instead, which the optimizer
leaves alone:

```scala
val gt: js.Dynamic = js.eval("globalThis").asInstanceOf[js.Dynamic]
gt.Object.applyDynamic("defineProperty")(
  gt,
  "__uniTaskInvoke",
  js.Dynamic.literal(value = f, writable = true, configurable = true)
)
```

`js.eval("globalThis")` is needed because `js.Dynamic.global` cannot be
passed as a value (only used as the left side of `.`) — the Scala.js
"global scope as value" restriction.

### Per-isolate registries

`worker_threads` are separate V8 isolates. Their memory is independent —
`Task.registry` in the main thread is invisible to the worker. The
implementation relies on the worker's bundle import re-running module
init in the worker isolate, which re-populates `Task.registry` *there*
from the same `Task.register("id") { … }` source statements.

The implication for callers: bodies must be registered at module-init
time (inside an `object` body whose enclosing object is eagerly
initialised). Lazy registration (e.g. inside a test method) won't be
visible to the worker.

For the uni test suite, `NodeWorkerTaskTestRegistry` uses the same
`@JSExportTopLevel val` trick to force eager init.

### Cancel flag is SAB-resident, not Scala-side

On JVM/Native, `cancel()` flips an `AtomicBoolean`. On Node-worker, the
flag lives in a `SharedArrayBuffer` word. The main thread writes via
`Atomics.compareExchange`; the worker reads via `Atomics.load` at every
`ctx.isCancelled` call. Both sides share *the same memory*; this is the
only practical cross-isolate signal available in Node.

The cancel *reason* (a string) is stored on the parent's Scala side, not
in the SAB. The worker doesn't need it — it throws
`InterruptedException("Task cancelled")` with a default message, and the
parent overrides the message with the recorded reason when surfacing
the terminal state via `await()` / `awaitRx`. Keeps the SAB layout
small.

## Consequences

- `Task.await()` works on all four platforms: JVM, Native, and Scala.js
  on Node (via `runRegistered`). Browser remains `awaitRx`-only by
  design — main-thread `Atomics.wait` is unavailable.
- wvlet's `SqlConnector` backends can write their body once with
  `Task.register("backend-id") { … }` and run it with
  `runRegistered("backend-id")` on every platform.
- New constraint for Scala.js callers wanting Node blocking await:
  bodies must be registered at module-init time, not inside method
  bodies. Documented on `Task.register`.
- Per-task worker spawn + per-task 4 KB `SharedArrayBuffer` are the
  known costs. Acceptable for the long-running-query workloads driving
  this PR; worker pooling is a follow-up if profiling demands it.
- A future `TaskScheduler` opt-in (e.g. shared worker pool) is the
  natural extension point for high-frequency callers. Gemini flagged
  this in PR review; captured in the plan doc.
