# UniTest: an Rx test body must emit a value

## Context

UniTest auto-awaits a test body that returns an `Rx`. Until now, a stream that completed before emitting anything resolved to `()`, and the test passed. JS returned `p.success(())`, and JVM and Native used `Rx.await`, whose `OnCompletion` fallback does the same. This fallback dates from before `2026-10-14-rx-completion-semantics.md`, when a rejected `filter` emitted a spurious completion.

Since `OnCompletion` became terminal, an empty completion really means that `.map`/`.flatMap` never ran. Assertions in those blocks were skipped silently. In #716, a success-path test passed against an `Rx.transformRx` that completed early. A survey run with the stricter rule (full JVM/JS/Native suites) found one more silent pass and no test that was legitimately empty.

## Decision

- A test body whose `Rx` completes before its first `OnNext` fails with `RxCompletedWithoutValue`. It is reported as `TestResult.Failure`, not `Error`, because the test skipped its checks rather than crashing.
- No source is exempt, `Rx.empty` included, and there is no warning phase. To test for an empty stream, assert on `rx.lastOption.toRx`.
- One shared `RxTestRunner.firstValue` (Promise + `RxRunner.runOnce`) serves all platforms. `executeTest` calls it through `Future.delegate` so that the subscription runs on the test execution context. The per-platform `compat.runRxAsFuture` hooks are gone.
- `Rx.await` in uni-core keeps its `()` fallback, because non-test code depends on it.

Rejected alternatives:

- Exempting known-empty sources: this needs operator-tree inspection, and `Rx.empty.map(...)` is no longer "known".
- Allowing `Rx[Unit]`: the type is erased, and `.map { _ => assert }` is exactly the case being hidden.
- Changing `Rx.await`: it would not cover JS, and it would change semantics for non-test callers.

## Consequences

- This is a breaking change for uni-test users who return an intentionally empty `Rx`.
- The stricter rule surfaced one silent pass, JS `NodeServerTest` "should hide exception messages in 500 responses". The cause was `transformRx` forwarding its input's completion while a retry was pending. That bug is fixed in #717, which this change builds on.
