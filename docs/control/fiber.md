# Fiber

`Fiber` runs plain blocking code concurrently and lets you wait for the result.
There is no effect type and no compiler plugin: the body is ordinary Scala code
that may call blocking APIs, such as the sync HTTP client, JDBC, or file I/O.

```scala
import wvlet.uni.control.Fiber

def fetch(id: Int): String = ...   // any blocking call

val a = Fiber { fetch(1) }         // starts now
val b = Fiber { fetch(2) }
val (x, y) = (a.join(), b.join())  // waits; rethrows the body's exception
```

`Fiber` is available on the **JVM** and **Scala Native**. Scala.js has a single
thread, so a blocking `join()` cannot wait there; use [`RxFiber`](../rx/concurrency#rxfiber)
and `Rx.parallel` instead.

## How lightweight is a fiber?

Fibers run on `RxScheduler.blocking`:

| Platform | Each running fiber is |
|----------|-----------------------|
| JVM, JDK 24+ | a virtual thread, so thousands of blocked fibers are cheap |
| JVM, JDK 17–23 | a pooled platform (OS) thread |
| Scala Native | a pooled OS thread |

On JDK 21–23 a virtual thread that blocks inside `synchronized` pins its carrier
thread, so Uni uses virtual threads only from JDK 24. Fibers never run on the
compute pool (`RxScheduler.default`), so a blocked fiber cannot starve CPU work.

## Running many at once

```scala
// Results in input order, at most 16 running at a time
val pages = Fiber.parMap(urls, maxConcurrency = 16)(fetch)

// Two different computations
val (user, orders) = Fiber.par(loadUser(id), loadOrders(id))

// The first to succeed wins; the others are cancelled
val fastest = Fiber.race(fetchFromPrimary(), fetchFromReplica())
```

- `parMap` requires `maxConcurrency` and starts a fiber only when a slot frees
  up, so it never holds more threads than the limit.
- `par` and `parMap` stop at the first failure: they cancel the rest and rethrow it.
- `race` fails only when every body fails. It rethrows the last failure, with the
  others attached as suppressed exceptions. `race(bodies: Seq[() => A])` takes
  any number of bodies.

## Scopes

`Fiber.scope` groups fibers so that none outlives the block:

```scala
Fiber.scope { s =>
  val a = s.fork(fetch(1))
  val b = s.fork(fetch(2))
  a.join() + b.join()
}
```

- `scope` returns only after every fork has finished.
- The first fork that fails cancels the others, and `scope` rethrows its exception.
- If the block itself throws, the forks are cancelled too.
- Cancelling a fiber that is waiting in a `scope` cancels that scope's forks.
- `fork` on a scope that has already returned throws `IllegalStateException`.

`par`, `parMap`, and `race` are built on the same rules.

## Cancellation

`fiber.cancel()` interrupts the fiber's thread. After that, `join()` throws
`FiberCancelledException`, even if the body caught the interrupt and returned a
value. Cancelling a fiber that already finished does nothing.

An interrupt stops `Thread.sleep`, `join`, the JDK `HttpClient`, NIO channels,
and socket reads on virtual threads. It does **not** stop:

- FFI calls, such as Scala Native's curl HTTP client or a native database driver
- JDBC calls, or platform-thread socket reads before JDK 24

For those, register a hook that aborts the call. It runs on the thread that calls
`cancel()`:

```scala
Fiber {
  val stmt = conn.createStatement()
  Fiber.onCancel(() => stmt.cancel())
  stmt.executeQuery(sql)
}
```

A CPU-bound loop checks for cancellation itself:

```scala
Fiber {
  for chunk <- chunks do
    Fiber.checkCancelled()   // throws FiberCancelledException once cancelled
    process(chunk)
}
```

## Fiber, RxFiber, or BackgroundTask?

| Use | When |
|-----|------|
| `Fiber` | Plain blocking code on the JVM or Native: fan out calls, wait for results |
| [`RxFiber`](../rx/concurrency#rxfiber) | Code written as `Rx` pipelines, or code that must also run on Scala.js |
| [`BackgroundTask`](./background-task) | One long worker that reports progress, on every platform |

`BackgroundTask` keeps a dedicated platform thread per task, which suits work
that blocks inside native code, such as a DuckDB query.
