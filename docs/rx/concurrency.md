# Rx Concurrency

Lightweight concurrency primitives for Rx, inspired by cats-effect. All primitives are non-blocking and work across JVM, Scala.js, and Scala Native.

## RxRef

Atomic mutable reference with lock-free compare-and-swap semantics. Unlike `Rx.variable`, RxRef does **not** emit change events to subscribers — it is purely a concurrent state container.

```scala
import wvlet.uni.rx.{Rx, RxRef}

val ref = RxRef(0)

// Basic get/set
ref.get           // Rx[Int]
ref.set(42)       // Rx[Unit]

// Atomic update
ref.update(_ + 1) // Rx[Unit] — retries on contention

// Get previous value while setting new
ref.getAndSet(10) // Rx[Int] — returns old value

// Get and update atomically
ref.getAndUpdate(_ * 2)  // Rx[Int] — returns old value
ref.updateAndGet(_ * 2)  // Rx[Int] — returns new value

// Atomic modify with result extraction
ref.modify { current =>
  val next = current + 1
  (next, s"was ${current}")  // (newState, result)
}  // Rx[String]

// Compare-and-set
ref.compareAndSet(expected = 10, newValue = 20)  // Rx[Boolean]

// Synchronous access (use with caution)
ref.unsafeGet  // Int
```

## RxDeferred

Single-value synchronization primitive. A `RxDeferred` starts empty and can be completed exactly once. Callers of `get` suspend until a value is available.

```scala
import wvlet.uni.rx.{Rx, RxDeferred}

val deferred = RxDeferred[String]()

// Complete with a value (returns true on first completion)
deferred.complete("done")  // Rx[Boolean]

// Get the value (suspends until completed)
deferred.get  // Rx[String]

// Non-blocking check
deferred.tryGet      // Rx[Option[String]]
deferred.isCompleted // Rx[Boolean]

// Complete with an error
deferred.completeWithError(Exception("failed"))  // Rx[Boolean]
```

### Example: Coordinating Async Work

```scala
val result = RxDeferred[Int]()

// Producer
val producer = Rx {
  val value = computeExpensiveResult()
  result.complete(value)
}

// Consumer (suspends until producer completes)
val consumer = result.get.map { value =>
  println(s"Got result: ${value}")
}
```

## RxFiber

Lightweight fiber for concurrent computation. Fibers are much cheaper than OS threads and are managed by the Rx scheduler.

```scala
import wvlet.uni.rx.{Rx, RxFiber}

// Fork a computation
val fiber: Rx[RxFiber[Int]] = Rx {
  expensiveComputation()
}.start

// Wait for result
fiber.flatMap(_.join)  // Rx[Int]

// Cancel a fiber
fiber.flatMap(_.cancel)  // Rx[Unit]

// Non-blocking poll
fiber.flatMap(_.poll)  // Rx[Option[Try[Int]]]

// Check cancellation
fiber.flatMap(_.isCancelled)  // Rx[Boolean]
```

### Extension Methods

```scala
import wvlet.uni.rx.Rx

val computation = Rx { heavyWork() }

// Fork to default scheduler
computation.start              // Rx[RxFiber[A]]

// Fork to a specific scheduler
computation.startOn(scheduler) // Rx[RxFiber[A]]

// Fire and forget
computation.startAndForget     // Rx[Unit]

// Run on blocking scheduler (for file/network I/O)
computation.evalOnBlocking     // Rx[A]
```

## RxSemaphore

Permit-based concurrency control. Limits the number of concurrent operations without blocking OS threads.

```scala
import wvlet.uni.rx.RxSemaphore

// Create with 3 permits
val sem = RxSemaphore(3)

// Bracket pattern (recommended)
sem.withPermit {
  Rx { accessSharedResource() }
}  // Rx[A] — acquire before, release after (even on error)

// Multiple permits
sem.withPermitN(2) {
  Rx { heavyOperation() }
}

// Manual acquire/release
sem.acquire      // Rx[Unit] — suspends if no permits available
sem.release      // Rx[Unit]

// Non-blocking
sem.tryAcquire   // Rx[Boolean]

// Inspection
sem.available    // Rx[Long] — current available permits
sem.waiting      // Rx[Int] — number of waiting acquires
```

### Example: Rate-Limiting Concurrent Requests

```scala
val limiter = RxSemaphore(5) // Max 5 concurrent requests

def fetchUrl(url: String): Rx[String] =
  limiter.withPermit {
    Rx { httpClient.get(url) }
  }

// All URLs are fetched with at most 5 concurrent requests
val results = urls.map(fetchUrl)
```

## RxScheduler

Platform-specific execution environment for Rx computations.

```scala
import wvlet.uni.rx.RxScheduler

// Platform-dependent default scheduler
val default = RxScheduler.default

// Blocking I/O scheduler (cached thread pool)
val blocking = RxScheduler.blocking

// Custom parallelism
val custom = RxScheduler(parallelism = 4)

// Schedule delayed execution
val cancel = default.schedule(1000, java.util.concurrent.TimeUnit.MILLISECONDS) {
  println("delayed!")
}

// Periodic execution
val periodic = default.scheduleAtFixedRate(
  initialDelay = 0,
  period = 5000,
  unit = java.util.concurrent.TimeUnit.MILLISECONDS
) {
  println("tick")
}

// Cancel scheduled tasks
cancel.cancel()
periodic.cancel()
```

### Platform Behavior

| Platform | Default Scheduler | Blocking Scheduler |
|----------|------------------|--------------------|
| JVM | Work-stealing thread pool | Cached thread pool |
| Scala.js | Microtask queue | Same (single-threaded) |
| Scala Native | Fixed-size thread pool | Fixed-size thread pool |

## Best Practices

1. **Use `withPermit`** over manual acquire/release — it guarantees cleanup on errors
2. **Use `RxDeferred`** for one-time signaling between producers and consumers
3. **Use `evalOnBlocking`** for file I/O, network calls, and other blocking operations
4. **Keep fibers lightweight** — avoid long-running blocking calls inside fibers
5. **Use `RxRef`** for shared mutable state; use `Rx.variable` when you need change notifications
