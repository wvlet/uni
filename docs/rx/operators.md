# Stream Operators

uni provides a rich set of operators for transforming and combining reactive streams.

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
5. **Clean up** subscriptions to avoid leaks
