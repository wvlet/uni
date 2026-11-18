# RxScheduler.blocking runs on virtual threads from JDK 24

## Context

On the JVM, `RxScheduler.blocking` was a cached pool of platform daemon threads (`rx-blocking`). `evalOnBlocking`, `RxFiber.startOn(RxScheduler.blocking)`, and the JDK HTTP client's streaming body reader all run there. Each concurrent blocking call held one OS thread, so thousands of concurrent waits meant thousands of OS threads.

Uni supports JDK 17+, and virtual threads exist from JDK 21. On JDK 21–23 a virtual thread that blocks inside `synchronized` pins its carrier, and once every carrier (one per CPU) is pinned, every other virtual thread stops. Uni's main sources have over 100 `synchronized` sites, and user code run on the blocking scheduler can have more. JEP 491 removed that pinning in JDK 24. `adr/2026-10-27-unitest-suite-thread.md` turned down a virtual-thread executor for the test runner for the same reason.

## Decision

- On JDK 24+ (`Runtime.version().feature() >= 24`), `RxScheduler.blocking` runs each task on a new virtual thread named `rx-blocking-N` (numbered from 1, like the platform pool; `Executors.newThreadPerTaskExecutor`).
- On JDK 17–23 it keeps the cached platform thread pool.
- The delay timer keeps one platform thread and hands each fired task to the blocking executor, so delayed tasks also run on virtual threads.
- `RxScheduler.default` stays a FIFO `ForkJoinPool` sized to the CPU count: CPU work gains nothing from virtual threads.
- No config flag. Code that needs platform threads passes its own scheduler to `startOn`.
- The JDK 21+ API is called directly, with no reflection. The build sets no `-release` flag, and the call is only linked when the version check passes, the same way `PosixPtyCompat` and `CanvasCompat` gate FFM.

## Why not

- **JDK 21+, as first requested.** A few blocking calls inside `synchronized` on 21–23 could freeze every virtual thread in the process.
- **JDK 21+ with an opt-out property.** Users would only find the pinning risk after hitting it, and the property would be one more setting to document.
- **Virtual threads for `RxScheduler.default`.** CPU-bound fibers gain nothing, and the work-stealing pool is tuned for them.

## Consequences

- Even on JDK 24+, a virtual thread pins while blocked in a native (JNI) frame or during class initialization. Long blocking JNI calls are better run on a platform-thread scheduler.
- CI test jobs run JDK 23, where `RxScheduler.blocking` uses platform threads. `BlockingSchedulerTest` builds the virtual-thread scheduler directly (the API exists on 21+) and tests the version rule as a pure function, so both paths are covered without a JDK 24+ job.
- Thread names stay `rx-blocking*`, so log output and thread-name checks keep working.
