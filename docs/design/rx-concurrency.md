# uni-rx Concurrency Design Note

## Overview

This document outlines the gap between cats-effect's concurrency model and uni-rx, along with a phased implementation plan to bring high-concurrency support to uni-rx while maintaining its lightweight, cross-platform nature.

## Background

### Current State of uni-rx

uni-rx is a lightweight reactive stream library optimized for:
- Simple sequential pipelines
- Cross-platform Scala (JVM/JS/Native)
- UI reactivity patterns (RxVar for DOM binding)
- Integration with Scala Futures

However, it has significant limitations for high-concurrency workloads:

| Aspect | Current Implementation | Limitation |
|--------|----------------------|------------|
| Execution Model | Single-threaded, eager pull-based | Cannot utilize multiple cores |
| Concurrency Unit | OS threads via `ExecutionContext.global` | ~1MB stack per thread, limited scalability |
| Synchronization | `synchronized` blocks, `AtomicBoolean` | Coarse-grained locking, contention |
| Parallelism | None - `flatMap` is sequential | Cannot parallelize within a pipeline |
| Backpressure | Not implemented | Risk of OOM under load |
| Resource Safety | Basic `Cancelable` trait | No guaranteed cleanup on errors |

### cats-effect Concurrency Model

cats-effect 3 provides a sophisticated concurrency runtime with:

1. **Fibers**: Lightweight logical threads (~200 bytes each), millions can run concurrently
2. **Work-Stealing Scheduler**: Fiber-aware scheduling with auto-yielding, 1.5-5x faster than thread pools
3. **Semantic Blocking**: Fibers suspend without blocking OS threads
4. **Concurrent Primitives**: `Ref`, `Deferred`, `Semaphore`, `Queue` built on lock-free algorithms
5. **Structured Concurrency**: Fiber hierarchy with cancellation propagation
6. **Resource Safety**: `Resource[F, A]` with bracket semantics for guaranteed cleanup

## Gap Analysis

### Critical Missing Features

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONCURRENCY CAPABILITY GAP                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  cats-effect                          uni-rx                     │
│  ───────────                          ──────                     │
│                                                                  │
│  ┌─────────────┐                      ┌─────────────┐           │
│  │   Fibers    │  ──── missing ────▶  │  OS Threads │           │
│  │  (millions) │                      │  (hundreds) │           │
│  └─────────────┘                      └─────────────┘           │
│                                                                  │
│  ┌─────────────┐                      ┌─────────────┐           │
│  │Work-Stealing│  ──── missing ────▶  │ Fixed Pool  │           │
│  │  Scheduler  │                      │ (no stealing)│           │
│  └─────────────┘                      └─────────────┘           │
│                                                                  │
│  ┌─────────────┐                      ┌─────────────┐           │
│  │  Ref/Deferred│ ──── missing ────▶  │ RxVar only  │           │
│  │  Semaphore  │                      │ (synchronized)│          │
│  └─────────────┘                      └─────────────┘           │
│                                                                  │
│  ┌─────────────┐                      ┌─────────────┐           │
│  │   parMap    │  ──── missing ────▶  │ Sequential  │           │
│  │ parTraverse │                      │   only      │           │
│  └─────────────┘                      └─────────────┘           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Performance Implications

| Scenario | cats-effect | uni-rx (current) |
|----------|-------------|------------------|
| 10K concurrent HTTP requests | ~10ms latency, low memory | Thread exhaustion or OOM |
| CPU-bound parallel processing | Linear scaling with cores | Single-threaded |
| Mixed I/O + compute workload | Efficient multiplexing | Blocking I/O starves compute |
| Backpressured stream processing | Controlled memory usage | Unbounded buffering |

## Design Goals

1. **No cats-effect dependency**: Keep Uni minimal; implement similar semantics natively
2. **Cross-platform**: Parallelism gracefully degrades to sequential on Scala.js
3. **Opt-in complexity**: Simple pipelines remain simple; concurrency is explicit
4. **Backward compatible**: Existing `Rx` code continues to work unchanged
5. **Minimal footprint**: Only add what's needed for high-concurrency support

## Implementation Plan

### Phase 1: Core Concurrency Primitives

#### RxRef[A] - Lock-free Concurrent State

A functional wrapper around `AtomicReference` providing safe concurrent state access:

