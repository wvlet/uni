# Result

`Result[A]` is Uni's standard container for "a value that may have failed". It
is a Rust-flavored counterpart to `scala.util.Try`: the error channel is
always a `Throwable`, and the combinators match [`Rx`](/rx/)'s vocabulary so
interop is mechanical.

```scala
import wvlet.uni.util.Result
```

`Result` lives in `uni-core` and is available on the JVM, Scala.js, and
Scala Native.

## When to Use Result

Use `Result[A]` at API boundaries where failure is an expected outcome that
callers need to handle as a value — parsers, external lookups, settled
`Rx` evaluations — while keeping `throw` / `try` / `catch` inside internal
code. Uni deliberately does **not** introduce a type-level error channel
(like ZIO's `E` parameter): Scala's exception mechanism already carries the
error type, and fixing `E = Throwable` keeps the shape single-parameter like
`Option[A]` and `Rx[A]`.

| Situation                                              | Recommended type        |
|--------------------------------------------------------|-------------------------|
| Internal helper that can throw                         | Exceptions + `NonFatal` |
| Public API that distinguishes "failed" from "returned" | `Result[A]`             |
| Async stream that produces zero or more values         | `Rx[A]`                 |

## Constructing a Result

`Result.apply` evaluates a block and wraps any non-fatal exception. Fatal
errors (e.g. `InterruptedException`, `VirtualMachineError`) escape — this
matches `scala.util.control.NonFatal`.

```scala
val parsed: Result[Int] = Result(Integer.parseInt("42"))
// Result.Success(42)

val failed: Result[Int] = Result(Integer.parseInt("not-a-number"))
// Result.Failure(java.lang.NumberFormatException: For input string: "not-a-number")
```

Direct constructors and conversions are also available:

```scala
Result.success(1)                           // Result.Success(1)
Result.failure(new RuntimeException("no"))  // Result.Failure(...)

Result.fromTry(scala.util.Try("parse this".toInt))
Result.fromEither(Right[Throwable, Int](42))
Result.fromOption(Option.empty[Int], new NoSuchElementException("empty"))
```

## Composing Results

`map`, `flatMap`, and `filter` work like any other monadic container, and
they **catch any exceptions thrown inside the supplied function**, turning
them into `Failure`. This is the "Rx-like propagation" property: exceptions
never bypass the wrapper.

```scala
val r: Result[Int] =
  for
    a <- Result(Integer.parseInt("10"))
    b <- Result(Integer.parseInt("20"))
    if a + b > 0
  yield a + b
// Result.Success(30)
```

A `Failure` anywhere in the chain short-circuits the rest — no need to
thread the error manually.

## Handling Failure

`recover` and `recoverWith` mirror the same-named methods on `Rx` and only
fire when the partial function matches the wrapped throwable:

```scala
val recovered: Result[Int] =
  Result(Integer.parseInt("nope")).recover {
    case _: NumberFormatException => 0
  }
// Result.Success(0)

val retried: Result[Int] =
  Result.failure(new java.io.IOException("timeout")).recoverWith {
    case _: java.io.IOException => Result(42)
  }
// Result.Success(42)
```

Other handlers:

```scala
Result.failure(boom).mapError(new IllegalStateException(_))  // rewrap
result.fold(onFailure = _.getMessage, onSuccess = _.toString)
result.getOrElse(0)
result.orElse(Result.success(0))
```

## Conversions

`Result` interoperates with the standard Scala types:

```scala
result.toOption     // Option[A] — Failure becomes None
result.toEither     // Either[Throwable, A]
result.toTry        // scala.util.Try[A]
result.get          // A; rethrows on Failure
```

## Rx Interop

`Rx[A].materialize` reifies each upstream event as a `Result[A]`, so errors
are delivered as values instead of terminating the stream:

```scala
import wvlet.uni.rx.Rx

val events: Rx[Result[Int]] =
  Rx.exception[Int](new RuntimeException("boom")).materialize
// Emits Result.Failure(RuntimeException("boom"))
```

To go the other way, use `Rx.fromResult` (or the `toRx` extension on
`Result`, available after importing `wvlet.uni.rx.*`):

```scala
import wvlet.uni.rx.*

val rx1: Rx[Int] = Rx.fromResult(Result.success(1))
val rx2: Rx[Int] = Result.success(1).toRx
```

`Result.Failure(e)` converts to an `Rx` that emits `OnError(e)` — the same
termination signal a thrown exception would produce.
