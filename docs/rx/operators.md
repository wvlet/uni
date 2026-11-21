# Stream Operators

Uni provides a rich set of operators for transforming and combining reactive streams.

## Transformation

### map

Transform each value:

```scala
val doubled = numbers.map(_ * 2)
```

### flatMap

Transform to another stream:

```scala
val expanded = numbers.flatMap(n => Rx.fromSeq(1 to n))
```

### filter

Keep matching values:

```scala
val evens = numbers.filter(_ % 2 == 0)
```

### transform

Handle both success and failure:

```scala
val handled = stream.transform {
  case Success(v) => processValue(v)
  case Failure(e) => handleError(e)
}
```

## Combining Streams

### zip

Combine when both emit (waits for pairs):

```scala
val a = Rx.fromSeq(1 to 3)
val b = Rx.fromSeq("a", "b", "c")

a.zip(b).subscribe { (num, letter) =>
  println(s"${num}-${letter}")
}
// 1-a, 2-b, 3-c
```

### join

Combine on any change (latest values):

```scala
val x = Rx.variable(1)
val y = Rx.variable(2)

x.join(y).subscribe { (a, b) =>
  println(s"${a} + ${b} = ${a + b}")
}

x := 10  // Prints: 10 + 2 = 12
y := 20  // Prints: 10 + 20 = 30
```

### concat

Chain streams sequentially:

```scala
val first = Rx.fromSeq(1 to 3)
val second = Rx.fromSeq(4 to 6)

first.concat(second).subscribe(println)
// 1, 2, 3, 4, 5, 6
```

## Error Handling

### recover

Replace errors with values:

```scala
val safe = risky.recover {
  case _: NetworkError => defaultValue
}
```

### recoverWith

Replace errors with another stream:

```scala
val withFallback = primary.recoverWith {
  case _: NetworkError => fallbackStream
}
```

### transformFailure

Transform specific errors:

```scala
val mapped = stream.transformFailure {
  case e: IOException => new NetworkException(e)
}
```

## Flow Control

### take

Limit emissions:

```scala
val first5 = stream.take(5)
```

### throttleFirst

Emit first in time window:

```scala
import java.util.concurrent.TimeUnit

// Prevent double-clicks
val throttled = clicks.throttleFirst(500, TimeUnit.MILLISECONDS)
```

### throttleLast / sample

Emit last in time window:

```scala
val sampled = stream.sample(100, TimeUnit.MILLISECONDS)
```

### timeout

Fail if no value in time:

```scala
val withTimeout = stream.timeout(5, TimeUnit.SECONDS)
```

### sampleFrame

Emit at most one value per frame, the last one of the frame. In a browser a
frame is a `requestAnimationFrame` callback, so a burst of updates renders once:

```scala
val position = pointer.sampleFrame
```

When the source ends, a value still waiting for its frame is emitted first.
`Rx.everyFrame` emits the frame number (0, 1, 2, ...) on every frame, and
`Rx.nextFrame` emits `()` once on the next frame. Outside a browser a frame is a
16 ms timer.

## Testing Time

The time operators (`Rx.interval`, `Rx.timer`, `throttleFirst`, `throttleLast`,
`sample`, `timeout`) read the clock from a `given RxTimeProvider`, and the frame
operators from a `given RxFrameProvider`. Put a manual one in scope and move
time by hand. Due tasks run on the calling thread, so the test needs no sleeps:

```scala
import wvlet.uni.rx.*

given time: ManualTimeProvider = RxTimeProvider.manual()

val ticks = Seq.newBuilder[Long]
Rx.interval(100, TimeUnit.MILLISECONDS).take(3).run(ticks += _)
time.advance(300, TimeUnit.MILLISECONDS)
ticks.result() // Seq(100, 100, 100)

given frames: ManualFrameProvider = RxFrameProvider.manual()
val counter = Rx.variable(0)
val out     = Seq.newBuilder[Int]
counter.sampleFrame.subscribe(out += _)
counter := 1
counter := 2
frames.advanceFrame() // out: Seq(2)
```

The provider is captured when the operator is built. `RxFrameProvider.fromTime(16)`
makes frames from a time provider, manual or not.

## Finding Leaked Subscriptions

`RxTracker` records the subscriptions that are still active: neither cancelled
nor ended by `OnError` / `OnCompletion`. A `subscribe` keeps running until it is
cancelled, so a binding that nobody cancels shows up here:

