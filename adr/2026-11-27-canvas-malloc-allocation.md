# Off-heap canvases skip zero-filling through FFM `malloc`; pooling stays in the engine

## Context

Engines that keep large working sets off-heap (the Wvlet engine's join-build and aggregation tables, compact string vectors, and spill buffers at SF100) allocate many buffers and overwrite them right away. The JVM backend of [`2026-11-15-canvas-off-heap-memory.md`](2026-11-15-canvas-off-heap-memory.md) gets each canvas from `Arena.ofShared().allocate`, which the JDK specifies to zero-fill. No supported JDK API skips that: `ByteBuffer.allocateDirect` zero-fills too, and that ADR rejected `sun.misc.Unsafe.allocateMemory` (deprecated for removal by JEP 471).

Measured on an Apple M5 Pro (Java 25.0.3, load average about 8, so the numbers are indicative): `./sbt "uniJVM/Test/runMain wvlet.uni.canvas.CanvasAllocBench"`. `alloc` gets and frees a buffer; `touch` also writes one byte per 4 KB page. `slab slice` takes `slice` of one pre-allocated canvas.

| Size | Operation | `new Array[Byte]` | Arena (zero-fill) | malloc | slab slice |
|------|-----------|----------------:|------------------:|-------:|-----------:|
| 64 KB | alloc | 1.65 µs | 24.32 µs | 19.42 µs | 0.03 µs |
| 64 KB | touch | 0.47 µs | 22.86 µs | 19.33 µs | 0.01 µs |
| 1 MB | alloc | 5.63 µs | 34.65 µs | 19.45 µs | 0.01 µs |
| 1 MB | touch | 45.04 µs | 35.78 µs | 25.27 µs | 0.63 µs |
| 64 MB | alloc | 457.90 µs | 1062.16 µs | 19.15 µs | 0.01 µs |
| 64 MB | touch | 399.60 µs | 1200.29 µs | 107.09 µs | 83.75 µs |

- Zero-filling dominates at large sizes: 64 MB costs about 1 ms through `Arena.allocate` and 19 µs through `malloc`.
- About 19 µs of every off-heap canvas is the cross-thread handshake of closing its shared arena, whatever the size. Heap arrays avoid it by leaving the memory to GC (the JIT may also remove allocations it can prove unused).
- Slicing pre-allocated memory costs nanoseconds.

## Decision

- On the JVM, new off-heap canvases get their memory from libc `malloc` through FFM downcalls (`NativeMalloc.java`, in Java so `invokeExact` does not box). `reinterpret(size, arena, cleanup)` attaches the memory to the canvas's own shared arena, and the cleanup calls `free` when the arena closes.
- The safety guarantees of the Canvas ADR are unchanged, because the arena still owns the segment: bounds checks, `IllegalStateException` after `close()`, no use-after-free when another thread closes the canvas during an access, and the `Cleaner` safety net. `malloc` returning NULL throws `OutOfMemoryError`; size 0 allocates one byte.
- `Linker` and `reinterpret` are restricted methods. Uni uses `malloc` only when `Module.isNativeAccessEnabled()` is true for its module (`--enable-native-access=ALL-UNNAMED` on the class path) and libc exports `malloc` and `free`. Otherwise it silently keeps `Arena.allocate`, so Uni never prints the native-access warning. `-Dwvlet.uni.canvas.useMalloc=false` turns `malloc` off.
- Off-heap canvas contents are unspecified on every platform (zeros or leftover bytes). The contract promises speed, not dirty memory: callers write before they read or call the new `Canvas.fill(offset, length, value)` (FFM `fill`, `memset`, `Int8Array.fill`, `Arrays.fill`).
- No memory pool in Uni. Pool policy (size classes, memory budgets, when to spill) is specific to each engine, and a DBMS such as Wvlet manages its own pool. Uni gives it the primitives: a fast large allocation, `slice` (nanoseconds, no handshake), and `fill` to clear reused ranges. A pool inside Canvas was proposed and dropped at the design gate.

## Consequences

- Applications that run with `--enable-native-access=ALL-UNNAMED` (the `./sbt` launcher does, so Uni's own tests cover the `malloc` path) skip zero-filling of new off-heap memory. Others behave as before.
- Releasing a canvas still pays the shared-arena handshake (about 19 µs). Engines that allocate and free many small buffers should carve them out of large canvases with `slice` instead of allocating each one.
- Scala.js `ArrayBuffer` remains zero-filled by the engine, and Scala Native already used `malloc`; both gain only `fill`.
