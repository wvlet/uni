# Rx: stop `filter` from completing the stream on rejection

## Goals

- Make async Scala.js tests that return an `Rx` built on `Rx.future`, including `startAndAwait { ... }`, wait for the future and run their assertions. Today they pass without asserting anything.
- Fix the semantics at the layer that is actually wrong, so that other `OnCompletion` consumers (`concat`, `lastOption`, `runOnce` callers in the HTTP servers) stop seeing completions that are not real.
- Settle which layer to fix: `FilterOp`, `Rx.future`, or `runOnce`.

## Background

- `Rx.future(f)` is `futureToRx(f).filter(_.isDefined).map(_.get)`, where `futureToRx` is an `RxVar[Option[A]]` that starts as `None`, is set to `Some(x)` and then `stop()`ped when `f` succeeds, or has its exception set when `f` fails.
- `FilterOp` in `RxRunner` emits `OnCompletion` downstream every time the predicate rejects a value, then returns `Continue` upstream (`RxRunner.scala:153`). The behavior comes from airframe-rx, and `RxTest` "filter" pins it: `Rx.sequence(1,2,3).filter(odd)` yields `OnNext(1), OnCompletion, OnNext(3), OnCompletion`.
  - The likely reason: a rejected value from a source that never completes on its own (an `RxVar`, or `Rx.const`, whose `TryOp` emits `OnNext` but no `OnCompletion`) still ends a one-shot `await`/`runOnce` instead of hanging.
- `RxRunner.runOnce` returns `Stop` for the first event, but `RxVar.foreachEvent` ignores the `RxResult`, and `SingleOp` emits `OnCompletion` regardless of it. So `runOnce` callers can receive more than one event.
- Consequences of these two behaviors together:
  - **Tests:** uni-test's JS `runRxAsFuture` and the JVM/Native `compat.await` both use `runOnce`. With a pending `Rx.future`, the first event is the spurious `OnCompletion`, so the test resolves with `()`. On JVM, `await` would also return `()` in place of the value.
  - **`startAndAwait`:** it ends with `.concat(cleanup)`. The spurious completion from `whenReady` (itself an `Rx.future` on Node) can trigger the cleanup that stops the server.
  - **Node server:** `NodeHttpServer.runResponse` writes `Response.notFound` on `OnCompletion`. Any async handler built on `Rx.future` therefore gets a 404 before its value arrives. The same applies to `NodeWebSocket` routes and other `runOnce` sites.
  - **Netty (#710):** `runRxResponse` saw `OnCompletion` after `OnNext` and guarded against it with an `AtomicBoolean`. That trailing completion is the real completion arriving after `runOnce` asked to stop (the second bullet above), not the filter.
- Affected areas: `Rx.future` users (`FetchChannel`, `JSWebSocketClient`, `JavaWebSocketClient`, `ElectronRendererChannel`, `RxQueue`, `NodeHttpServer.whenReady`), the `runOnce` callers listed above, and all async tests on Scala.js.

## Design

Recommended: fix `FilterOp`, the root cause, and make `runOnce` deliver at most one event.

1. **`FilterOp`:** when the predicate rejects a value, return `Continue` and emit nothing. `OnCompletion` from a filter then means the upstream really completed, as in standard Rx.
   - Update `RxTest` "filter" to expect `OnNext(1), OnNext(3), OnCompletion`.
2. **`TryOp` (`Rx.const` / `Rx.fromTry`):** emit `OnCompletion` after the value, as `SingleOp` already does.
   - This keeps `Rx.const(x).filter(_ => false)` terminating under `await`/`runOnce`. It is the one case the old filter behavior covered and step 1 would otherwise turn into a hang.
   - An `RxVar` whose values are all rejected will no longer end a `runOnce`. That is correct, because the variable can still change.
3. **`RxRunner.runOnce`:** deliver only the first event to `effect`, enforced with an `AtomicBoolean`, and keep returning `Stop`. The method then actually does what its doc comment says, "until the first event".
   - It does **not** cancel upstream, so a source's own completion still drives `concat`. `startAndAwait`'s cleanup that stops the server keeps running.
3a. **`FlatMapOp`** (found during implementation): complete only after both the input and the current nested stream complete. Before, the input's `OnCompletion` passed straight through, so `startAndAwait`'s `concat(cleanup)` stopped the server while the block's `Rx.future` was still pending. The old spurious filter completion had been hiding this.
4. **Netty guard:** after steps 1–3 the `AtomicBoolean` in `runRxResponse` is redundant. Per the brief, keep it in this PR and mention it in the PR description as removable in a follow-up.
5. **Tests:**
   - Regression test in shared `RxTest` (runs on JVM, JS, and Native), using `ExecutionContext.parasitic` so it is synchronous: `runOnce(Rx.future(p.future))` records no event while `p` is pending, then exactly `OnNext(1)` after `p.success(1)`. Before the fix it records `OnCompletion` first.
   - `filter(...).concat(...)` and `filter(...).lastOption` tests that fail under the old behavior.
   - A uni-test JS test checking that `runRxAsFuture(Rx.future(delayed))` resolves to the value, not `()`.
6. **Workaround cleanup:** replace `firstValue` and `withNodeServer` in `NodeServerTest` with `startAndAwait`, since the fix should make it work on Node.
7. **Previously vacuous tests:** run `uniJS/test` (`NodeServerTest`, `NodeWebSocketTest`, `JSWebSocketClientTest`, …) plus the JVM and Native suites. Fix small failures in this PR and list larger ones in the PR description.
7a. **Surfaced failures fixed:** `NodeWebSocket` ignored the half-open `'end'` event, so `onClose` never fired when a client sent FIN without a close frame. The Node 404 test expected a 404 `Response`, but the client raises `HttpException` for a 404.
8. **ADR:** rewrite the "Known gap" section of `adr/2026-10-14-http-server-secure-defaults.md` as resolved, and point it to this change.

Breaking change: code that relied on filter-rejection producing `OnCompletion` changes behavior. The likely example is a `runOnce` or `await` over a filtered `RxVar`, which used to resolve to `()` and now waits for a matching value or a real completion.

## Alternatives and Why Not?

- **Fix only `Rx.future`** (a dedicated `FutureOp` that emits `OnNext`/`OnError` from `onComplete`, then `OnCompletion`): narrow, with no change to filter semantics. But every other `filter` stays broken for `concat`, `lastOption`, and `runOnce`. For example, a Node handler using `rx.filter(...)` over a variable still returns 404. It treats the symptom.
- **Fix only `runOnce`** (ignore `OnCompletion` until an `OnNext` arrives): `runOnce` cannot tell a spurious completion from a real one. `Rx.empty` or a filtered `Rx.single` would then hang, and `concat`/`lastOption` inside the chain would still see the spurious completion. This is the wrong layer.
- **`FilterOp` only, without the `runOnce` change:** fixes the vacuous tests, but `runOnce` callers still receive `OnNext` followed by the real `OnCompletion`. `NodeHttpServer.runResponse` would then try to write a 404 after the real response, which needs an ad-hoc guard at each call site, like Netty's. The `runOnce` change is a few lines and fixes all call sites at once.

## Decisions needing input

- Include step 2 (`TryOp` completes)? Recommended yes. It is small and keeps `Rx.const(...).filter(...)` from hanging.
- Keep the Netty `AtomicBoolean` (per the brief) and remove it in a follow-up PR?
