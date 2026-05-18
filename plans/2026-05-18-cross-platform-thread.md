# 2026-05-18: Cross-platform background-work primitive (uni#552)

Tracking: https://github.com/wvlet/uni/issues/552

## Goal

A cross-platform primitive for "run a unit of work on a background worker
that can be cooperatively cancelled and progress-polled from another thread."
The caller writes platform-neutral code; uni handles `Thread` vs
`worker_threads` vs `pthread`.

Primary callers: `wvlet/wvlet` `SqlConnector` backends — DuckDB (Native,
JVM), Trino (HTTP, all platforms), Snowflake (HTTP, all platforms),
future Spark / DuckLake / Spice. REPL ctrl-C and CLI progress bars.

Out of scope for this design: a general M:N fiber scheduler, an effect
monad, structured concurrency (`scope { … }`). `RxFiber` already gives
us Rx-level fiber semantics — this primitive is one level below it, the
"actually run on a separate OS thread" piece.

## Naming, shape, and package: `wvlet.uni.control.Task`

Decision (post-review): the abstraction is **`Task` with no result
type** and **no progress API**. Body is `TaskContext => Unit`. The
progress channel from the original sketch is gone — bodies publish
whatever they want (progress, stages, partial results) through any
existing uni-rx primitive (`RxVar`, `RxQueue`, `RxDeferred`), the same
way they'd publish a result. Task focuses entirely on lifecycle.

Package: `wvlet.uni.control`, alongside `Retry`, `CircuitBreaker`,
`RateLimiter`, `Guard`, `Resource`, `Ticker` — the existing home for
lifecycle / reliability primitives. No new `concurrent` package
needed; `control` is the right fit. Physical location: `uni/`, not
`uni-core/`, so `Task` is available wherever `RxVar` and friends are.

Rationale for no progress API:

- The issue says "the worker body is just a regular block that touches
  FFI" and "side-effects are managed outside the interface." That
  rules out a functional `body: => A` return — the body's purpose is
  to *cause* effects, not produce a value.
- For every concrete use case (DuckDB pending-execute, Trino poll
  loop, Snowflake submit/poll), the result lands in a buffer / queue /
  `RxDeferred` the caller already controls. An `A` return on `Task`
  would either duplicate that channel or force it through Task's own
  plumbing, gaining nothing.
- `await()` in the issue means "block until terminal state," not
  "block and return the value." `await(): Unit` (or
  `awaitRx: Rx[Unit]` on browser) is what was actually asked for.
- If a caller wants the value-returning convenience, it's a one-liner
  on top: `Task.run { ctx => d.complete(Try(body(ctx))) }` with a
  user-supplied `RxDeferred[A]`.
- Same logic kills the progress API: any "progress" channel is just an
  Rx the body writes to. uni already ships `RxVar` (latest-value-wins),
  `RxQueue` (event stream), `RxDeferred` (one-shot). Building it into
  `Task` would force callers to convert between the Task progress type
  and whatever Rx they already use elsewhere.

`Task` is free in `wvlet.uni.control` (no collision). The `Bg` prefix
was a working-name; dropped.

## Surface

```scala
package wvlet.uni.control

trait Task:
  /** Current observable state. */
  def state: Task.State

  /** True once state is terminal (Succeeded / Failed / Cancelled). Non-blocking. */
  def isDone: Boolean

  /** Request cooperative cancellation. Idempotent. Returns immediately. */
  def cancel(): Unit

  /**
   * Block until terminal state. Throws InterruptedException if cancelled,
   * rethrows the body's exception if Failed. Throws UnsupportedOperationException
   * on Scala.js — use awaitRx there.
   */
  def await(): Unit

  /** Rx-completion of the task. Emits OnNext(()) on Succeeded, OnError on Failed
    * or Cancelled. Works on every platform. */
  def awaitRx: Rx[Unit]

object Task:
  enum State:
    case Running, Succeeded, Failed, Cancelled
    def isTerminal: Boolean = this != Running

  def run(body: TaskContext => Unit): Task = taskCompat.run(body)

trait TaskContext:
  /** True iff cancellation has been requested. Check at safe points in the loop. */
  def isCancelled: Boolean

  /** Throws InterruptedException if cancellation has been requested. */
  def checkCancelled(): Unit
```

Notes on the shape:

- No `[A]` parameter, no progress API. Bodies publish anything they
  want — progress, stages, partial results — through any existing Rx
  primitive (`RxVar`, `RxQueue`, `RxDeferred`).
- `await()` is provided where blocking is possible; `awaitRx` is the
  universal path. Both report cancel/failure faithfully.
