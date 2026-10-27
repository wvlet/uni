# Rx: reactive streams and concurrency

`wvlet.uni.rx` runs on JVM, Scala.js, and Scala Native. The blocking helpers
`rx.await` and `rx.toSeq` exist only on JVM and Native; on Scala.js they throw
`UnsupportedOperationException`. In a `UniTest`, return the `Rx` from the test
body instead of awaiting it (see [testing.md](testing.md)).

Full reference: https://wvlet.org/uni/rx/operators.md and https://wvlet.org/uni/rx/concurrency.md

## Sources and running

```scala
import wvlet.uni.rx.{Cancelable, Rx}
import java.util.concurrent.TimeUnit

val one   = Rx.single { 40 + 2 }           // lazy, evaluated per run
val many  = Rx.fromSeq(1 to 5)             // also Rx.sequence(1, 2, 3), Rx.empty[Int]
val err   = Rx.exception[Int](IllegalStateException("boom"))
val ticks = Rx.interval(100, TimeUnit.MILLISECONDS).take(3) // 3 ticks, then completes

val counter = Rx.variable(0)               // RxVar: holds a value, never completes
counter := 1
counter.update(_ + 1)                      // counter.get == 2

val queue = Rx.queue[String]()             // push-based source
queue.put("a")                             // not queue.add(...): add takes an RxEvent
queue.stop()                               // completes the stream

val c: Cancelable = many.map(_ * 2).run { v => println(v) } // stops at completion or error
c.cancel                                    // stop early
val sub = counter.subscribe { v => println(v) } // keeps running across updates
sub.cancel

val two: Int = one.map(_ / 21).await       // JVM/Native only: first value
val all: Seq[Int] = many.toSeq             // JVM/Native only: values emitted synchronously
```

`toSeq` does not wait: on a timer or other async source it returns what was
emitted so far (often nothing). Use `await` for the first value, or return the
`Rx` from a test body.

- `Rx(v)` and `Rx { ... }` (deprecated) are `Rx.variable(v)`: the argument is evaluated
  immediately and the result never completes. For a deferred computation use
  `Rx.single { ... }`.
- `Rx.interval` emits the milliseconds elapsed since the previous tick, not a
  counter; `Rx.timer(n, unit)` emits `0L` once.
- `Rx.future(f)` needs a `given ExecutionContext`.

## Operators

```scala
import wvlet.uni.rx.Rx
import java.util.concurrent.TimeUnit

val nums = Rx.fromSeq(1 to 10)

nums.map(_ * 2)
nums.filter(_ % 2 == 0)                    // also: when, withFilter
nums.flatMap(n => Rx.fromSeq(Seq(n, n)))   // waits for each nested stream
nums.take(3)                               // completes after the 3rd value
nums.zip(Rx.fromSeq(Seq("a", "b")))        // pairs: (1,a), (2,b)
Rx.variable(1).join(Rx.variable("x"))      // latest of each on any change
nums.concat(Rx.single(11))
nums.startWith(0)
nums.lastOption                            // RxOption[Int]
nums.tap(v => println(v))                  // also tapOn, tapOnFailure
nums.cache                                 // RxCache: replays the last value
nums.timeout(5, TimeUnit.SECONDS)          // fails with Rx.TimeoutException
nums.throttleFirst(500)                    // also throttleLast / sample (milliseconds by default)

Rx.exception[Int](IllegalStateException("x")).recover { case _: IllegalStateException => 0 }
Rx.exception[Int](IllegalStateException("x")).recoverWith { case _ => Rx.single(0) }
nums.transformFailure { case e: IllegalStateException => IllegalArgumentException(e) }
nums.transform {                           // Try[A] => B
  case scala.util.Success(v) => v
  case scala.util.Failure(_) => -1
}
```

Completion is terminal: `filter` does not complete on a rejected value,
`flatMap` completes only after its nested stream, and `take(n)` cancels its
upstream after the nth value.

## Concurrency primitives

`RxRef`, `RxDeferred`, `RxSemaphore`, `RxBoundedQueue`, fibers, and `RxParallel`
are in the `uni` module (not `uni-core`). Every operation returns an `Rx`; nothing
happens until it runs.

```scala
import wvlet.uni.rx.*

val ref = RxRef(0)                         // atomic cell, no change events
ref.update(_ + 1)                          // Rx[Unit]
ref.modify(n => (n + 1, s"was ${n}"))      // Rx[String]

val d = RxDeferred[String]()               // completes once
val waiter: Rx[String] = d.get             // suspends until completed
d.complete("done")                         // Rx[Boolean], true the first time

val sem = RxSemaphore(2)
def limited(i: Int): Rx[Int] = sem.withPermit(Rx.single(i * 10)) // releases on error too

val q = RxBoundedQueue[Int](capacity = 16) // offer waits when full, take when empty
q.offer(1).flatMap(_ => q.take)            // Rx[Int]

val fiber = Rx.single(21 * 2).start        // Rx[RxFiber[Int]] on RxScheduler.default
fiber.flatMap(_.join)                      // Rx[Int]; also cancel, poll
Rx.single(42).evalOnBlocking               // run blocking I/O on RxScheduler.blocking

RxParallel.parTraverseN(4)(1 to 10)(limited) // Rx[Seq[Int]], order kept
RxParallel.race(Rx.single(1), Rx.single(2))  // first to finish wins

val res = RxResource.make(Rx.single("conn"))(c => Rx.single(println(s"close ${c}")))
res.use(c => Rx.single(c.length))          // release runs after use, even on error
```

A fiber takes the first value of its `Rx`; an `Rx` that completes empty fails
the fiber with `IllegalStateException`.
