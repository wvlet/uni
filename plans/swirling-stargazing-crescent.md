# Uni standard `Result[A]` type

Date: 2026-04-18
Topic: Add a standard `Result[A]` type to uni-core that aligns with `Rx[A]`
propagation semantics and Scala's exception mechanism.

## Context

Uni currently handles "a value that could fail" in several ad-hoc ways:

- `scala.util.Try[A]` (used internally by Rx for `transform` / `tapOn`).
- `Either[Throwable, A]` at a few API boundaries.
- `wvlet.uni.control.ResultClass` (retry classification only, not a value
  wrapper).
- `Rx`'s own `RxEvent` ADT (`OnNext` / `OnError` / `OnCompletion`) for stream
  propagation inside `uni-core/src/main/scala/wvlet/uni/rx/`.

None of these is an idiomatic, Rust-flavored single-value `Result[A]`:

- `Try` is in `scala.util` and carries Scala-collections-ish naming that
  predates Scala 3 and does not line up with `Rx`'s combinator vocabulary
  (`recover` / `recoverWith`).
- `Either[Throwable, A]` is conventionally "left = bias'd" and the error type
  is unconstrained, so library authors keep re-picking a left type.
- `ZIO`-style triples (`ZIO[R, E, A]`) solve more problems than we need: Scala
  already has `throw`, `try/catch`, and `NonFatal`, and we do not want to
  introduce a type-level environment.

The goal is a **single-parameter** `Result[+A]` with the error type fixed to
`Throwable`, with combinators and naming that match `Rx`, so Rx ⇄ Result
interop is obvious. This becomes the recommended return type when a public
API wants to represent "this may fail without throwing"
(e.g. parser results, `IO` helpers, settled Rx evaluations) while keeping
Scala's exception mechanism as the default for internal control flow.

## Design

### Definition

`Result[+A]` is a Scala 3 `enum`, consistent with `LogLevel` and the 20+ other
enums in the tree (see `uni-core/src/main/scala/wvlet/uni/log/LogLevel.scala`).

```scala
package wvlet.uni

import scala.util.control.NonFatal
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}

enum Result[+A]:
  case Success(value: A)
  case Failure(error: Throwable)
```

Placed at `uni-core/src/main/scala/wvlet/uni/Result.scala` (root of
`wvlet.uni` inside `uni-core`), so it sits alongside foundational types and
is reachable from `import wvlet.uni.Result`.

### Core API (instance methods)

Methods mirror `Rx`'s names where they overlap, so users switching between
`Rx[A]` and `Result[A]` see the same vocabulary.

| Method | Signature | Notes |
|---|---|---|
| `isSuccess` / `isFailure` | `Boolean` | Predicate helpers. |
| `get` | `A` | Rethrows the wrapped `Throwable` on `Failure`. |
| `getOrElse` | `[B >: A](default: => B): B` | Lazy default on failure. |
| `orElse` | `[B >: A](alt: => Result[B]): Result[B]` | Lazy alternative. |
| `map` | `[B](f: A => B): Result[B]` | Exceptions in `f` become `Failure`. |
| `flatMap` | `[B](f: A => Result[B]): Result[B]` | Exceptions in `f` become `Failure`. |
| `filter` / `withFilter` | `(p: A => Boolean): Result[A]` | `for`-comprehension support; predicate failure → `Failure(NoSuchElementException)`. |
| `foreach` | `(f: A => Unit): Unit` | Only runs on `Success`. |
| `recover` | `[B >: A](pf: PartialFunction[Throwable, B]): Result[B]` | Mirrors `Rx.recover`. |
| `recoverWith` | `[B >: A](pf: PartialFunction[Throwable, Result[B]]): Result[B]` | Mirrors `Rx.recoverWith`. |
| `mapError` | `(f: Throwable => Throwable): Result[A]` | Translate/wrap the error. |
| `fold` | `[B](onFailure: Throwable => B, onSuccess: A => B): B` | Eliminator. |
| `toOption` | `Option[A]` | `Failure → None`. |
| `toEither` | `Either[Throwable, A]` | |
| `toTry` | `scala.util.Try[A]` | |
| `toRx` | `Rx[A]` | Single-element `Rx` (or errored `Rx`). |

`map` and `flatMap` always catch `NonFatal`, so `for` comprehensions propagate
failures uniformly — this is the "`Rx[A]`-like propagation while wrapping
exception" the user asked for.

### Companion / constructors

```scala
object Result:
  def apply[A](body: => A): Result[A] =
    try Success(body)
    catch { case NonFatal(e) => Failure(e) }

  def success[A](v: A): Result[A] = Success(v)
  def failure(e: Throwable): Result[Nothing] = Failure(e)

  def fromTry[A](t: Try[A]): Result[A] =
    t match
      case TrySuccess(v) => Success(v)
      case TryFailure(e) => Failure(e)

  def fromEither[A](e: Either[Throwable, A]): Result[A] =
    e.fold(Failure(_), Success(_))

  def fromOption[A](o: Option[A], ifNone: => Throwable): Result[A] =
    o.fold(Failure(ifNone))(Success(_))
```

