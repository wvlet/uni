# Canvas (Off-Heap Memory)

`wvlet.uni.canvas.Canvas` is a memory buffer addressed by `Long` offsets. It can be larger than
the 2 GB limit of `Array[Byte]`, and its off-heap form lives outside the managed heap, so it
doesn't count toward `-Xmx` on the JVM and you can free it right away with `close()`.

The API is the same on the JVM, Scala.js, and Scala Native. It is a port of airframe-canvas.

```scala
import wvlet.uni.canvas.Canvas
import wvlet.uni.control.Control

// withResource frees the memory when the block ends, even if it throws
Control.withResource(Canvas.newOffHeapCanvas(1024)) { c =>
  c.writeInt(0, 10)
  c.writeLongBigEndian(8, 1234L)
  c.readInt(0)           // 10
  c.readLongBigEndian(8) // 1234
}
```

## Creating canvases

| Factory | Memory |
|---------|--------|
| `Canvas.newOffHeapCanvas(size: Long)` | Off-heap memory with unspecified contents (see [Allocation speed](#allocation-speed)). Free it with `close()` |
| `Canvas.newCanvas(size: Int)` | A new zero-filled heap byte array |
| `Canvas.wrap(bytes)`, `Canvas.wrap(bytes, offset, length)` | An existing byte array, without copying |
| `Canvas.wrap(byteBuffer)` | The remaining bytes of an array-backed `ByteBuffer`, without copying |

`canvas.slice(offset, length)` returns a view that shares memory with its parent. Closing any
view frees the memory for all of them.

## Reading and writing

- `readByte`, `readShort`, `readInt`, `readLong`, `readFloat`, `readDouble`, `readBoolean`, and
  the matching `write*` methods take a `Long` offset.
- Multi-byte values use little-endian byte order on every platform. `readIntBigEndian`,
  `writeLongBigEndian`, and the other `*BigEndian` methods use network byte order.
- `readBytes` and `writeBytes` copy to and from byte arrays and other canvases. `toByteArray`
  copies a whole canvas (up to 2 GB).
- `fill(offset, length, value)` sets a range to one byte value, and `fill(value)` sets the whole
  canvas. Use `fill(0)` when you need zeros.
- Every access checks its range and throws `IndexOutOfBoundsException` when it falls outside the
  canvas. Using an off-heap canvas after `close()` throws `IllegalStateException`.

## Managing off-heap memory

`OffHeapMemoryAllocator` tracks the memory its canvases still hold. `Canvas.newOffHeapCanvas`
uses `OffHeapMemoryAllocator.default`. Create your own allocator to free a group of canvases
together:

```scala
import wvlet.uni.canvas.OffHeapMemoryAllocator

val allocator = OffHeapMemoryAllocator()
val a = allocator.newCanvas(4096)
val b = allocator.newCanvas(4096)
allocator.allocatedMemorySize // 8192
a.close()
allocator.allocatedMemorySize // 4096
allocator.close()             // frees b and anything else still open
```

## Allocation speed

`newOffHeapCanvas` takes the fastest allocation each platform offers, so the contents of a new
off-heap canvas are unspecified: they may be zeros or bytes left by earlier use of the memory.
Write before you read, or call `fill(0)`.

| Platform | New memory |
|----------|------------|
| JVM with native access enabled | libc `malloc` through the Foreign Function & Memory API, not zero-filled |
| JVM without native access | `Arena.allocate`, zero-filled |
| Scala.js | `ArrayBuffer`, zero-filled by the JS engine |
| Scala Native | `malloc`, not zero-filled |

On the JVM, calling `malloc` needs native access, so Uni uses it only when the JVM runs with
`--enable-native-access=ALL-UNNAMED` (or the module that holds Uni). Without the flag it zero-fills
and prints no warning. On a 64 MB canvas, skipping zero-filling cuts allocation from about 1 ms to
about 20 µs. The [ADR](https://github.com/wvlet/uni/blob/main/adr/2026-11-27-canvas-malloc-allocation.md)
has the numbers. `-Dwvlet.uni.canvas.useMalloc=false` turns `malloc` off.

Closing an off-heap canvas on the JVM takes about 20 µs whatever its size, because other threads
must stop using its memory first. If you allocate and free many small buffers, as a query engine
does, allocate a few large canvases and hand out `slice`s of them, which take nanoseconds. Keep
the pool in your application, where you know the sizes and the memory budget, and clear a reused
range with `fill` when your code expects zeros.

## Platforms

| Platform | Off-heap memory | Freed without `close()` |
|----------|-----------------|-------------------------|
| JVM (Java 22+) | A `MemorySegment` from the Foreign Function & Memory API | Yes, after GC (`java.lang.ref.Cleaner`) |
| JVM (Java 17–21) | Not supported: `newOffHeapCanvas` throws `UnsupportedOperationException` | — |
| Scala.js | A JavaScript `ArrayBuffer`; `close()` detaches it where `ArrayBuffer.transfer` exists (Node.js 21+) | Yes, after GC (`FinalizationRegistry`) |
| Scala Native | `malloc` / `free` | No: call `close()`, or close the allocator |

On Scala Native, don't close a canvas while another thread still reads or writes it. The JVM
and Scala.js report that case as `IllegalStateException`; Native can read freed memory.

`Canvas.isOffHeapSupported` tells you whether `newOffHeapCanvas` works on the current platform.
Heap canvases work everywhere.

Close canvases explicitly, with `Control.withResource` or a `Resource` from `wvlet.uni.control`. Freeing
after GC is a safety net for the JVM and Scala.js. It runs only when the GC notices the canvas
is unreachable, which can be much later.
