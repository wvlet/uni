---
description: What an Uni Rx stream emits and in what order. OnNext carries values, OnError reports an error the stream continues past, and a stream ends at most once, with OnCompletion or OnFailure.
---

# Stream Events

An Rx stream talks to its subscriber with four events. `OnNext` carries a value, and `OnError`
reports an error that the stream continues past. The stream ends at most once: with
`OnCompletion` when it completed, or with `OnFailure` when it failed. Read this page when you
write a handler that sees events, rather than just values, or when you need to know whether and
how a stream ends.

```scala
import wvlet.uni.rx.*

RxRunner.runContinuously(Rx.fromSeq(Seq(1, 2, 3))) {
  case OnNext(v)    => println(s"value: ${v}")
  case OnError(e)   => println(s"error, the stream goes on: ${e.getMessage}")
  case OnCompletion => println("completed")
  case OnFailure(e) => println(s"failed: ${e.getMessage}")
}
// value: 1
// value: 2
// value: 3
// completed
```

## The Four Events

| Event | Meaning | What comes after it |
|-------|---------|---------------------|
| `OnNext(v)` | A value | More events |
| `OnError(e)` | An error the stream continues past, such as a throwing `map` function | More events |
| `OnCompletion` | The stream completed | Nothing |
| `OnFailure(e)` | The stream failed, for example because its source failed | Nothing |

`OnCompletion` and `OnFailure` share the parent type `OnEnd`. Match `case end: OnEnd` when you
only need to know that the stream ended.

## The Rules

1. **Values and errors arrive in any order, then at most one end.** A stream's events always
   follow the shape `(OnNext | OnError)* (OnCompletion | OnFailure)?`.
2. **Nothing follows an end.** After `OnCompletion` or `OnFailure`, the subscription is over.
3. **A stream can run forever.** An `Rx.variable` or an `Rx.interval` has no end until you stop
   it, so cancel the `Cancelable` that `subscribe` returns when you are done with it.
4. **An end carries no value.** Values arrive only as `OnNext`. For the last value, use
   `lastOption`, which emits `Some(last)`, or `None` for an empty stream, before it completes.

## Two Kinds of Errors

An error that happens inside the stream doesn't end it. A throwing `map` reports an `OnError`, and
the next value still arrives:

```scala
val parsed = Rx.fromSeq(Seq("1", "x", "3")).map(_.toInt)
// OnNext(1), OnError(NumberFormatException), OnNext(3), OnCompletion
```

A failed source ends the stream with `OnFailure`, because nothing more can come from it. Examples
are `Rx.exception(e)`, a throwing `Rx.single { ... }`, a failed `Rx.future`, and
`RxVar.setException(e)`:

```scala
val failed = Rx.exception[Int](IllegalStateException("unavailable"))
// OnFailure(IllegalStateException)
```

`recover` handles both kinds. For an `OnError`, it emits the replacement value and the stream goes
on. For an `OnFailure`, it emits the replacement value and completes:

```scala
val recovered = parsed.recover { case _: NumberFormatException => 0 }
// OnNext(1), OnNext(0), OnNext(3), OnCompletion

val fallback = failed.recover { case _: IllegalStateException => -1 }
// OnNext(-1), OnCompletion
```

Try a throwing `map` in the gallery, where each lane draws the events of one stream:

<UiGallery story="rx-errors/resume" embed height="520px" />

## Event Traces

What a handler of `RxRunner.runContinuously` receives, where `boom` is a `map` that throws on `2`:

| Stream | Events |
|--------|--------|
| `Rx.fromSeq(Seq(1, 2, 3))` | `OnNext(1)` `OnNext(2)` `OnNext(3)` `OnCompletion` |
| `Rx.empty[Int]` | `OnCompletion` |
| `Rx.single(1)` | `OnNext(1)` `OnCompletion` |
| `Rx.exception(e)` | `OnFailure(e)` |
| `Rx.fromSeq(Seq(1, 2, 3)).map(boom)` | `OnNext(1)` `OnError(e)` `OnNext(3)` `OnCompletion` |
| `Rx.fromSeq(Seq(1, 2, 3)).filter(_ > 10)` | `OnCompletion` |
| `Rx.fromSeq(Seq(1, 2, 3)).take(2)` | `OnNext(1)` `OnNext(2)` `OnCompletion` |
| `Rx.fromSeq(Seq(1, 2, 3)).lastOption` | `OnNext(Some(3))` `OnCompletion` |
| `Rx.fromSeq(Seq(1, 2)).concat(Rx.single(3))` | `OnNext(1)` `OnNext(2)` `OnNext(3)` `OnCompletion` |
| `Rx.exception(e).concat(Rx.single(3))` | `OnFailure(e)` |
| `Rx.fromSeq(Seq(1, 2, 3)).map(boom).materialize` | `OnNext(Success(1))` `OnNext(Failure(e))` `OnCompletion` |

