# BackgroundTask — cross-platform cancellable, progress-pollable worker (issue #552)

## Context

wvlet's `SqlConnector.QueryHandle` (`state`/`stats`/`cancel`/`await`) is satisfied "for free" by the
Trino client (polls a remote HTTP server) but the DuckDB impl ships as a synchronous wrapper:
`cancel` is a no-op and there's no progress. libduckdb *does* expose `duckdb_query_progress`,
`duckdb_interrupt`, and a pending-result API for cooperative incremental execution — but driving them
requires the query to run on a **different thread** than the one polling progress / issuing cancel.
wvlet has no cross-platform primitive for that and doesn't want to inline JVM `Thread` / Native
`pthread` / Node `worker_threads` plumbing per backend. Issue #552 asks uni for it.

**Outcome:** a `BackgroundTask` that runs a plain side-effecting block on a background worker, with
cooperative cancel + a non-blocking progress snapshot + block-to-completion — no effect monad.

### Key constraint (drove the JS decision)

Node `worker_threads` run a **separate JS realm** fed a pre-written JS string + `SharedArrayBuffer`
(see `adr/2026-05-14-nodejs-sync-http.md`); an arbitrary Scala.js closure can't run on one, and
Scala.js is single-threaded. So a *true* background worker exists only on **JVM and Native** (the
DuckDB targets). Decision: the API is identical on all three platforms, but **JS runs the body inline
on the event loop during `start()`** (completes immediately; `await`/`poll` return the result;
`cancel()` is best-effort once done) — correct but not concurrent, and documented as such.

## Design

Package **`wvlet.uni.control`** (uni's side-effect/lifecycle home: `Control`, `Resource`,
`RateLimiter`). Reserve `wvlet.uni.rx` for the existing Rx-flavored `RxFiber` (this is its non-Rx
twin). Progress is a **poll snapshot** (no Rx dependency); an `Rx[P]` push stream is an easy
follow-up if needed.

```scala
package wvlet.uni.control
import wvlet.uni.util.Result

/** A unit of work running on a background worker: cooperatively cancellable, progress-pollable. */
trait BackgroundTask[A, P]:
  /** Latest progress the body reported (None if none yet). Non-blocking. */
  def progress: Option[P]
  /** Terminal snapshot: None while running, Some(result) once done/failed/cancelled. Non-blocking. */
  def poll: Option[Result[A]]
  /** Block the caller until the task terminates, returning its result.
    * JVM/Native block on a latch; on JS the body already ran inline, so this returns immediately. */
  def await(): Result[A]
  /** Signal cooperative cancellation: sets the flag and runs any registered onCancel hooks. */
  def cancel(): Unit
  def isCancelled: Boolean
  def isDone: Boolean

/** Handed to the task body so it can observe cancellation and report progress. */
trait TaskContext[P]:
  def isCancelled: Boolean
  /** Throws TaskCancelledException if cancelled — call at safe points to abort cooperatively. */
  def checkCancelled(): Unit
  def reportProgress(p: P): Unit
  /** Register a hook run on the caller's thread when cancel() fires (e.g. duckdb_interrupt). */
  def onCancel(hook: () => Unit): Unit

final class TaskCancelledException extends RuntimeException("BackgroundTask was cancelled")

object BackgroundTask:
  /** Start `body` on a background worker (JVM/Native: daemon thread; JS: inline). */
  def start[A, P](body: TaskContext[P] => A): BackgroundTask[A, P]
```

### DuckDB usage (the motivating shape)

```scala
val task = BackgroundTask.start[ResultSet, QueryProgress] { ctx =>
  ctx.onCancel(() => libduckdb.duckdb_interrupt(conn))   // unblock a query stuck in a libduckdb call
  val pending = libduckdb.pending(conn, sql)
  while !pending.isFinished do
    ctx.checkCancelled()                                 // cooperative abort between steps
    pending.executeTask()
    ctx.reportProgress(libduckdb.duckdb_query_progress(conn))
  pending.result
}
// caller thread, non-blocking:
task.progress            // Option[QueryProgress] for a progress bar
task.cancel()            // REPL ctrl-C → flag + duckdb_interrupt
task.await()             // Result[ResultSet]
```

### Shared impl + platform split

