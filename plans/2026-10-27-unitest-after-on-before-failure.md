# UniTest: run `after` when `before` fails, and a deterministic async `after` test on the JVM

## Goals

- Make the per-test hooks symmetric with the suite hooks. `afterAll` already runs after a failed `beforeAll`, but `after` is skipped when `before` throws. A `before` that half-acquires something (opens a file, sets a global flag, starts a mock server) then leaks it into later tests.
- Match JUnit's `@BeforeEach`/`@AfterEach` behavior, which most Scala and Java developers expect.
- Close a test gap left by #784. The check that an async test's objects stay open until its `Future` completes is only deterministic on Scala.js. On the JVM, the check would still pass if `after` ran as soon as the body returned.

## Background

- `UniTestTask.runTests` (sbt runner) and `UniTestEngine.runTest` (JUnit engine, for IDEs) both catch a `before` error, report the test as `Error("before hook failed: ...")`, and never call `runAfter()`.
- `UniTest.runAfter` runs `after` and then shuts down the per-test Design child session. A test whose `before` failed never ran its body, so it never opened a child session (`_testSession` is `None`), and the shutdown is a no-op.
- The current behavior is documented in `docs/core/unitest.md` (Lifecycle Hooks table), the Design-integration ADR, and the `BeforeFails` case in `DesignLifecycleTest`. The skill reference `plugins/uni/skills/uni/references/testing.md` doesn't mention it.
- `DesignTestTest` ("an async test keeps its objects open until the Future completes") hops through the execution context twice. That orders the work on single-threaded Scala.js, but on the JVM the Future can finish before the body returns, so an early shutdown can go unnoticed.

## Design

Lane: **Standard**. This changes behavior that users can observe (`after` now runs in a case where it was skipped).

Gap 1: `after` runs even when `before` throws.
- In both runners, call `runAfter()` when `before` fails. The test result stays `Error("before hook failed: ...")` with the `before` error as the cause. If `after` also throws, its error is attached to the `before` error with `addSuppressed`.
- The test body still does not run, and no Design session is opened.
- For the sbt runner, `after` runs synchronously (no body Future to wait on), before the event is reported.
- Doc updates:
  - `docs/core/unitest.md`, `before` row: "The test fails and its body does not run; `after` still runs". `after` row: "after each test ..., also when the test or `before` fails".
  - The ADR `adr/2026-10-27-unitest-design-integration.md`: amend the Decision bullet in place (the ADR is from the same unreleased cycle), and add a Consequences note.
  - The skill: no change, because it doesn't describe this. No plugin version bump.
- Tests: change `BeforeFails` in `DesignLifecycleTest` to record that `after` ran for the failed test (an `after` event in the `EventLog`), with no session events for it. Also check the JUnit engine on the same suite. Add a case where both `before` and `after` throw: the result is the `before` error, with the `after` error suppressed (checked through the JUnit `TestExecutionResult` throwable, and through the sbt event's throwable).

Gap 2: a JVM-only async test.
- Add `uni-test/.jvm/src/test/scala/wvlet/uni/test/AsyncSessionJVMTest.scala`. The test body takes `(dir: TempDir)` and returns a Future backed by a `Promise`. A separate thread sleeps about 100 ms, checks `dir.isOpen`, and then completes the Promise. A following test asserts that the dir was closed afterward.
- If `after` (and the session shutdown) ran when the body returned, the delayed check would see a closed dir and fail. The check doesn't depend on timing in the other direction: a slow machine only delays the check further.

Release-notes callout for the PR body: an `after` that assumes `before` finished, for example one that dereferences a field `before` would have set, may now run and throw on a `null`. Its error is reported as suppressed under the `before` error.

## Alternatives and Why Not?

- **Keep skipping `after` and document it**: this is the current state. It is asymmetric with `afterAll`, and it surprises people coming from JUnit.
- **Report the `after` error as the result, or as a second event**: this hides the root cause, or inflates the counts. Keeping the first failure matches how `afterAll` and the body-plus-`after` case already behave in the JUnit engine.
