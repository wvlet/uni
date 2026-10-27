# Design integration in UniTest

## Context

Fixtures that share servers, clients, or temp directories across suites were written with the lifecycle hooks: a trait holding the resource in an `Option` var, acquiring it after `super.beforeAll`, and releasing it in `try` with `super.afterAll` in `finally`. Each fixture repeated about 40 lines, and the obvious shortcut, a self-type trait (`trait F { this: UniTest => override protected def beforeAll ... }`), compiles but silently runs only the last such fixture's hooks and cannot call `super`. AirSpec solved this with Design: bindings per spec (`initDesign`) and per test (`initLocalDesign`), sessions managed by the runner, and test arguments built from the session. UniTest left that out (#298) because `uni-test` could not depend on `uni`, where Design lived; #794 moved Design and Surface into `uni-core` (see [2026-10-27-design-surface-in-uni-core.md](2026-10-27-design-surface-in-uni-core.md)).

## Decision

- `UniTest.addDesign(f: Design => Design)` adds suite-scoped bindings, and `addTestDesign(f)` adds bindings built fresh for each test. Fixture traits call them from their bodies, so the calls compose in trait initialization order, and a later binding of the same type wins. Named `add*` rather than AirSpec's `init*` because the calls accumulate. Calling them once the tests start (after `beforeAll` begins) throws, because the binding might no longer take effect.
- `test(name) { (a: A, b: B) => ... }` builds its arguments from the test's child session. `(session: Session)` gives the session itself. A body without parameters is a plain test and opens no session.
- `test` is a single inline method. A transparent macro reports whether the body is a function literal; plain bodies take the ordinary inline path, and function literals go through a second macro that builds each argument with `Surface.of[T]`. Overloads per arity were rejected: a body of type `Nothing` (ending in `pending(...)` or `fail(...)`) conforms to every function type, which makes them ambiguous. A function value that is not a literal is a compile error, so a test can never register a body that it never calls.
- The suite's session opens on the first test that takes arguments and shuts down after `afterAll`; each such test's child session shuts down after `after`, once a returned `Future` or `Rx` completes. When `before` throws, the body is skipped and opens no session, but `after` still runs, as with JUnit's `@AfterEach` and like `afterAll` after a failed `beforeAll`; the `before` error stays the result, with an `after` error suppressed. When a hook and a shutdown both fail, the hook's error is rethrown with the shutdown error suppressed.
- Objects bound with `addDesign` are shared by the suite. Objects bound with `addTestDesign`, and unbound objects, are built in the child session and closed after the test.
- Design fix: a singleton bound in a parent session but first requested from a child session used to build its dependencies in the child (`contextSession`). Those dependencies were closed with the child while the singleton lived on, so the next test got a client wired to a closed server. `SessionImpl.getInstance` now builds a singleton's dependencies in the session that binds it. Non-singleton class bindings still resolve from the requesting session.
- A suite session whose `start` throws makes every later test that needs it fail with the same error, instead of building child sessions on a half-started parent.
- The JUnit engine (`UniTestEngine`, used by IDEs) previously ran no lifecycle hooks; it now calls the same `run*` wrappers with the sbt runner's semantics. It skips them when no test is selected, reports a body that exceeds its 30-second wait as a test error and still runs `after`, and keeps the first failure when teardown also fails.

## Alternatives

- A `suiteResource`/`testResource` API in `uni-test`: a second resource manager next to Design.
- Interfaces in `uni-core` that Design implements (`DesignProvider`/`DesignSession`), with a type class for test arguments: an extra layer in signatures, and a `given` import for injection. Unnecessary once Design is in `uni-core`.
- Overriding `protected def design`: every fixture needs `override`, `super.design`, and a narrowed return type, and forgetting `super` silently drops the other fixtures' bindings.
- A docs-only fixture pattern: about 40 lines per fixture.

## Consequences

- The suite session opens lazily, so a binding that fails to build fails the test that needs it rather than the whole suite.
- `test` is now inline, so its body is expanded at each call site. Behavior of plain bodies is unchanged.
- Running `after` after a failed `before` (changed after v2026.1.36, which skipped it) lets `before` release what it acquired before failing. An `after` that assumes `before` finished, for example one that reads a field `before` would have set, can now fail; its error is attached to the `before` error.
- `registerTest` and `registerSessionTest` are `protected[test]`: the macro expands in user classes and calls them.