```scala
trait RxRef[A]:
  /** Get the current value */
  def get: Rx[A]

  /** Set a new value */
  def set(a: A): Rx[Unit]

  /** Atomically update the value */
  def update(f: A => A): Rx[Unit]

  /** Atomically modify and return a result */
  def modify[B](f: A => (A, B)): Rx[B]

  /** Compare-and-set */
  def compareAndSet(expected: A, newValue: A): Rx[Boolean]

object RxRef:
  def apply[A](initial: A): RxRef[A]
```

**Implementation notes:**
- Uses `java.util.concurrent.atomic.AtomicReference` on JVM
- Falls back to simple mutation on JS (single-threaded)
- Retry loop for `update`/`modify` on CAS failure

#### RxDeferred[A] - One-shot Synchronization

A synchronization primitive for communicating a single value between fibers:

```scala
trait RxDeferred[A]:
  /** Semantically block until a value is available */
  def get: Rx[A]

  /** Complete with a value, returns false if already completed */
  def complete(a: A): Rx[Boolean]

  /** Check if completed without blocking */
  def tryGet: Rx[Option[A]]

object RxDeferred:
  def apply[A](): RxDeferred[A]          // Direct creation
  def of[A](): Rx[RxDeferred[A]]         // Creation within Rx context
```

**Implementation notes:**
- Uses `RxRef[Option[A]]` + callback list for waiters
- `get` registers a callback if not yet completed
- `complete` triggers all registered callbacks

#### RxSemaphore - Concurrency Limiting

Controls the number of concurrent operations:

```scala
trait RxSemaphore:
  /** Acquire a permit, semantically blocking if none available */
  def acquire: Rx[Unit]

  /** Release a permit */
  def release: Rx[Unit]

  /** Execute an Rx with automatic acquire/release */
  def withPermit[A](rx: Rx[A]): Rx[A]

  /** Try to acquire without blocking */
  def tryAcquire: Rx[Boolean]

  /** Current number of available permits */
  def available: Rx[Long]

object RxSemaphore:
  def apply(permits: Long): RxSemaphore      // Direct creation
  def of(permits: Long): Rx[RxSemaphore]     // Creation within Rx context
```

### Phase 2: Fiber-like Execution Model

#### RxFiber[A] - Lightweight Concurrency Unit

Represents a running computation that can be joined or cancelled:

```scala
trait RxFiber[A]:
  /** Wait for the fiber to complete and get its result */
  def join: Rx[A]

  /** Request cancellation of this fiber */
  def cancel: Rx[Unit]

  /** Check completion status without blocking */
  def poll: Rx[Option[Try[A]]]

extension [A](rx: Rx[A])
  /** Fork this Rx to run concurrently, returning a handle */
  def start: Rx[RxFiber[A]]

  /** Fork and forget - fire and forget execution */
  def startAndForget: Rx[Unit]
```

#### RxScheduler - Work-Stealing Thread Pool

Platform-specific scheduler optimized for fiber-like workloads:

```scala
trait RxScheduler:
  /** Execute a task on the scheduler */
  def execute(task: Runnable): Unit

  /** Schedule a delayed task */
  def schedule(delay: Long, unit: TimeUnit)(task: => Unit): Cancelable

  /** Number of worker threads */
  def parallelism: Int

object RxScheduler:
  /** Default work-stealing scheduler */
  def default: RxScheduler

  /** Create a scheduler with specific parallelism */
  def apply(parallelism: Int): RxScheduler
```

**JVM Implementation:**
- Work-stealing `ForkJoinPool` with FIFO async mode
- Sized to `Runtime.getRuntime.availableProcessors()`
- Separate blocking pool for `Rx.blocking` operations

**JS Implementation:**
- Uses microtask queue (`Promise.resolve().then(...)`)
- Single-threaded, no actual parallelism
- `parallelism = 1` always

**Native Implementation:**
- Fixed-size thread pool
- Work-stealing if available in Scala Native runtime

### Phase 3: Parallel Operators

#### Parallel Combinators