The whole state machine is **shared** (`uni/src/main`); only "spawn a worker" and "block until done"
differ per platform. Internal state uses `AtomicBoolean`/`AtomicReference` (available on all three —
Native is multithreaded, JS provides single-threaded shims):

- `cancelled: AtomicBoolean`, `progressRef: AtomicReference[Option[P]]`,
  `resultRef: AtomicReference[Option[Result[A]]]`, `cancelHooks: AtomicReference[List[() => Unit]]`.
- `start` builds the `TaskContext`, then runs the body via the compat worker; the worker wrapper does
  `resultRef.set(Some(try Success(body(ctx)) catch NonFatal => Failure(e)))` then signals the gate.
  `cancel()` CAS-sets the flag once and runs the registered hooks. `await()` = `gate.await();
  resultRef.get().get` (latch `countDown` happens-before `await` returns ⇒ result is visible).

Platform compat — **`private[control] object BackgroundTaskCompat`** (one file per platform):

| | `runWorker(body: () => Unit): Unit` | gate `await()` / `signal()` |
|---|---|---|
| **JVM** | daemon `Thread` via `ThreadUtil.newDaemonThreadFactory("uni-task")` | `CountDownLatch(1)` |
| **Native** | daemon `Thread` (same) | `CountDownLatch(1)` |
| **JS** | run `body()` **inline** (synchronous) | no-ops (already done when `start` returns) |

Cancellation is **cooperative** (no forced interrupt) per the issue; the `onCancel` hook covers the
"interrupt a call already in flight" case (e.g. `duckdb_interrupt`). `Thread.interrupt()` on
JVM/Native is a possible later enhancement.

## Files

| Action | Path |
|---|---|
| Add | `uni/src/main/scala/wvlet/uni/control/BackgroundTask.scala` (traits + `TaskCancelledException` + shared impl + `object BackgroundTask`) |
| Add | `uni/.jvm/src/main/scala/wvlet/uni/control/BackgroundTaskCompat.scala` (daemon thread + `CountDownLatch`) |
| Add | `uni/.native/src/main/scala/wvlet/uni/control/BackgroundTaskCompat.scala` (same as JVM) |
| Add | `uni/.js/src/main/scala/wvlet/uni/control/BackgroundTaskCompat.scala` (inline run + no-op gate) |
| Add | `uni/src/test/scala/wvlet/uni/control/BackgroundTaskTest.scala` (cross-platform) |
| Add | `uni/.jvm/src/test/.../BackgroundTaskConcurrencyTest.scala` (+ a `.native` copy) — true-concurrency cases |

Reuses: `wvlet.uni.util.Result` (`Success`/`Failure`), `wvlet.uni.util.ThreadUtil`
(`newDaemonThreadFactory`, `sleep`). Follows the established `Area`/`AreaCompat` value-handoff split
(`adr/2026-05-15-cross-platform-init-handoff.md`).

## Verification

- `./sbt scalafmtAll` then `uniJVM/test uniJS/test uniNative/test` (compile + run all three).
- **Cross-platform tests** (`uni/src/test`, run on JS inline too): normal completion → `Success`;
  body throws → `Failure(e)`; `reportProgress` then return → `progress == Some(...)`; `await()`
  returns the same as `poll` once done.
- **Concurrency tests** (`.jvm` + `.native`, real threads via `CountDownLatch`): body loops on
  `!ctx.isCancelled` reporting progress → caller observes `progress` advance, calls `cancel()`,
  `await()` terminates (loop exits / `checkCancelled` → `Failure(TaskCancelledException)`),
  `isCancelled == true`; `onCancel` hook runs on `cancel()`; `await()` blocks then returns `Success`
  after a `ThreadUtil.sleep` body. (These can't run on JS — it executes inline, no concurrency.)

## Risks / notes

- **JS is non-concurrent** (inline) — acceptable; DuckDB is JVM/Native. Documented on `start`.
- **Cooperative cancel only** — a body blocked inside one long FFI call won't cancel until it returns
  to a checkpoint; the `onCancel` hook is the escape hatch (e.g. `duckdb_interrupt`).
- **Out of scope (follow-ups):** `Rx[P]` progress stream (RxVar-backed), `Thread.interrupt()` on
  cancel, worker pooling, timeouts. Capture an ADR for the Node-worker_threads limitation + the JS
  inline decision once it lands.
