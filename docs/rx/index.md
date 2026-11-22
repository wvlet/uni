# Reactive Streams (Rx)

Uni provides a lightweight reactive streams implementation for handling asynchronous data flows.

## Overview

| Component | Description |
|-----------|-------------|
| [Basics](./basics) | Creating and subscribing to streams |
| [Stream Events](./events) | What a stream emits, how it ends, and how errors work |
| [Operators](./operators) | Transforming and combining streams |
| [Concurrency](./concurrency) | `RxRef`, `RxDeferred`, fibers, and semaphores |

## What is Rx?

Rx (Reactive Extensions) provides a way to work with asynchronous data streams using functional operators like `map`, `filter`, and `flatMap`.

```scala
import wvlet.uni.rx.Rx

// Create a stream of values
val numbers = Rx.fromSeq(1 to 10)

// Transform with operators
val result = numbers
  .filter(_ % 2 == 0)  // Keep even numbers
  .map(_ * 10)         // Multiply by 10
  .subscribe(println)  // Print: 20, 40, 60, 80, 100
```

## Key Concepts

### Rx[A]

A stream that emits values of type `A`:

```scala
val stream: Rx[Int] = Rx.fromSeq(1 to 5)
```

### RxVar[A]

A mutable variable that emits updates:

```scala
val counter = Rx.variable(0)
counter.subscribe(println)

counter := 1  // Emits 1
counter := 2  // Emits 2
```

### RxOption[A]

A stream of optional values:

```scala
val maybeValue = Rx.option(Some("hello"))
maybeValue.subscribe {
  case Some(v) => println(v)
  case None => println("empty")
}
```

## Quick Examples

### Interval Timer

```scala
import java.util.concurrent.TimeUnit

val ticker = Rx.interval(1, TimeUnit.SECONDS)
  .take(5)
  .subscribe(i => println(s"Tick: ${i}"))

// Prints: Tick: 0, Tick: 1, ... Tick: 4
```

### Combining Streams

```scala
val a = Rx.variable(1)
val b = Rx.variable(2)

a.join(b)
  .map((x, y) => x + y)
  .subscribe(sum => println(s"Sum: ${sum}"))

a := 10  // Prints: Sum: 12
b := 20  // Prints: Sum: 30
```

### Error Handling

```scala
Rx.single(riskyOperation())
  .recover {
    case _: NetworkError => fallbackValue
  }
  .subscribe(handleResult)
```

## Package

```scala
import wvlet.uni.rx.*
```
