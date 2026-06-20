# 2026-06-20: `BackgroundTask` — a cross-platform cancellable, progress-pollable worker

PRs: #588 (the primitive, merge `2a44dfc1`) and the `progressStream` + this-ADR follow-up.

## Context

`wvlet/wvlet`'s `SqlConnector.QueryHandle` (`state`/`stats`/`cancel`/`await`) is satisfied for free
by the Trino client (it polls a remote HTTP server) but not by the DuckDB client, which ships as a
synchronous wrapper: `cancel` is a no-op and there is no progress. libduckdb exposes
`duckdb_query_progress`, `duckdb_interrupt`, and a pending-result API, but driving them requires the
query to run on a **different thread** than the one polling progress / issuing cancel — and uni had
no cross-platform primitive for "run a unit of work on a background worker, cancellable and
progress-pollable from another thread" (issue #552). Other uses: REPL ctrl-C, CLI progress bars,
Snowflake/Trino cancel.

## Decision

`wvlet.uni.control.BackgroundTask[A, P]` — the non-Rx twin of the existing `wvlet.uni.rx.RxFiber`.
The body is a **plain side-effecting block** `TaskContext[P] => A` (no effect monad, per uni's
direction); it cooperates with cancellation by polling `ctx.isCancelled` / `ctx.checkCancelled()` and
publishes progress with `ctx.reportProgress(p)`. The caller gets `progress` (poll snapshot),
`progressStream: Rx[P]` (push), `poll`, `await`, `cancel`, `isCancelled`, `isDone`.

The whole state machine is shared (`uni/src/main/.../control/BackgroundTask.scala`); only spawning the
worker and the completion gate are per-platform (`BackgroundTaskCompat`): **JVM/Native** = a daemon
`Thread` (`ThreadUtil.newDaemonThreadFactory`) + a `CountDownLatch` gate; **JS** = inline run + no-op
gate.

## Non-obvious points a future reader would otherwise reverse-engineer

### Node `worker_threads` can't host an arbitrary Scala body → JS runs inline

The issue assumed Node `worker_threads` could run the work. They can't: a `worker_threads.Worker`
runs a *separate JS realm* fed a pre-written JS string + `SharedArrayBuffer` (see
[`2026-05-14-nodejs-sync-http.md`](2026-05-14-nodejs-sync-http.md)) — a Scala.js closure can't cross
into it — and Scala.js is otherwise single-threaded. So a *true* background worker exists only on
**JVM and Native** (the DuckDB targets). On JS the body runs **inline during `start()`** and completes
before it returns: the API is identical, but there is no concurrency, `cancel()` after completion is a
no-op, and `progressStream` only replays the final value to a later subscriber (use the `progress`
snapshot on JS). This was confirmed with the maintainer before implementing.

### Cooperative cancel only; `onCancel` is the in-flight escape hatch

`cancel()` sets a flag the body polls — it does not forcibly interrupt. For a call already blocked in
FFI (the DuckDB case), the body registers `ctx.onCancel(() => duckdb_interrupt(conn))`; `cancel()`
runs those hooks on the caller's thread. (`Thread.interrupt()` on JVM/Native is a possible later
addition.)

### `await` must never hang — catch `Throwable`, signal in `finally`

`NonFatal` does not catch `OutOfMemoryError`/`InterruptedException`/etc. If the worker died on one of
those without setting the result or signalling the gate, `await()` would block forever. So the worker
body is wrapped in `try/finally`: it catches **`Throwable`** (→ `Result.Failure`) and **always**
signals the gate in `finally`. The failure is *recorded, not rethrown* — on JS the body runs inline,
so rethrowing would make `start()` itself throw, diverging from JVM/Native.

### Hooks are drained on completion (no leak, no stale cancel)

`launch`'s `finally` also drains the `onCancel` hooks (`hooksDrained = true; hooks = Nil`). This
releases captured closures (a connection/result set could otherwise leak via a retained task) and
makes a *post-completion* `cancel()` a no-op — firing `duckdb_interrupt` after the query finished
could abort an unrelated query on the same connection. `onCancel` registered after draining runs
immediately only if the task was actually `cancelled` (not if it completed normally).

### The progress snapshot uses `AtomicReference`, not the `RxVar`

`progressStream` is backed by an `RxVar[Option[P]]`, but the `progress` poll snapshot is a separate
`AtomicReference`: `RxVar.get` reads a non-`@volatile` `var` outside any lock and `update` writes it
outside the `synchronized` block, so it gives no cross-thread visibility guarantee. `reportProgress`
updates both.

### `progressStream` uses `flatMap`, not `filter`, to skip the initial `None`

`RxVar[Option[P]]` replays its current value (`None`) to a new subscriber. Skipping it with
`filter(_.isDefined)` is wrong: a *false* `filter` predicate emits `OnCompletion` downstream
(`RxRunner` `FilterOp`), prematurely ending the stream. `flatMap { Some(p) => Rx.single(p); None =>
Rx.empty }` instead maps `None` to `Rx.empty`, whose inner completion `flatMap` skips — and `RxVar`
holds only the latest value, so an unconsumed `progressStream` doesn't accumulate (no buffering leak,
unlike `Rx.queue`).

## Consequences

- `wvlet/wvlet` can give its DuckDB `SqlConnector` real progress + cancel against one cross-platform
  API; the same primitive serves REPL ctrl-C and CLI progress bars.
- JS is correctness-complete but non-concurrent (documented on `start`/`progressStream`).
- Follow-ups if needed: `Thread.interrupt()` on cancel, worker pooling, per-task timeouts.
