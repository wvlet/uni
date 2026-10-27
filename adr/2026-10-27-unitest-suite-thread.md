# UniTest runs each JVM suite on its sbt task thread

## Context

UniTest's sbt runner is shared across JVM, Scala.js and Scala Native. The async `UniTestTask.execute(..., continuation)` builds the suite with `Future(newInstance(...))` and drives the test queue through `flatMap`/`transform` callbacks, so async test bodies (`Future`, `Rx`) work on Scala.js. The blocking JVM `execute(handler, loggers)` used to delegate to that path and `Await` it, with `compat.executionContext = ExecutionContext.global`.

So on the JVM, suite construction, `beforeAll`/`before`/`after`/`afterAll` and every test body ran on global-pool workers, while the sbt thread only waited. With suites running in parallel on a 2-CPU machine (or `maxExtraThreads=0`, or waits that bypass `blocking`), test bodies that synchronously awaited a `Future` on the global pool occupied the workers that `Future` needed and timed out. This was seen in td-llm-proxy's SSE ping tests on CircleCI.

AirSpec, which UniTest replaces, has the same Promise/Await structure but runs callbacks on a dedicated `Executors.newCachedThreadPool` of daemon threads (`airspec-executor`), so the global pool stayed free.

## Decision

- On the JVM, the blocking `execute` calls `compat.runSuite`, which drives a per-call run-loop `ExecutionContext` on the calling thread until the suite's `Future` completes. All runner callbacks, hooks and test bodies run on the thread sbt dedicates to the task; user `Future`s complete on their own pool and their callbacks re-enter the loop.
- `UniTest.executeTest` takes the runner's context as `(using ExecutionContext)` so `Future`/`Rx` result handling stays on the suite thread. The JUnit engine passes `compat.executionContext` (its bodies already run on the JUnit thread).
- The loop enqueues with the non-blocking `LinkedBlockingQueue.add` and clears the interrupt flag after each task, as a pool worker does between tasks, so a test body that leaves the flag set cannot abort the rest of the suite. An interrupt while the loop waits in `take()` (sbt cancellation) still propagates.
- Tasks submitted after the suite completes go to `ExecutionContext.global` instead of a queue nobody drains.
- Scala.js keeps the async `execute` on `MacrotaskExecutor`; Scala Native keeps `Await` on its global context.

## Why not a dedicated pool, like AirSpec

- A static cached pool keeps the test classloader reachable from its threads until they idle out, and adds one thread per concurrent suite on top of sbt's own waiting thread.
- A virtual-thread executor needs JDK 21+, and pins carriers on `synchronized` before JDK 24.
- Running on the sbt thread adds no threads, has no static state, and keeps suite concurrency under sbt's control (`parallelExecution`, `concurrentRestrictions`).

## Consequences

- `RunnerStarvationTest` forks a JVM with a 2-thread global pool and `maxExtraThreads=0` and runs two package-private fixture suites through the sbt runner; package-private suites are not public for sbt discovery, so they never run as ordinary tests.
- Any new internal code that schedules work on the runner's context and then blocks on it from the same suite would deadlock. Only `uni-test` internals can reach that context.
- Scala Native still shares its global context with test bodies; changing it needs separate validation of single-threaded mode.