```scala
extension [A](rx: Rx[A])
  /** Map with bounded parallelism */
  def parMap[B](parallelism: Int)(f: A => B): Rx[B]

  /** FlatMap with concurrent inner streams */
  def parFlatMap[B](parallelism: Int)(f: A => Rx[B]): Rx[B]

  /** Merge emissions from another stream */
  def merge[B >: A](other: Rx[B]): Rx[B]

  /** Run concurrently with another, take first result */
  def race[B](other: Rx[B]): Rx[Either[A, B]]

object Rx:
  /** Run multiple Rx in parallel, return first to complete */
  def race[A](a: Rx[A], b: Rx[A]): Rx[A]

  /** Run multiple Rx in parallel, collect all results */
  def parSequence[A](rxs: Seq[Rx[A]]): Rx[Seq[A]]

  /** Traverse with bounded parallelism */
  def parTraverse[A, B](as: Seq[A], parallelism: Int)(f: A => Rx[B]): Rx[Seq[B]]

  /** Run all and return when all complete */
  def parZip[A, B](a: Rx[A], b: Rx[B]): Rx[(A, B)]
```

### Phase 4: Resource Safety and Backpressure

#### RxResource - Bracket Semantics

```scala
trait RxResource[A]:
  /** Use the resource with guaranteed cleanup */
  def use[B](f: A => Rx[B]): Rx[B]

  /** Compose resources */
  def flatMap[B](f: A => RxResource[B]): RxResource[B]
  def map[B](f: A => B): RxResource[B]

object RxResource:
  /** Create a resource with acquire and release */
  def make[A](acquire: Rx[A])(release: A => Rx[Unit]): RxResource[A]

  /** Create from AutoCloseable */
  def fromAutoCloseable[A <: AutoCloseable](acquire: Rx[A]): RxResource[A]

  /** Lift a pure value (no cleanup needed) */
  def pure[A](a: A): RxResource[A]
```

#### RxBoundedQueue - Backpressure Support

```scala
trait RxBoundedQueue[A] extends RxSource[A]:
  /** Offer an element, returning false if full */
  def tryOffer(a: A): Rx[Boolean]

  /** Offer an element, semantically blocking until space available */
  def offer(a: A): Rx[Unit]

  /** Take an element, semantically blocking until available */
  def take: Rx[A]

  /** Current queue size */
  def size: Rx[Int]

object RxBoundedQueue:
  def apply[A](capacity: Int): Rx[RxBoundedQueue[A]]
```

## Cross-Platform Considerations

| Feature | JVM | Scala.js | Scala Native |
|---------|-----|----------|--------------|
| `RxRef` | AtomicReference | Direct mutation | AtomicReference |
| `RxDeferred` | Lock-free with CAS | Promise-based | Lock-free with CAS |
| `RxSemaphore` | Lock-free queue | Sequential permits | Lock-free queue |
| `RxFiber.start` | ForkJoinPool | Microtask queue | Thread pool |
| `parMap(n)` | True parallelism | Sequential (n=1) | True parallelism |
| `RxScheduler` | Work-stealing pool | Event loop | Fixed pool |

## Migration Path

### Existing Code Compatibility

All existing uni-rx code continues to work:
```scala
// Before and after - no changes needed
Rx.fromSeq(1 to 10)
  .map(_ * 2)
  .filter(_ > 5)
  .run(println)
```

### Opting into Parallelism

```scala
// Sequential (existing behavior)
Rx.fromSeq(urls)
  .flatMap(url => fetchUrl(url))
  .run(process)

// Parallel (new capability)
Rx.fromSeq(urls)
  .parFlatMap(parallelism = 10)(url => fetchUrl(url))
  .run(process)
```

## Performance Targets

| Metric | Target |
|--------|--------|
| Fiber creation overhead | < 1μs |
| Context switch between fibers | < 100ns |
| `parMap` scaling | Linear up to core count |
| Memory per fiber | < 1KB |
| Throughput (ops/sec) | > 1M on 8-core machine |

## Testing Strategy

1. **Unit tests**: Each primitive in isolation
2. **Stress tests**: High contention scenarios
3. **Cross-platform tests**: Verify behavior consistency
4. **Benchmarks**: Compare with cats-effect baseline
5. **Property-based tests**: Verify concurrency invariants

## References

- [Cats Effect 3 Concurrency](https://typelevel.org/blog/2020/10/30/concurrency-in-ce3.html)
- [Cats Effect Thread Model](https://typelevel.org/cats-effect/docs/thread-model)
- [Work-Stealing Scheduler Design](https://github.com/typelevel/cats-effect/issues/634)
- [Java ForkJoinPool](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinPool.html)