`Result.apply { ... }` is the primary factory — it is the "try this block,
wrap any non-fatal exception" entry point that parallels `Try { ... }` but
returns the Uni type.

### Rx interop

A single conversion method on `RxOps[A]` returns `Rx[Result[A]]`, turning
`OnError` events into `Result.Failure` values without terminating the stream.
This closes the loop with the user's motivation ("Rx-like propagation"):

- `RxOps[A].materialize: Rx[Result[A]]` — each upstream event becomes a
  `Result`; `OnError` becomes `Failure` and the stream continues.
- `Rx.fromResult[A](r: Result[A]): Rx[A]` — factory on `Rx` companion.
- `Result[A].toRx: Rx[A]` — already covered above.

Only `materialize` is a new method on `RxOps`; everything else is a factory
or instance method on `Result`. This keeps `Rx`'s surface area minimal while
making the interop obvious. (Naming mirrors RxJS/ReactiveX `materialize` so
readers recognise the intent.)

### Why not `Result[E, A]` / `Result[A, E]`?

The user explicitly rejected the ZIO triple for this reason — Scala already
has exceptions and `NonFatal`, and the purpose of `Result[A]` is to *wrap*
exceptions at API boundaries, not to re-invent a type-level error channel.
Fixing `E = Throwable` keeps the shape single-parameter like `Option[A]` and
`Rx[A]`, and lets `mapError` cover the "translate the exception" use case.

## Files to add / modify

Added:

- `uni-core/src/main/scala/wvlet/uni/Result.scala` — the enum and companion.
- `uni-core/src/test/scala/wvlet/uni/ResultTest.scala` — unit tests using
  `wvlet.uni.test.UniTest` (matches existing test style, e.g. `RxTest`).
- `docs/core/result.md` — reference page following
  `docs/control/rate-limiter.md`'s shape (concept, API tour, interop with
  `Rx` and `Try`).

Modified:

- `uni-core/src/main/scala/wvlet/uni/rx/Rx.scala` — add `materialize` on
  `RxOps`, `fromResult` on the `Rx` companion. Small localized additions;
  reuse the existing `FlatMapOp` / `RecoverOp` mechanics so no new
  `RxEvent` is required.
- `docs/.vitepress/config.mts` — add the new page to **both** the `/guide/`
  and `/` sidebars (per `docs/CLAUDE.md` rule).

No other modules depend on this change — it is additive.

## Testing

`ResultTest` covers:

1. `Result.apply { ... }` wraps `NonFatal` throwables; fatal errors escape
   (`intercept[InterruptedException] { ... }`).
2. `map` / `flatMap` propagate exceptions thrown inside `f` as `Failure`.
3. `recover` / `recoverWith` only trigger on matching partial functions;
   non-matching partials re-emit the original `Failure`.
4. `for`-comprehension: two `Result` values compose; a middle `Failure`
   short-circuits.
5. `toTry` / `toEither` / `toOption` / `toRx` round-trips.
6. `Rx[A].materialize` turns an errored `Rx` into `Rx[Result[A]]` that emits
   exactly one `Failure` and completes.

Run locally with:

```bash
./sbt "coreJVM/testOnly *ResultTest"
./sbt "coreJS/testOnly *ResultTest"
./sbt scalafmtAll
```

## Documentation

`docs/core/result.md` follows the `RateLimiter` page's shape:

- One-line concept and package line (`import wvlet.uni.Result`).
- When to use `Result` vs `throw` vs `Rx`.
- API tour with verified snippets (Rule #0 — every method referenced is
  confirmed against `Result.scala`).
- Interop subsection: `Rx.materialize`, `Result.toRx`, `fromTry` /
  `toTry`, `fromEither` / `toEither`.

Sidebar entries added in both `/guide/` and `/` blocks of
`docs/.vitepress/config.mts`. `npm run docs:build` must pass.

## Verification

- `./sbt compile` — all modules build.
- `./sbt test` — full suite green; the new `ResultTest` is the focused
  signal.
- `./sbt scalafmtAll` — CI format check.
- `npm run docs:build` — no dead links, sidebar reachable.
- Manual: open `http://localhost:5173/core/result` after `npm run docs:dev`
  and confirm the page renders with the two sidebar entries.

## Out of scope (deferred)

- Adding a `Pending` / `Empty` third case (would conflate single-value with
  stream semantics — keep `Rx` for that).
- A bifunctor `Result[E, A]` — rejected above.
- Rewriting existing `Try`-returning APIs in Uni to `Result`; callers can
  migrate incrementally via `Result.fromTry` / `toTry`.
- Integrating `Result` into `wvlet.uni.http.Response` error classification
  (tracked separately if desired — HTTP errors are status-code driven, not
  exception-driven).