```scala
val tracker = RxTracker()
val c = tracker.trace {
  counter.map(_ * 2).subscribe(render)
}
tracker.activeSubscriptions // Seq(RxSubscription(#1 runContinuously MapOp at MyView.scala:12))
c.cancel
tracker.activeCount         // 0
```

`trace` tracks what its block starts on the current thread. To watch the whole
process, for example from a debug panel, use `RxTracker.startGlobal()` and
`RxTracker.stopGlobal()`. Pass `captureStackTrace = false` to skip the stack
traces that locate each subscription's `origin`. In a `UniTest`,
`assertNoRxLeaks { ... }` fails when its block leaves a subscription active.

## Caching

### cache

Cache the last value:

```scala
val cached = expensive.cache

cached.subscribe(println)  // Computes
cached.subscribe(println)  // Uses cached
```

### expireAfterWrite

TTL cache:

```scala
val ttlCache = stream.cache.expireAfterWrite(5, TimeUnit.MINUTES)
```

## Side Effects

### tap

Observe without modifying:

```scala
val logged = stream.tap(v => logger.debug(s"Value: ${v}"))
```

### tapOn

Observe with Try:

```scala
val logged = stream.tapOn {
  case Success(v) => logger.info(s"Success: ${v}")
  case Failure(e) => logger.error("Failed", e)
}
```

### tapOnFailure

Observe only errors:

```scala
val logged = stream.tapOnFailure { e =>
  logger.error("Error occurred", e)
}
```

### onCancel

Run cleanup when a subscriber cancels before the stream ends, such as aborting a request or telling a
server to stop. It runs at most once per subscription, and not when the stream completes or fails by
itself. `take(n)` cancels its upstream, so it runs `onCancel` too:

```scala
val ticks = stream.onCancel(logger.info("The subscriber left"))
val c     = ticks.run()
c.cancel // logs "The subscriber left"
```

## Starting Values

### startWith

Prepend a value:

```scala
val withDefault = stream.startWith(0)
```

### startWith (Seq)

Prepend multiple values:

```scala
val withDefaults = stream.startWith(Seq(-2, -1, 0))
```

## Aggregation

### scan

Emit a running value: `f(acc, a)` for each upstream value, starting from `init`. `init` itself
isn't emitted; add `startWith(init)` when you need it. Each subscription keeps its own
accumulator:

```scala
Rx.fromSeq(Seq(1, 2, 3)).scan(0)(_ + _) // 1, 3, 6
```

### split

Split an `Rx[Seq[A]]` into one `Rx` per key, so each item can follow its own changes:

```scala
case class Message(id: String, text: String)

val messages = Rx.variable(Seq(Message("1", "hi")))
val rows: Rx[Seq[(String, Rx[Message])]] = messages.split(_.id)
```

- The list emits only when its keys change (added, removed, or reordered). A changed item under
  a kept key goes to that key's `Rx`, which emits only when the value differs (`!=`).
- A key keeps the same `Rx` instance while it stays in the list, so consumers can tell a row
  apart from a new one.
- A key's `Rx` completes when the key leaves the list, when the upstream completes, or when the
  subscription is cancelled. A key that comes back gets a new `Rx`.
- A repeated key keeps only its first item. Each subscription keeps its own key table.

In uni-dom, `each(messages.split(_.id))(_._1)((id, current) => li(current.map(_.text)))`
renders each row once and updates it in place.

### lastOption

Get the last value as Option:

```scala
import wvlet.uni.rx.RxOption

val last: RxOption[Int] = stream.lastOption
```

### toSeq (JVM only)

Collect all values:

```scala
val all: Seq[Int] = Rx.fromSeq(1 to 10).toSeq
```

## Operator Chaining

Combine operators fluently:

```scala
val result = Rx.interval(100, TimeUnit.MILLISECONDS)
  .take(100)
  .filter(_ % 2 == 0)
  .map(_ * 10)
  .throttleLast(500, TimeUnit.MILLISECONDS)
  .tap(v => logger.debug(s"Processing: ${v}"))
  .recover { case _ => -1 }
  .cache
```

## Best Practices

1. **Order matters** - filter before map when possible
2. **Use throttle** for UI events
3. **Cache expensive** computations
4. **Handle errors** at appropriate levels
5. **Clean up** subscriptions to avoid leaks, and check with `RxTracker`
