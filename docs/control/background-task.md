# Background Task

`BackgroundTask[A, P]` runs a unit of work on a background worker that is
**cooperatively cancellable** and **progress-pollable** from another thread. It
is the non-`Rx` counterpart to a fiber: the body is a plain side-effecting block
(no effect monad), so it fits long-running, blocking work — a database query, a
file scan, a REPL evaluation — that you want to cancel or report progress on.

- `A` — the result type the body returns
- `P` — the progress type the body reports

```scala
import wvlet.uni.control.BackgroundTask

val task = BackgroundTask.start[Int, Int] { ctx =>
  var sum = 0
  for i <- 1 to 100 do
    ctx.checkCancelled()        // abort cooperatively if cancelled
    sum += i
    ctx.reportProgress(i)       // publish progress (0–100)
  sum
}

val result = task.await()       // Result[Int] — blocks until done
println(result.getOrElse(0))    // 5050
```

## Starting a Task

`BackgroundTask.start` takes a body `TaskContext[P] => A` and returns immediately
with a handle:

```scala
def start[A, P](body: TaskContext[P] => A): BackgroundTask[A, P]
```

On **JVM** and **Scala Native** the body runs on a daemon thread, so the caller
can poll, cancel, and block concurrently. On **Scala.js** there are no threads,
so the body runs **inline during `start`** and has already completed when `start`
returns — see [Platform behavior](#platform-behavior).

## The Task Context (body side)

The body receives a `TaskContext[P]` to cooperate with the caller:

| Method | Purpose |
|--------|---------|
| `isCancelled: Boolean` | `true` once `cancel()` was called — poll it in loops |
| `checkCancelled(): Unit` | Throws `TaskCancelledException` if cancelled — call at safe points |
| `reportProgress(p: P): Unit` | Publish the latest progress to the caller |
| `onCancel(hook: () => Unit): Unit` | Register a hook to interrupt an in-flight blocking call |

Cancellation is **cooperative**: `cancel()` only sets a flag. The body must poll
`isCancelled` / call `checkCancelled()` at safe points (typically loop
iterations) for cancellation to take effect.

For a call already blocked in native/FFI code that can't poll a flag, register an
`onCancel` hook — it runs on the caller's thread when `cancel()` is invoked, and
is the escape hatch for interrupting work in flight:

```scala
val task = BackgroundTask.start[QueryResult, Unit] { ctx =>
  val handle = nativeQuery.start()
  ctx.onCancel(() => nativeQuery.interrupt(handle))  // e.g. duckdb_interrupt
  nativeQuery.awaitResult(handle)
}
```

## The Task Handle (caller side)

| Method | Blocks? | Returns |
|--------|---------|---------|
| `progress: Option[P]` | no | latest reported progress, or `None` |
| `progressStream: Rx[P]` | no | push stream of progress, completing when the task ends |
| `poll: Option[Result[A]]` | no | `None` while running, `Some(result)` once finished |
| `await(): Result[A]` | yes | the terminal result (blocks until done) |
| `cancel(): Unit` | no | signals cooperative cancellation, runs `onCancel` hooks |
| `isCancelled: Boolean` | no | whether `cancel()` was called |
| `isDone: Boolean` | no | whether the task has terminated |

### Results

`await()` and `poll` return a [`Result[A]`](../core/result) — `Success(value)`
or `Failure(error)`. The body's failures are **recorded, not rethrown**, so
`await()` always returns rather than throwing:

```scala
task.await() match
  case Result.Success(value) => println(s"Done: ${value}")
  case Result.Failure(e)     => println(s"Failed: ${e.getMessage}")
```

A cancelled task whose body calls `checkCancelled()` completes as a
`Failure(TaskCancelledException)`.

### Polling vs. streaming progress

Use the non-blocking `progress` snapshot to render a progress bar from another
thread:

```scala
while !task.isDone do
  task.progress.foreach(p => render(p))
  Thread.sleep(100)
```

Or subscribe to `progressStream` for a push feed that completes with the task:

```scala
task.progressStream.subscribe { p =>
  println(s"progress: ${p}")
}
```

The stream holds only the latest value (it does not buffer), so an unconsumed
`progressStream` won't accumulate updates.

## Cancellation

```scala
val task = BackgroundTask.start[Unit, Int] { ctx =>
  while !ctx.isCancelled do
    doOneStep()
}

// later, from another thread:
task.cancel()
task.await()   // returns once the body observes the flag and exits
```

`cancel()` after the task has already completed is a **no-op** — `onCancel`
hooks are drained on completion, so a late cancel can't fire them against an
unrelated operation.

## Platform behavior

| Platform | Worker | Concurrency |
|----------|--------|-------------|
| JVM | daemon thread | full — poll / cancel / `await` from another thread |
| Scala Native | daemon thread | full |
| Scala.js | inline during `start` | none — body completes before `start` returns |

On Scala.js the API is identical, but because the body runs inline:

- the task is already done when `start` returns, so `cancel()` is effectively a
  no-op;
- a `progressStream` subscriber attached afterwards sees only the **final**
  reported value, not a live feed — use the `progress` snapshot instead.

For the full rationale (why JS can't host a true worker thread, the
catch-`Throwable`-and-signal contract that keeps `await` from hanging, and the
`progressStream` design), see the
[ADR](https://github.com/wvlet/uni/blob/main/adr/2026-06-20-background-task.md).
