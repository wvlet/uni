# Rx Basics

Learn the fundamentals of reactive streams in uni.

## Creating Streams

### Single Value

```scala
import wvlet.uni.rx.Rx

// Immediate value
val single = Rx.single(42)

// Lazy evaluation
val deferred = Rx.single {
  println("Computing...")
  expensiveComputation()
}
```

### From Sequence

```scala
val numbers = Rx.fromSeq(1 to 10)
val items = Rx.sequence("a", "b", "c")
```

### Empty Stream

```scala
val empty = Rx.empty[Int]
```

### From Try

```scala
import scala.util.{Success, Failure}

val fromSuccess = Rx.fromTry(Success(42))
val fromFailure = Rx.fromTry(Failure(Exception("error")))
```

### From Future

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val fromFuture = Rx.future(Future {
  Thread.sleep(100)
  "result"
})
```

## Reactive Variables

### Creating Variables

```scala
val counter = Rx.variable(0)
val name = Rx.variable("Alice")
```

### Updating Variables

```scala
counter := 5        // Set to 5
counter.update(_ + 1) // Increment
```

### Subscribing to Changes

```scala
counter.subscribe { value =>
  println(s"Counter is now: ${value}")
}

counter := 1  // Prints: Counter is now: 1
counter := 2  // Prints: Counter is now: 2
```

## Subscribing to Streams

### Basic Subscription

```scala
val cancelable = Rx.fromSeq(1 to 5).subscribe { value =>
  println(value)
}
```

### Canceling Subscriptions

```scala
val cancelable = stream.subscribe(println)

// Later...
cancelable.cancel
```

### Running to Completion

```scala
// Run and handle each value
stream.run { value =>
  processValue(value)
}
```

### Awaiting Result (JVM only)

```scala
val result = Rx.single(computeValue()).await
```

## Stream Events

Streams emit three types of events:

1. **OnNext(value)** - A value is emitted
2. **OnError(exception)** - An error occurred
3. **OnCompletion** - Stream finished

```scala
import wvlet.uni.rx.{OnNext, OnError, OnCompletion}

stream.run {
  case OnNext(value) => println(s"Value: ${value}")
  case OnError(e) => println(s"Error: ${e.getMessage}")
  case OnCompletion => println("Done")
}
```

## Timers and Intervals

### One-time Delay

```scala
import java.util.concurrent.TimeUnit

// Emit after 1 second
val delayed = Rx.timer(1, TimeUnit.SECONDS)
delayed.subscribe(_ => println("Timer fired!"))
```

### Periodic Interval

```scala
// Emit every 500ms
val ticker = Rx.interval(500, TimeUnit.MILLISECONDS)
  .take(10)  // Limit to 10 emissions
  .subscribe(i => println(s"Tick ${i}"))
```

## Option Streams

### Creating RxOption

```scala
val maybeValue = Rx.option(Some(42))
val noValue = Rx.none
```

### Converting to RxOption

```scala
val optionStream = Rx.variable[Option[Int]](None).toOption
```

## Queues

Push values manually:

```scala
val queue = Rx.queue[Int]()

queue.subscribe(println)

queue.put(1)  // Prints: 1
queue.put(2)  // Prints: 2
queue.put(3)  // Prints: 3
```

## Best Practices

1. **Cancel subscriptions** when no longer needed
2. **Use variables** for mutable state
3. **Prefer lazy evaluation** with `Rx.single`
4. **Handle errors** with `recover`
5. **Limit infinite streams** with `take`