- No `Cancelling` state. It was unreliable as an observable signal (a
  racing terminal `set` from the body could win), so observers would
  see it nondeterministically. `isCancelled: Boolean` covers the
  "cancel requested" signal; the terminal state is the source of
  truth for "what happened."
- No `flatMap`/`map` on `Task` — composition happens via Rx
  (`task.awaitRx.flatMap(_ => …)`).

## Platform implementations

### JVM (`uni/.jvm/.../taskCompat.scala`)

- Worker: `Thread` from `ThreadUtil.newDaemonThreadFactory("uni-task")`.
  One thread per `Task.run` call. Acceptable for the long-running-query
  use cases (DuckDB / Trino / Snowflake); a shared `Executor` /
  `TaskScheduler` opt-in is the right enhancement when a high-frequency
  caller appears — Gemini flagged this in PR review.
- Cancel signal: `AtomicBoolean` read by `isCancelled`; `cancel()`
  also calls `Thread.interrupt()` so blocking JDK calls
  (`InputStream.read`, `Socket.connect`, `Thread.sleep`) unblock.
- Progress: `AtomicReference[Progress]` for `progress`;
  `RxQueue[Progress]` (existing) for `progressStream`.
- Await: `CountDownLatch(1)`, released on terminal transition.

### Scala Native (`uni/.native/.../bgTaskCompat.scala`)

- Worker: `pthread_create`. Daemon-style — we don't `join` from the
  finalizer; the JVM-style daemon thread isn't a concept here, so the
  task body must finish (cancellation makes that happen).
- Cancel signal: a `Ptr[CInt]` (or `AtomicInt` from
  `scala.scalanative.unsafe`) toggled by `cancel()`.
- Progress: a `Ptr[Any]` guarded by a mutex; `RxQueue` for stream.
- Await: pthread condition variable.
- Worth checking: whether `scala-native` has a higher-level
  `Thread`/`ExecutorService` shim that already handles this. If yes,
  use it; we don't want to reimplement pthread plumbing per backend.

### Scala.js — Node (`uni/.js/.../bgTaskCompat.scala`, `isNode == true`)

- Worker: `worker_threads.Worker`, reusing the runtime-load trick
  from `NodeSyncHttpChannel` (`process.getBuiltinModule('worker_threads')`
  + global `require` fallback). The static `@JSImport` trap from
  ADR 2026-05-14 applies here too: any `BgTask.run` user is reachable
  from browser bundles, so the import must be runtime, not static.
- Worker body source: this is the part that bites. The body is Scala
  code; `worker_threads` runs JS in a fresh V8 isolate. Options:
  1. **Restrict to FFI-shaped bodies** (like `NodeSyncHttpChannel`):
     the worker script is a fixed JS string parameterised via
     `workerData`. Doesn't satisfy "the body is just a regular block."
  2. **Load the same Scala.js bundle into the worker** and dispatch via
     a registry: at link time we emit a "worker entrypoint" that takes
     a task ID and arguments. Body must be registered ahead of time
     and serialised through `workerData`. Closes over nothing.
  3. **Run the body on the main thread's microtask queue** and treat
     the "worker" as a logical fiber, not a real OS thread. Cancel
     and progress still work (cooperative). This collapses Node and
     browser to the same impl, but loses the "DuckDB blocking call
     doesn't freeze Trino polling" benefit.
- Cancel signal: `SharedArrayBuffer` + `Atomics.store` (parent writes
  a non-zero flag; worker reads via `Atomics.load` at safe points).
  Same pattern as `NodeSyncHttpChannel`, just in the opposite
  direction.
- Progress: parent reads a `SharedArrayBuffer` word for the "latest
  progress generation," with a richer payload posted via
  `parentPort.postMessage` for the stream.
- Await: `Atomics.wait` on the terminal-state word. Same `Long`-to-JS
  trap as ADR 2026-05-14 — `.toInt` any millis before crossing into JS.

Recommendation: start with (1) for the DuckDB-on-Node case (a fixed
worker that drives a libduckdb pending-execute loop is FFI-shaped
anyway). Defer (2) until a non-FFI use case actually shows up.

### Scala.js — Browser

**Service workers are the wrong primitive.** Documenting this because
the question keeps coming up:

