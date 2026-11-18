# Run RxScheduler.blocking on virtual threads

## Goals

- Make each blocking task on the JVM (`evalOnBlocking`, `RxScheduler.blocking.execute`, the JDK HTTP client's streaming body reader) cost a virtual thread instead of an OS thread, so thousands of concurrent blocking calls don't create thousands of platform threads.
- Keep the same `RxScheduler` API and keep working on every JVM Uni supports today (Java 17+).

## Background

- `RxScheduler.blocking` is a cached pool of platform daemon threads (`rx-blocking`). It grows without bound: each concurrent blocking call holds one OS thread with about 1 MB of stack.
- `RxScheduler.default` (a `ForkJoinPool` sized to the CPU count) runs CPU work. Virtual threads don't help there, so it stays as it is.
- Uni already picks features by JVM version at runtime with `Runtime.version().feature()` (`PosixPtyCompat`, `TerminalIOCompat`, `CanvasCompat`). The build sets no `-release` flag, so code can call the JDK 21+ API directly behind such a check.
- CI runs JDK 23 in every test job, so a JDK 24+-only path would not run there unless tests build it directly.
- JDK 21–23 *pin* a virtual thread to its carrier while it blocks inside `synchronized` (JEP 491 fixed this in JDK 24). Uni's main sources have about 110 `synchronized` sites, and user code run with `evalOnBlocking` can have more. Once every carrier (one per CPU) is pinned, all other virtual threads stop, which can starve or deadlock the process. `adr/2026-10-27-unitest-suite-thread.md` already turned down virtual threads for this reason.
- Even on JDK 24+, a virtual thread still pins while blocked in a native (JNI) frame or during class initialization. Blocking JNI calls stay a case to avoid on `RxScheduler.blocking`.

## Design

Lane: Standard. Changes which kind of thread runs blocking work on the JVM, and differs from the request on the minimum JDK (24, not 21).

- On JDK 24+, `RxScheduler.blocking` runs each task on a new virtual thread, named `rx-blocking-N`.
- On JDK 17–23, it keeps today's cached platform thread pool, so blocking inside `synchronized` never pins a carrier.
- The delay timer (`schedule`, `scheduleAtFixedRate`) keeps its single platform thread and hands each task to the blocking executor when it fires.
- `parallelism` stays `Int.MaxValue`; `shutdown()` stops new tasks the same way on both paths.
- No new public API or config flag. Code that needs platform threads can pass its own scheduler with `startOn(scheduler)`.
- Scala.js and Scala Native don't change.
- Update the platform table and the blocking-scheduler text in `docs/rx/concurrency.md` and the `RxScheduler.blocking` Scaladoc: virtual threads on JDK 24+, cached pool below.
- Add an ADR for the JDK 24 cutoff and the fallback, with an `adr/README.md` entry.
- The version rule is a small pure function, so its cutoff is tested on any JDK. Tests also build the virtual-thread scheduler directly (the API exists on CI's JDK 23) and check that tasks run on virtual threads, that many concurrent sleeping tasks finish together, and that delayed tasks fire. No new CI job.

## Alternatives and Why Not?

- **Virtual threads from JDK 21, as requested.** On JDK 21–23 a blocking call inside `synchronized` pins a carrier; a few of them at once can freeze every virtual thread.
- **JDK 21+ with an opt-out system property.** Users would find the pinning risk only after hitting it, and it adds a setting to document and support.
- **Also move `RxScheduler.default` to virtual threads.** CPU work gains nothing, and the work-stealing FIFO pool is tuned for short fibers.
