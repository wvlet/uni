# UniTest: run JVM suites on the sbt task thread, not ExecutionContext.global

## Goals

- Stop UniTest from running user test code on `ExecutionContext.global` on the JVM, so a test body
  that synchronously awaits a Future on that pool cannot starve it.
- Keep the change JVM-only and local to the runner. Scala.js and Scala Native keep their current
  execution path.
- Add a regression test that reproduces the starvation with the real sbt `UniTestTask`.

## Background

- `UniTestTask.execute(handler, loggers)` (the blocking entry point sbt calls on the JVM) delegates to
  the async `execute(..., continuation)` written for Scala.js, then `Await`s the result. The async
  path builds the suite with `Future(newInstance(...))` and drives the test queue through
  `flatMap`/`transform` callbacks on `compat.executionContext`. On the JVM that context is
  `ExecutionContext.global`.
- So suite construction, `beforeAll`/`before`/`after`/`afterAll` and every test body run on
  global-pool workers, while the sbt thread only waits. When N suites run in parallel, N global
  workers are occupied by synchronous test code. If those bodies `Await` a Future submitted to the
  global pool, nothing is left to run it. This is the case on a 2-CPU machine, or whenever
  `blocking` compensation is unavailable (`maxExtraThreads=0`, or waits that don't use `blocking`).
- Reproduced in this repo: a forked JVM with a 2-thread global pool and `maxExtraThreads=0` runs two
  fixture suites through `Framework().runner(...).tasks(...)` on two threads. Each suite meets the
  other at a barrier and then `Await`s `Future(1)` on global. Both fail with
  `Future timed out after [5 seconds]`, and the stack traces show the bodies running on
  `ForkJoinWorkerThread`.
- AirSpec passes the same suites under identical settings, so users migrating from AirSpec hit this
  as a regression. The td-llm-proxy workaround (per-suite virtual-thread executors) stays as it is.
  This fix does not depend on it.

## Design

Lane: **Express**. This is a runner bug fix with a confirmed reproduction, no API or UI change, and
no always-gate area. Per the launch prompt, nothing is pushed or merged without explicit approval.

- **Run each JVM suite on the thread sbt gives it.** Add a small JVM-only run-loop
  `ExecutionContext`. Its `execute` enqueues the task. The sbt thread that called
  `Task.execute(handler, loggers)` drains the queue until the suite's Future completes. Construction,
  hooks, bodies and all runner callbacks then run on that one sbt thread, in the same order as today.
  User Futures still complete on whatever pool the user chose. Their completion callbacks re-enter
  the run loop.
- **No new threads.** Thread count, daemon status and lifetime are unchanged, and nothing static
  holds the test classloader. The loop and its queue belong to one `execute` call and become
  garbage when it returns. JDK baseline is unaffected (no virtual threads; CI is on JDK 23).
- **Late tasks are not dropped.** Once the suite completes, the loop closes. Any task submitted
  after that, such as a stray callback, goes to `ExecutionContext.global` instead of a queue that
  nobody drains.
- **Interrupts propagate** as they do with today's `Await` (sbt cancellation).
- **Plumbing:**
  - `UniTest.executeTest` takes the runner's context as a `using` parameter instead of reading
    `compat.executionContext`, so Future/Rx result handling and the Rx subscription also stay on the
    suite thread.
  - The async `execute(..., continuation)` (Scala.js) keeps using `compat.executionContext`.
  - The sync `execute` calls a new `compat.runSuite(ec => Future[Unit])`. On the JVM this is the run
    loop. On JS/Native it is today's `Await.result(body(executionContext), Duration.Inf)`.
- **JUnit engine** keeps its current behaviour: bodies already run on the JUnit thread, and it
  passes `compat.executionContext` explicitly to `executeTest`.
- **Test:** `RunnerStarvationTest` (JVM only) forks a JVM with the constrained global pool and runs
  two package-private fixture suites through the sbt runner, with a 10s barrier, a 5s await, a 30s
  in-child deadline, a 60s process wait, and `destroyForcibly` cleanup. Package-private fixtures are
  not public for sbt discovery, so sbt does not pick them up as suites (verified).

```mermaid
sequenceDiagram
  participant sbt as sbt task thread
  participant loop as run-loop EC
  participant g as global pool
  sbt->>loop: runSuite(body)
  loop->>sbt: construct suite, hooks, test body (runs inline)
  sbt->>g: user Future(...)
  g-->>loop: completion callback enqueued
  loop->>sbt: next test ... until suite Future completes
```

## Alternatives and Why Not?

- **Dedicated cached thread pool for `compat.executionContext`.** This is a one-line change, but it
  adds a static pool whose threads keep the test classloader alive until they idle out. It also
  grows one thread per concurrent suite on top of sbt's own threads, and it still decouples
  user-code threads from sbt's parallelism control.
- **Virtual-thread executor.** Needs JDK 21+, and a virtual thread blocked in `synchronized` pins its
  carrier on JDK < 24. It has the same static-lifetime questions as a cached pool.
- **Wrap waits in `blocking`.** This depends on user code and on `maxExtraThreads > 0`, and doesn't
  help waits that don't go through `blocking` (latches, Java futures).
- **Change Scala Native as well.** Native's single-threaded mode drains global inside `Await`, so a
  run loop there would need separate validation. Out of scope.