| | Service Worker | Web Worker (dedicated) |
|---|---|---|
| Purpose | Network proxy / offline cache / push | General background script |
| Lifetime | Browser-managed; killed when idle | Lives until parent terminates it |
| Scope | One per origin+scope; survives page reloads | One per spawn; tied to spawning page |
| Registration | `navigator.serviceWorker.register(url)`; HTTPS required; lifecycle (install/activate) | `new Worker(url \| Blob)`; no lifecycle |
| Comms | `fetch` interception + `postMessage` via `MessageChannel` | `postMessage`/`onmessage` |
| `SharedArrayBuffer` + `Atomics.wait` | Not usable for this pattern — SW can be terminated mid-wait | Works in the worker; **fails on main thread** (throws since Chrome 87) |
| Right fit for "run this function and return the result" | No | Yes |

So the browser implementation should use **dedicated Web Workers**
(`new Worker(blobUrl)`), with the same SharedArrayBuffer + Atomics
plumbing as the Node impl.

Caveats specific to the browser:

- `Atomics.wait` **throws on the main thread** (`TypeError: Atomics.wait
  cannot be called in this context`). So `await()` cannot block the
  main thread on the browser. Options:
  1. Throw `UnsupportedOperationException` from `await()` on the
     browser main thread; require callers to use `progressStream` /
     `poll` / an `Rx`-wrapper instead.
  2. Provide `awaitRx: Rx[A]` as the primary API and only expose
     blocking `await()` where supported.
  3. (Heavy) require the caller's own code to be running in a Web
     Worker so `Atomics.wait` works.
  Strongly prefer (2) — it matches the issue's "block-to-completion"
  requirement on JVM/Node/Native and degrades gracefully on browser.
- `SharedArrayBuffer` requires cross-origin isolation (COOP+COEP
  headers). Document this; uni can't enforce it. Fall back to plain
  `postMessage` for progress/cancel if SAB is unavailable (cancel
  becomes higher-latency but still works).
- Web Worker source: same dispatch-registry problem as Node. Inline a
  Blob URL with the Scala.js bundle, dispatch by task ID.

Browser is a **v1 target**, not a deferred one — confirming
feasibility on all four platforms together is what de-risks the API.
The `await()`/`awaitRx` split above is specifically what makes
browser viable: `await()` throws `UnsupportedOperationException` on
browser main thread, `awaitRx` works everywhere.

Worker source loading on browser: ship a fixed worker-entry script
(loaded via `Blob` URL) that imports the same Scala.js bundle and
dispatches by task ID. Task bodies register at module init via a
small registry. This is more friction than `Task.run { … }` on
JVM/Native; we accept that as the cost of "actually-on-a-Web-Worker"
parallelism. Fallback for environments where SAB/COOP+COEP is
unavailable: run the body on the main thread's microtask queue with
cooperative cancellation, losing parallelism but keeping the API. The
feasibility spike validates both paths.

## Open questions

1. **Progress type.** Fixed shape (e.g. `case class Progress(percent:
   Option[Double], rowsProcessed: Option[Long], rowsTotal: Option[Long],
   extras: Map[String, String])`) vs `Any` vs typed
   `BgTask[A, P <: Progress]`? Leaning typed `BgTask[A, P]` — wvlet
   wants `QueryStats`, we shouldn't downcast.
2. **Scheduler ownership.** Reuse `RxScheduler.blocking` (cached pool)
   for the JVM impl? It is already designed for blocking I/O. Adds a
   coupling between this primitive and the Rx scheduler we just
   designed. Alternative: a dedicated `BgTaskScheduler` so this
   primitive doesn't depend on `RxScheduler`. Leaning dedicated to
   keep the "below Rx" layering clean.
3. **Cancel semantics on JVM.** `Thread.interrupt()` is racy with
   non-interruptible JNI calls (libduckdb's `duckdb_execute` is one).
   The right cancel for DuckDB is `duckdb_interrupt(connection)`, not
   a thread interrupt. So `cancel()` should *only* set the cooperative
   flag; the body is responsible for translating that into the
   backend-specific cancel. `Thread.interrupt()` is a courtesy for
   pure-Scala bodies, optional.
4. **`await()` re-entrancy on Node main thread.** `Atomics.wait`
   blocks the event loop, which means timers, microtasks, and other
   `BgTask`s in the same isolate stall. Document this — the Node sync
   HTTP channel already has the same property and it has been fine in
   practice, but it's a footgun for someone running multiple BgTasks
   from the main thread.
5. **`Rx` integration.** A `bgTask.asRx: Rx[A]` adapter is the natural
   bridge. Should this live in uni-core's rx package (creates a
   `concurrent → rx` reference) or as an extension method in
   `uni/.../rx`? Probably the latter, so `uni-core` stays free of
   concurrent.

## Sketched ADR points (for after the API is reviewed)

- Why a separate primitive instead of layering on `RxFiber`: fibers
  are scheduler-resident; they don't get their own OS thread, so a
  blocking JNI call inside an `RxFiber` body starves the Rx scheduler.
