# Off-heap memory: `wvlet.uni.canvas`, ported from airframe-canvas

## Context

airframe-canvas offers `Long`-addressed buffers that can be larger than the 2 GB `Array[Byte]` limit and can be freed right away, but it runs only on the JVM and is built on `sun.misc.Unsafe`. JDK 23 deprecates Unsafe's memory-access methods for removal (JEP 471), and JDK 24 warns when they are used (JEP 498). Uni has no dependencies beyond the Scala standard library and targets the JVM, Scala.js, and Scala Native.

## Decision

- One `Canvas` API in shared code. Multi-byte values are little-endian on every platform, with `*BigEndian` variants. airframe used the native byte order; a fixed order keeps data written on one platform readable on another.
- Every access is bounds-checked and throws `IndexOutOfBoundsException`. With Unsafe, a bad offset could crash the JVM. Access after `close()` throws `IllegalStateException`.
- Heap canvases (`HeapCanvas`) are pure Scala in shared code, so they work everywhere, including JVMs older than 22.
- JVM: FFM only. Each allocation gets its own shared `Arena` (any thread may access or close it). Java 17–21 get `UnsupportedOperationException` and `Canvas.isOffHeapSupported == false`, the same pattern as `PosixPty`. An Unsafe fallback for Java 17–21 was rejected at the design gate to keep Uni free of `sun.misc.Unsafe`.
- GC safety net through a `MemoryOwner` that every canvas and slice references: a `java.lang.ref.Cleaner` on the JVM and a `FinalizationRegistry` on Scala.js. The cleanup action captures only the `Allocation`, never the owner, or the owner would never become unreachable. FFM access methods call `Reference.reachabilityFence(owner)`, so the JIT cannot treat a canvas as unreachable in the middle of a long copy and let the cleaner close its arena.
- `OffHeapMemoryAllocator` tracks live allocations in a map of `Allocation`s (id, size, free function), not of canvases, so tracking does not stop GC. `Allocation.release()` is idempotent (CAS).
- Scala.js: an `ArrayBuffer` with `DataView`/`Int8Array` facades that take JS numbers for offsets, since the standard facades take `Int`. `close()` detaches the buffer with `ArrayBuffer.transfer(0)` where it exists (ES2024, Node.js 21+).
- Scala Native: `malloc`/`free` and `memcpy`/`memmove`. There is no GC hook, so memory is freed only by `close()` or by closing the allocator. Each access checks the open flag, but a `close()` from another thread during an access is not guarded; this is documented.

## Consequences

- airframe-canvas users migrate to `wvlet.uni.canvas.Canvas`, with `release` replaced by `close()` and data written in native order now read as little-endian (the same bytes on x86 and ARM).
- On the JVM, off-heap canvases need Java 22+. FFM arenas zero-fill memory, so the docs only promise "not guaranteed to be zero-filled", which also covers `malloc` on Native.
- The msgpack `Buffer` traits (`Int` offsets, a capacity-exception protocol) are unchanged. Canvas could back them later.
