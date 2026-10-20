# UniTest: fail an Rx test body that completes without a value

## Goals

- Stop UniTest from passing a test whose returned `Rx` completes before emitting anything. Today, assertions inside `.map`/`.flatMap` are skipped silently, and the test reports success.
- Settle which empty bodies, if any, stay allowed, and whether to warn first or fail right away.

## Background

- `UniTest.executeTest` passes an `RxOps` return value to `compat.runRxAsFuture`. On every platform, a completion with no prior value counts as a pass:
  - JS (`uni-test/.js/.../compat.scala`): `OnCompletion` → `p.success(())`.
  - JVM and Native: `Future(rx.await)`, and `uni-core`'s `compat.await` turns completion into `()` ("for Rx[Unit] tests").
- The `()` fallback dates from when `OnCompletion` was ambiguous. A rejected `filter` emitted a completion, so a one-shot runner needed to resolve on it. ADR `2026-10-14-rx-completion-semantics` made `OnCompletion` terminal, so an empty completion now really means "no value".
- PR #716 hit this: a success-path test passed against a buggy `Rx.transformRx` because the stream completed early. #716 works around it with a local `outcome` helper in `PlaywrightTest.scala`. This PR leaves that code alone.

### Survey: which existing tests complete empty?

I made `runRxAsFuture` fail on empty completion on all three platforms and ran `projectJVM/testFull`, `projectJS/testFull` and `projectNative/testFull` (about 5,400 tests, including uni-dom-test).

- **JVM: 0. Native: 0. JS: 1.** The JS case is `NodeServerTest` "should hide exception messages in 500 responses" (`uni/.js/src/test/.../NodeServerTest.scala:102`).
- That one isn't a legitimately empty test. It's a vacuous pass of the same kind. A 500 is retryable, so `Retry.runAsyncWithContext` takes `Rx.delay(...).flatMap(loop)` inside `transformRx`, and `transformRx` forwards its input's completion while the retry is still pending. That's the `transformRx` bug the separate session is fixing. The test's `status shouldBe 500` never runs today.
- No test returns `Rx.empty` or another deliberately empty stream.

## Design

**Lane: Standard.** This changes the observable semantics of uni-test for every downstream user. A body that passed before can now fail.

- **Always fail.** `runRxAsFuture` reports a test failure when the stream completes before its first `OnNext`. The message is roughly: *"The Rx returned by the test completed without emitting a value, so assertions in map/flatMap did not run. To test for an empty stream, assert on `rx.lastOption.toRx` instead."* No source is exempt, `Rx.empty` included, because a test body that is meant to be empty still has to assert something to mean anything. The escape hatch (`lastOption.toRx`) makes the intent explicit. `toSeq` blocks, so it can't serve on JS.
- **No warning phase.** The survey found no legitimate empty bodies. A warning in test output is easy to miss, which is the very problem this PR fixes. Release notes will flag the change as breaking for uni-test users.
- **One shared implementation.** The Promise + `RxRunner.runOnce` logic moves into shared uni-test code:
  - JS calls it directly.
  - JVM and Native call it inside a `Future` on the test `ExecutionContext`, so the subscription stays off the caller thread as `Future(rx.await)` did.
  - The runner is cancelled once the Promise completes, as `Rx.await` does.
- **Reported as `TestResult.Failure`** (an assertion-style failure, not an `Error`), because the test silently skipped its checks. It isn't a crash. Flaky tests are still downgraded to Skipped, as before.
- **Unchanged:** `uni-core`'s `Rx.await` keeps its `()` fallback. Non-test code uses it, and changing it is a separate, wider decision.
- **Tests:** UniTest self-tests on all platforms. `Rx.empty` → Failure, `Rx.single(1)` → Success, `Rx.exception` → Error, and an `Rx.single(1).filter(_ => false)` body → Failure.
- **The NodeServerTest 500 case** fails under this rule until `transformRx` is fixed. Options are in the first question below.

## Alternatives and why not

- **Fail unless the source is known-empty (`Rx.empty`, `Rx.fromSeq(Nil)`).** That needs operator-tree inspection, and it's wrong after any operator: `Rx.empty.map(...)` is no longer "known". There's also no real use case. The survey found zero such bodies.
- **Fail only for a "no value" Rx, allowing `Rx[Unit]` bodies.** The type is erased at runtime, and an `Rx[Unit]` from `.map { _ => assert }` is exactly the case where an empty completion hides skipped assertions.
- **Warn first, fail in a later release.** This adds a deprecation cycle for a behaviour with no known legitimate users, and warnings keep hiding the vacuous passes in the meantime.
- **Change `Rx.await` in uni-core instead.** It fixes JVM and Native but not JS, and it changes semantics for non-test callers.

## Open questions

1. NodeServerTest 500 case (it fails on JS until `transformRx` is fixed):
   - **(a) Recommended:** land this PR after the `transformRx` fix and rebase onto it, so the test turns green for real.
   - **(b)** Mark that test `pending("transformRx completes early; see <PR>")` here, and un-pend it in the `transformRx` PR.