- Why service workers were rejected for the browser impl.
- Why `Atomics.wait` cannot back `await()` on the browser main thread
  and the resulting `awaitRx` fallback.

## Implementation notes (as landed)

Two API surfaces ended up shipping together so the design could be
validated end-to-end across all four platforms:

1. **`Task.run { body }`** — closure-based, works on JVM, Native, and
   Scala.js (both Node and browser). `await()` blocks on JVM/Native;
   throws `UnsupportedOperationException` on Scala.js (would deadlock
   the event loop). Use `awaitRx` for portable completion.

2. **`Task.register("id") { body }` + `Task.runRegistered("id")`** —
   registry-based. The body is registered at module-init time. On
   Node, `runRegistered` spawns a `worker_threads` worker that
   dynamic-imports the same Scala.js bundle (via `import.meta.url`),
   so the worker's bundle re-import re-runs `Task.register` calls in
   the worker isolate. The main thread blocks on `Atomics.wait` over a
   `SharedArrayBuffer`; cancel is a CAS into the SAB's cancel-flag
   word that the worker observes at every `ctx.isCancelled` /
   `ctx.checkCancelled` call.

The driving requirement (uni#552) called out wvlet's cross-platform
query runner — Trino HTTP polling, DuckDB libduckdb pending-execute,
Snowflake polling — all of which fit the "register the connector's
body at module init, run it with arguments via a side channel"
pattern. Bodies that need captured state on Node use module-level
state in the registered object (Scala.js singletons re-init per
isolate, so each worker has its own state).

`cancel(reason: String)` is now part of the trait; the reason flows
into the `InterruptedException` that `checkCancelled` throws and that
`await()` / `awaitRx` surface.

`Task.State.Cancelling` was dropped during review — it was unreliable
as an observable signal (a racing terminal `set` from the body could
win). `isCancelled: Boolean` covers the "cancel requested" signal;
the terminal state (`Cancelled` / `Succeeded` / `Failed`) is the
source of truth for "what happened."

The progress API from the original sketch is also gone: bodies
publish anything they want — progress, partial results, terminal
values — through any existing Rx primitive (`RxVar`, `RxQueue`,
`RxDeferred`). Same "side-effects managed outside the interface"
rule that already justified no `[A]` result type.

## Non-obvious traps the Node implementation surfaced

These are captured in `adr/2026-05-18-node-task-worker-threads.md`
for future readers. Short version:

- `process.argv[1]` is empty under `sbt-jsenv-nodejs` (the test
  runner dynamic-imports the bundle rather than running it as the
  main script). Use `js.\`import\`.meta.url` instead.
- The test bundle's tail calls `org.scalajs.testing.bridge.Bridge.start()`
  which references `scalajsCom` — undefined in worker isolates and
  throws. The worker bootstrap tolerates this by catching the import
  error and continuing if `globalThis.__uniTaskInvoke` is present
  (set by an eagerly-evaluated `@JSExportTopLevel` `val` that
  precedes the bridge call).
- `js.Dynamic.global.updateDynamic("foo")(v)` is rewritten by the
  Scala.js linker as a bare identifier assignment (`foo = v`), which
  throws `ReferenceError` in strict-mode ES modules. Use
  `Object.defineProperty(gt, "foo", {value: v, writable: true, configurable: true})`
  via `js.eval("globalThis")` instead.
- `@JSExportTopLevel` on a `val` triggers eager evaluation at module
  init; on a `def`, it doesn't. The bundle-bootstrap `val` pattern
  is what makes module-init registration reliable across worker
  re-imports.

## Suggested next step

**Feasibility spike across all four platforms in one PR**, before
broadening the surface. Concretely:

1. Trait + `taskCompat.run` stub on JVM, Native, JS-Node, JS-Browser.
2. A single canonical test (`TaskFeasibilityTest`) that runs the same
   body on every platform: spin a counter for N iterations, report
   progress every iteration, exit early on `isCancelled`. Assert the
   counter increments, the progress stream emits, and `cancel()` cuts
   the run short. JVM/Native/Node use `await()`; browser uses
   `awaitRx` (test via uni-test's existing async support).
3. JS-Browser path uses a Blob-URL'd worker entry. Don't ship a full
   task registry yet — register one task ID inline for the test.
4. No `RxScheduler` integration in this PR. No typed `Progress` in
   this PR. Both come after the spike confirms the API survives the
   trip through all four backends.

Once the spike lands, the wvlet DuckDB integration is the first real
consumer; let its needs shape any further API changes.
