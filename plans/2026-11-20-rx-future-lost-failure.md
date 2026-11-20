# Fix three flaky tests: a lost Rx.future failure, a stats race, and a log-capture race

## Goals

- Find and fix the root cause of each flake, rather than raising timeouts:
  1. `WebSocketRelaySpec` "close the client with 4502 when the upstream can't be reached": the client gets no close and times out after 10 s. Seen twice on `NettyWebSocketRelayTest`: PR #1015 run 44770974176, and main at 1d40a1b0.
  2. `WebSocketRelaySpec` "relay text and binary frames both ways": `framesToClient` is 2 instead of 3. Seen on main at ce528fcf, `JvmWebSocketRelayTest`. This run was listed as a 4502 sighting, but it failed on this other case.
  3. `RouterHandlerErrorTest` "show the exception message with the server's detailed errors": the captured warnings are empty. Seen on PR #1015 run 44770974176, attempt 2.

## Background

### 1. A failed future can be lost before anyone subscribes

- `Rx.future(f)` wraps `f` in an `RxVar[Option[A]]` and registers the future's callbacks when `Rx.future` is called. A failure becomes `RxVar.setException`, which notifies only the subscribers attached at that moment and stores nothing.
- So when `f` fails before `RxRunner` subscribes, the error is dropped. A late subscriber sees only the initial `None`, which `Rx.future` filters out, and then waits forever.
- Reproduced deterministically: `Rx.future(Future.failed(e))` with `ExecutionContext.parasitic` delivers no event at all.
- Both blocking clients hit this race; the relay subscribes in `RxRunner.runOnce` after `connect` returns:
  - JVM: `JavaWebSocketClient.connect` starts `buildAsync`, then returns `Rx.future(opened.future)` with `parasitic`. A refused localhost connect can fail `opened` on the `HttpClient` thread inside that gap, and a loaded CI runner widens it.
  - Native: `NativeWebSocketClient` has the same race. It starts its connect thread before `Rx.future`.
  - JS doesn't race: the promise settles on a later tick.
- The "denied" upstream needs a full HTTP round trip, which is why it never loses its 4502.
- `adr/2026-10-14-rx-completion-semantics.md` already says that a failed `Rx.future` emits `OnError` and no `OnCompletion`. Only delivery to late subscribers is broken.
- Ruled out: a connect timeout to the test's freed ephemeral port. 300 local iterations per backend each finished in 1–2 ms. A parallel suite reusing that port would mostly produce a different error, not this silent wait.

### 2. Upstream frames are counted after they are forwarded

- `UpstreamHandler` forwards a frame to the client and only then increments `framesToClient`.
- The client can receive the last echo and close, and the relay reports the stats, all before the upstream thread increments the counter.

### 3. Another project's log capture takes Netty's warning

- The Netty server logs `Error in Rx handler` on its worker thread before it writes the 500, so the warning is not late.
- sbt runs uni-netty and uni tests in one JVM, and each project's runner loads its own copy of `TestLogCapture`. Each copy wraps the JDK root logger's handlers, so their routers nest.
- A record from a thread that runs no suite, such as a Netty worker, goes to every open capture of the router that sees it first. When the outer router's copy has an open `captureLogs` in another suite (uni has 10 that capture every logger), that capture takes the record and the outer router stops there. The inner copy, which holds this test's capture, never sees it.

## Design

Lane: Standard. This changes what core `Rx` primitives deliver and how uni-test routes logs, both of which every module uses, so the direction needs your approval.

- **`Rx.future` and `Rx.fromFuture` replay the terminal event to a late subscriber.**
  - Failure: the subscriber gets `OnError` and no `OnCompletion`, as the ADR states.
  - Success: the subscriber gets the value, then `OnCompletion`. Today a late subscriber gets the value but is never told the stream ended.
- **Each event reaches a subscriber once, and in order.** The future-backed variable records its value and terminal event, and registers a subscriber, under one lock. It then delivers the current state, so a future that completes during registration can't send a duplicate value or a value after `OnCompletion`.
- **The public `RxVar` doesn't change.** Its `setException` keeps its behaviour, so plain state variables are unaffected.
- **Callers that see new behaviour.** When the future has already failed, a later subscriber of `Rx.future`, `Rx.fromFuture`, or `Future.toRx` now sees the error instead of waiting forever. For example, `DomRenderer` clears the section and logs a warning, instead of showing "loading" forever. (`Rx.fromFuture` returns an `RxOption`, so callers reach the variable only through `RxRunner`.)
- **The relay counts an upstream frame before forwarding it, and subtracts it when the send throws.** The stats then cover the frame before the client can see it, and a failed send still isn't billed.
- **A log record from a thread with no suite reaches every class-loader copy's captures.** After the outer router offers the record to its own captures, it also offers it to the routers it wraps, through a JDK-typed interface that works across class loaders. The record is printed only when no capture took it.
- **ADR:** amend `adr/2026-10-14-rx-completion-semantics.md` with the replay rule. The Lean models don't cover `Rx.future`, so no new Lean case is needed.
- **Tests:**
  - A shared `Rx` test: a future that fails or succeeds before subscription reaches `runOnce` and `foreach`, on all platforms.
  - A relay test whose upstream client returns `Rx.future` of a future that has already failed, so the 4502 path no longer depends on timing.
  - A uni-test test of a pool-thread record captured through a wrapped router.
- **Verification:** repeated runs of the Netty, JVM, and Native relay suites, and of `RouterHandlerErrorTest`.

## Alternatives and Why Not?

- **Make every `RxVar` replay a terminal error.** That changes the public semantics of a state variable for every UI caller, which is a larger decision than this fix needs.
- **Subscribe before starting the connect in the relay.** It fixes only this caller. Every other `Rx.future` user that can fail fast keeps the bug.
- **Use port 1 for the unreachable upstream, as the Node test does.** It doesn't address the lost failure, which is the observed failure mode.
- **Fork test JVMs per project.** It would hide the capture race, but it would slow CI. Two runners can still share a JVM in other builds.
- **Raise the 10 s test timeout.** A dropped event never arrives, so a longer wait still fails.
