# Direct-style Fiber: run plain blocking code concurrently

## Goals

- Let users run ordinary blocking code concurrently and wait for the results, with no `Rx`, effect type, or compiler plugin: start a computation, join it, run a list in parallel with a limit, and cancel the rest when one fails.
- Make that cheap on the JVM by running on `RxScheduler.blocking`, which uses virtual threads on JDK 24+ since #948.

## Background

- `RxFiber` (`rx.start`, `join`, `cancel`) covers the `Rx` style: every result is an `Rx` that has to be run. It doesn't fit code written with plain blocking calls, such as the sync HTTP client, JDBC, or file I/O.
- `BackgroundTask` (`wvlet.uni.control`) already runs a plain block on JVM and Native. It is built for one long worker with progress and cooperative cancel (DuckDB queries). It starts a platform daemon thread per task, and has no join-with-rethrow, no parallel helpers, and no scope.
- `BackgroundTask` should stay on platform threads. Its main user blocks inside native DuckDB calls, and a virtual thread blocked in a JNI frame pins its carrier even on JDK 24.
- Without compiler support, direct style needs real threads. That works on the JVM (virtual threads) and on Native (OS threads from the blocking pool). Scala.js has a single thread, so a blocking `join()` can't wait there.
- Uni already has a `.jvm-native` source folder for code shared by those two platforms.
- Java's own `StructuredTaskScope` is still a preview API through JDK 25, and Uni supports JDK 17, so Uni can't build on it.

## Design

Lane: Standard. Adds a new public API.

User flow, for someone fetching several URLs with the sync HTTP client:

- Start: `val a = Fiber { fetch(x) }` starts the work at once on `RxScheduler.blocking` and returns a handle.
- Wait: `a.join()` blocks until the result is ready and returns it, or rethrows the body's exception.
- Fan out: `Fiber.parMap(urls, maxConcurrency = 16)(fetch)` returns the results in input order. `Fiber.par(fetchA, fetchB)` returns a tuple, and `Fiber.race(...)` returns the first success and cancels the rest.
- Group: `Fiber.scope { s => s.fork(...); s.fork(...) }` returns only when every fork has finished. The first failure cancels the other forks and is rethrown from `scope`, so no fork outlives the block. `parMap`, `par`, and `race` are built on `scope`.
- Back out: `a.cancel()` interrupts the fiber's thread and runs any hooks the body registered with `Fiber.onCancel(hook)`. A CPU loop calls `Fiber.checkCancelled()`.
- Check: `a.poll` returns the result without blocking (`Option[Result[A]]`), following `BackgroundTask`.

Decisions:

- Name it `Fiber`, in `wvlet.uni.control`, next to `BackgroundTask`. `RxFiber` keeps the `Rx` side.
- JVM and Native only, in `.jvm-native`, so code that calls `join()` on JS fails to compile instead of failing at run time. JS code keeps using `RxFiber`/`RxParallel`.
- Run fibers on `RxScheduler.blocking`, never the compute `ForkJoinPool`, so a blocked fiber can't starve CPU work.
- "Lightweight" holds on JDK 24+ only. On JDK 17–23 and on Native each running fiber is an OS thread; the docs say so.
- Cancellation:
  - Interrupt stops `sleep`, `join`, the JDK `HttpClient`, NIO channels, and socket reads on virtual threads.
  - It does not stop FFI calls (Native's curl client, DuckDB) or platform-thread socket reads before JDK 24. Those register `Fiber.onCancel(hook)`, for example to call JDBC `Statement.cancel` or abort a curl transfer, as `BackgroundTask` does.
  - Whichever comes first, cancel or completion, decides the result. Once cancel wins, `join()` throws a cancelled exception even if the body swallowed the interrupt and returned a value. `cancel()` after completion does nothing.
  - An interrupt never leaks to the next task on a pooled thread: a fiber is interrupted only while it runs, and its thread's interrupt flag is cleared before going back to the pool.
  - The cancelled exception is a new `FiberCancelledException`. `TaskCancelledException`'s message names `BackgroundTask`.
- Parallel helpers:
  - `parMap` requires `maxConcurrency`. It starts a fiber only when a slot frees up, so it never holds more threads than the limit.
  - `race` fails only when every branch fails, rethrowing the last failure with the others attached as suppressed.
- Scopes:
  - Cancelling a fiber that is waiting in an inner `scope` cancels that scope's forks too.
  - `fork` on a scope that has already closed throws `IllegalStateException`.
- No `Rx` conversion or timeout helpers in this PR; both are small follow-ups once the core is in use.
- Docs: a `Fiber` section in the concurrency guide on when to pick `Fiber`, `RxFiber`, or `BackgroundTask`. Also entries in `docs/guide/for-ai-agents.md` and the `uni` skill (JVM snippets only, with a plugin version bump), and an ADR for the JVM/Native-only scope and the cancel rules.
- Tests run on JVM and Native. They cover:
  - join and rethrow
  - cancel during a blocking call, an `onCancel` hook, and a body that swallows the interrupt
  - no interrupt leaking to the next task on the platform-thread pool
  - `parMap` order and its concurrency limit
  - `race` when all branches fail
  - sibling cancel, nested scopes, and `fork` after a scope closes

## Alternatives and Why Not?

- **Cross-platform with JS running bodies inline, as `BackgroundTask` does.** Shared code would compile on JS, but `parMap` and `race` would quietly run one at a time there, and `race` would always pick the first.
- **Extend `BackgroundTask`.** Its context parameter, progress type, and platform threads fit one long worker; adding join, scope, and virtual threads would change it for its DuckDB users.
- **Direct style on JS through `dotty-cps-async` or Scala.js `js.await` (WebAssembly + JSPI).** That adds a dependency or depends on an experimental backend; it could come later.
- **Flag-only cooperative cancel, like `BackgroundTask`.** Most fiber bodies are blocking I/O that never checks a flag, so cancel would do nothing for them.