`filter` doesn't complete a stream when it rejects a value. It reports the end of its upstream,
so it completes here only because the sequence did. `concat` starts its second stream only after
the first one completes. `materialize` turns events into `Result` values, and it ends at the first
error.

## Variables

An `Rx.variable` sends its current value to each new subscriber. After that, it sends each new
value; setting the same value again sends nothing (use `forceSet` to send it anyway). It has no
end until you call `stop()` or `setException(e)`:

```scala
val counter = Rx.variable(0)
val c = RxRunner.runContinuously(counter)(ev => println(ev))
// OnNext(0)
counter := 1 // OnNext(1)
counter := 1 // nothing: the value did not change
counter.stop() // OnCompletion
```

`counter.setException(e)` ends the variable with `OnFailure(e)` instead.

## Ways to Run a Stream

Each entry point decides what your code sees:

| Entry point | Your code receives | At an `OnError` | At the end |
|-------------|--------------------|-----------------|------------|
| `rx.subscribe(f)` | Values | Logged as a warning, and the stream goes on | Stops; an `OnFailure` is logged as a warning |
| `RxRunner.runContinuously(rx)(f)` | Every `RxEvent` | Delivered, and the stream goes on | Delivered, then stops |
| `RxRunner.run(rx)(f)` | `OnNext`, `OnCompletion`, `OnFailure` | Delivered as `OnFailure(e)`, and the run stops | Delivered, then stops |
| `RxRunner.runOnce(rx)(f)` | The first event only | Delivered as `OnFailure(e)` if it comes first | Delivered if it comes first |
| `rx.run(f)` | Values | Thrown, and the run stops | Stops, and an `OnFailure` is thrown |
| `rx.toSeq`, `rx.await` (JVM and Native) | All values, or the first one | Thrown | Returns |

`RxRunner.run` suits one-shot work such as a request, where the first error is the answer. Its
handler type, `RxRunEvent`, has no `OnError` case, so the compiler checks three cases:

```scala
RxRunner.run(parsed) {
  case OnNext(v)    => println(s"value: ${v}")
  case OnCompletion => println("done")
  case OnFailure(e) => println(s"failed: ${e.getMessage}")
}
// value: 1
// failed: For input string: "x"
```

`subscribe` suits UI state and other long-lived streams. Its function sees values only. An error
it can't handle is logged as a warning, never thrown back to the code that sent the event, such as
the caller of `:=`. Handle errors in the chain with `recover` or `tapOnFailure`, or use
`RxRunner.runContinuously` to see every event. A uni-dom binding shows nothing for an `OnError`
and renders the next value.

## How Operators End

| Operator | Completes when |
|----------|----------------|
| `map`, `filter`, `tap` | Its upstream ends |
| `take(n)` | It has emitted `n` values (it cancels its upstream), or its upstream ends first |
| `concat(other)` | `other` ends; an `OnFailure` of the first stream ends it at once |
| `flatMap(f)` | Both its input and the current inner stream have ended; a new input value cancels the inner stream still running |
| `zip(...)` | One of its sources has ended and has no buffered value left to pair |
| `join(...)` | Every source has ended |
| `recover(f)` | Its upstream completes, or after the replacement value for an `OnFailure` |

An `OnFailure` of an inner stream of `flatMap`, or of any source of `merge`, ends the operator
with the same failure.

## Compared With Other Rx Libraries

Uni's events follow [R3](https://github.com/Cysharp/R3) for .NET, which also separates errors a
stream continues past from the end of a stream:

| Uni | R3 | ReactiveX (RxJava) |
|-----|----|--------------------|
| `OnNext(v)` | `OnNext(v)` | `onNext(v)` |
| `OnError(e)`, the stream goes on | `OnErrorResume(e)` | (no equivalent) |
| `OnCompletion` | `OnCompleted(Result.Success)` | `onComplete()` |
| `OnFailure(e)` | `OnCompleted(Result.Failure(e))` | `onError(e)` |

In ReactiveX, every error ends the stream. In Uni, only `OnFailure` does. `RxRunner.run` gives you
ReactiveX's behavior by delivering the first error as the end.
