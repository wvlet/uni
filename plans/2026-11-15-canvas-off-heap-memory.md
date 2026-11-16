# Port airframe-canvas to uni as `wvlet.uni.canvas`

## Goals

- Give uni one `Canvas` API for large memory buffers (bigger than 2 GB, with `Long` offsets) that works the same way on the JVM, Scala.js, and Scala Native.
- Let apps allocate off-heap memory and free it right away with `close()`, without depending on GC timing or on the `-Xmx` limit.

## Background

- airframe-canvas only runs on the JVM and is built on `sun.misc.Unsafe`. JDK 23 deprecates Unsafe's memory-access methods for removal (JEP 471), and JDK 24 prints a warning when they're used (JEP 498).
- uni already calls the FFM API (`java.lang.foreign`) on Java 22+ for `PosixPty`, and checks `Runtime.version()` before loading the FFM-backed code. Canvas can follow the same pattern.
- uni has no dependencies beyond the Scala standard library. Native already uses `scala.scalanative.unsafe` and libc, and Scala.js has `ArrayBuffer` and `DataView`.
- The msgpack `Buffer` in `wvlet.uni.msgpack.spi` is a separate, `Int`-indexed abstraction. This PR leaves it as it is.

## Design

Lane: Standard. This adds a new public API to the `uni` artifact.

- **User flow**
  - `Canvas.newOffHeapCanvas(size)` allocates memory, `Canvas.newCanvas(size)` or `Canvas.wrap(bytes)` makes a heap canvas, and `close()` frees memory right away.
  - `OffHeapMemoryAllocator.allocatedMemorySize` reports how much memory is still held.
- **API:** keep airframe's methods (`readInt`, `writeLong`, `readBytes`, `slice`, `toByteArray`, and the rest) with `Long` offsets, in package `wvlet.uni.canvas`.
  - Multi-byte values are little-endian on every platform, with `*BigEndian` variants. airframe used the platform's native byte order, which is little-endian on x86 and ARM.
- **Bounds:** every read and write checks its offset and length and throws `IndexOutOfBoundsException`. With Unsafe, a bad offset could crash the JVM.
- **JVM backend:** FFM only, with no Unsafe (decided at the design gate).
  - Java 22+: `MemorySegment`, with a shared `Arena` per allocation, closed by `close()` or by a `java.lang.ref.Cleaner` after GC. This replaces airframe's own collector thread.
  - Older JVMs: `newOffHeapCanvas` throws `UnsupportedOperationException` with the reason, and `Canvas.isOffHeapSupported` returns false. This is the same pattern `PosixPty` uses.
  - Heap canvases are pure Scala over `Array[Byte]`, shared by all platforms, and work on every JVM version.
- **Scala.js backend:** an `ArrayBuffer` with a `DataView`. JS has no off-heap memory, so "off-heap" here means memory outside the Scala heap. `close()` detaches the buffer (`transfer(0)` where available) so it is freed early. Sizes are limited by the JS engine.
- **Scala Native backend:** `malloc`/`free` through `scala.scalanative.libc`, with pointer arithmetic for reads and writes. Memory is freed only by `close()`, because Native has no phantom references. The docs will say so.
- **Docs:** a new `docs/core/canvas.md` page, a row in `docs/guide/for-ai-agents.md` (with an Airframe→Uni mapping), and the matching update to the `plugins/uni` skill.
- Tests in shared code run on all three platforms: round trips for each type, endianness, slices, copies between canvases, bounds errors, and allocated-size accounting after `close()`.

## Alternatives and Why Not?

- **Unsafe fallback for Java 17–21.** Rejected at the design gate. FFM only keeps uni free of `sun.misc.Unsafe`.
- **Unsafe only, as airframe did.** Prints warnings on JDK 24+, and Unsafe's memory access is going away.
- **`ByteBuffer.allocateDirect`.** Each buffer is limited to 2 GB and can't be freed explicitly, which goes against the reasons canvas exists.
- **Reuse the msgpack `Buffer` traits.** Their `Int` offsets and capacity-exception protocol are built for streaming. Canvas could back them in a later PR.
