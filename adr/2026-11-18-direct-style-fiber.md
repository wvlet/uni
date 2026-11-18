# Direct-style Fiber for plain blocking code (JVM and Native)

## Context

Users asked for lightweight threads they can use with ordinary blocking code. `RxFiber` covers the `Rx` style, where every result is an `Rx` that has to be run. `BackgroundTask` runs a plain block, but it is shaped for one long worker with progress, has no join-with-rethrow, parallel helpers, or scope, and runs on a dedicated platform thread. Since #948, `RxScheduler.blocking` uses virtual threads on JDK 24+, so a direct-style API on top of it is cheap there.

Direct style without compiler support needs real threads. The JVM (virtual or platform threads) and Scala Native (OS threads) have them; Scala.js has one thread, so a blocking `join()` cannot wait. Java's `StructuredTaskScope` is still a preview API through JDK 25, and Uni supports JDK 17.

## Decision

- `wvlet.uni.control.Fiber` lives in `uni/.jvm-native`, so code that calls `join()` on Scala.js fails to compile rather than at run time. JS code uses `RxFiber`/`RxParallel`.
- Fibers run on `RxScheduler.blocking`, never the compute pool, so a blocked fiber cannot starve CPU work.
- API: `Fiber { ... }`, `join()` (rethrows), `poll`, `cancel()`, `Fiber.par`, `Fiber.parMap(items, maxConcurrency)`, `Fiber.race`, `Fiber.scope { s => s.fork(...) }`, `Fiber.onCancel`, `Fiber.checkCancelled()`.
- Cancel interrupts the thread, since most fiber bodies are blocking I/O that never polls a flag. `Fiber.onCancel(hook)` covers calls an interrupt cannot stop (FFI, JDBC, platform-thread sockets before JDK 24), as `BackgroundTask.onCancel` does.
- State changes (pending, running, done, cancelled), the thread reference, and the cancel hooks share one lock. Whichever of cancel and completion takes the lock first decides the result. Once cancel wins, `join()` throws `FiberCancelledException` even if the body swallowed the interrupt and returned a value.
- `cancel()` interrupts only while it holds the lock and the body is running. The worker clears the thread reference under that lock when the body returns, then clears the interrupt flag, so no interrupt reaches the next task on a pooled thread.
- A fiber runs its completion callbacks before it releases `join()` waiters, so a scope always sees a fork's failure once that fork has terminated.
- `scope` waits for every fork, including forks started while it closes. The first non-cancelled failure cancels the others and is rethrown. Once the scope starts cancelling (a fork failed, the block threw, or the waiting thread was interrupted), it cancels every fork, including forks added later, and keeps waiting for all of them; after an interrupt it then throws `FiberCancelledException`.
- `parMap` requires `maxConcurrency` and forks from a launcher fiber that takes a semaphore permit per item. At most `maxConcurrency` bodies run at once, plus the launcher; a failure that cancels the scope also stops the launcher while it waits for a permit.
- `race` fails only when every body fails, rethrowing the last failure with the others suppressed. It cancels and waits for the losers before returning.

## Why not

- **Cross-platform, running bodies inline on JS as `BackgroundTask` does.** `parMap` and `race` would quietly run one at a time on JS, and `race` would always pick the first body.
- **Extend `BackgroundTask`.** Its context parameter, progress type, and platform threads fit one long worker. `BackgroundTask` should also stay on platform threads, because its DuckDB users block in JNI, which pins a virtual thread's carrier even on JDK 24.
- **Direct style on JS through `dotty-cps-async` or Scala.js `js.await` (WebAssembly + JSPI).** That needs a dependency or an experimental backend.
- **Flag-only cooperative cancel.** Blocking I/O bodies never check a flag, so cancel would do nothing for them.

## Consequences

- On JDK 17–23 and on Native each running fiber is an OS thread; the docs say fibers are lightweight only on JDK 24+.
- Follow-ups if needed: an `Rx` conversion (`fiber.toRx`) and timeouts (`join(timeout)`).
