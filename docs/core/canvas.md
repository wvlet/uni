# Canvas (Off-Heap Memory)

`wvlet.uni.canvas.Canvas` is a memory buffer addressed by `Long` offsets. It can be larger than
the 2 GB limit of `Array[Byte]`, and its off-heap form lives outside the managed heap, so it
doesn't count toward `-Xmx` on the JVM and you can free it right away with `close()`.

The API is the same on the JVM, Scala.js, and Scala Native. It is a port of airframe-canvas.

```scala
import wvlet.uni.canvas.Canvas

val c = Canvas.newOffHeapCanvas(1024)
try
  c.writeInt(0, 10)
  c.writeLongBigEndian(8, 1234L)
  c.readInt(0)           // 10
  c.readLongBigEndian(8) // 1234
finally
  c.close()              // free the memory now
```

## Creating canvases

| Factory | Memory |
|---------|--------|
| `Canvas.newOffHeapCanvas(size: Long)` | Off-heap memory, not guaranteed to be zero-filled. Free it with `close()` |
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

Close canvases explicitly, for example with `try`/`finally` or `scala.util.Using`. Freeing
after GC is a safety net for the JVM and Scala.js. It runs only when the GC notices the canvas
is unreachable, which can be much later.
