/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.canvas

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner
import java.lang.ref.Reference
import java.nio.ByteOrder

/**
  * An off-heap canvas over a `MemorySegment` of the Foreign Function & Memory API (Java 22+). The
  * segment checks every access: out-of-range offsets throw `IndexOutOfBoundsException`, and access
  * after `close()` throws `IllegalStateException`.
  */
private[canvas] final class FfmCanvas(
    private[canvas] val segment: MemorySegment,
    // Every slice references the owner, so the cleaner frees the memory only after all are gone
    owner: MemoryOwner
) extends Canvas:
  import FfmCanvas.*

  override def size: Long         = segment.byteSize()
  override def isOffHeap: Boolean = true

  // Keep the owner reachable until the access ends. Otherwise the JIT may treat this canvas as
  // unreachable during a long copy, and the cleaner could close the arena in the middle of it.
  private inline def keepAlive[A](inline body: A): A =
    try body
    finally Reference.reachabilityFence(owner)

  override def readByte(offset: Long): Byte   = keepAlive(segment.get(Byte_, offset))
  override def readShort(offset: Long): Short = keepAlive(segment.get(Short_, offset))
  override def readInt(offset: Long): Int     = keepAlive(segment.get(Int_, offset))
  override def readLong(offset: Long): Long   = keepAlive(segment.get(Long_, offset))

  override def readBytes(offset: Long, dest: Array[Byte], destOffset: Int, length: Int): Unit =
    keepAlive(MemorySegment.copy(segment, Byte_, offset, dest, destOffset, length))

  override def readBytes(offset: Long, dest: Canvas, destOffset: Long, length: Long): Unit =
    dest match
      case f: FfmCanvas =>
        keepAlive(f.keepAlive(MemorySegment.copy(segment, offset, f.segment, destOffset, length)))
      case _ =>
        keepAlive(super.readBytes(offset, dest, destOffset, length))

  override def writeByte(offset: Long, v: Byte): Unit   = keepAlive(segment.set(Byte_, offset, v))
  override def writeShort(offset: Long, v: Short): Unit = keepAlive(segment.set(Short_, offset, v))
  override def writeInt(offset: Long, v: Int): Unit     = keepAlive(segment.set(Int_, offset, v))
  override def writeLong(offset: Long, v: Long): Unit   = keepAlive(segment.set(Long_, offset, v))

  override def writeBytes(offset: Long, src: Array[Byte], srcOffset: Int, length: Int): Unit =
    keepAlive(MemorySegment.copy(src, srcOffset, segment, Byte_, offset, length))

  override def fill(offset: Long, length: Long, value: Byte): Unit =
    checkRange(offset, length)
    keepAlive(segment.asSlice(offset, length).fill(value))

  override def slice(offset: Long, length: Long): Canvas =
    checkRange(offset, length)
    owner.allocation.ensureOpen()
    FfmCanvas(segment.asSlice(offset, length), owner)

  override def close(): Unit = owner.allocation.release()

end FfmCanvas

private[canvas] object FfmCanvas:
  private val Byte_ : ValueLayout.OfByte   = ValueLayout.JAVA_BYTE
  private val Short_ : ValueLayout.OfShort = ValueLayout
    .JAVA_SHORT_UNALIGNED
    .withOrder(ByteOrder.LITTLE_ENDIAN)

  private val Int_ : ValueLayout.OfInt = ValueLayout
    .JAVA_INT_UNALIGNED
    .withOrder(ByteOrder.LITTLE_ENDIAN)

  private val Long_ : ValueLayout.OfLong = ValueLayout
    .JAVA_LONG_UNALIGNED
    .withOrder(ByteOrder.LITTLE_ENDIAN)

  // Frees the memory of canvases that become unreachable without close()
  private lazy val cleaner: Cleaner = Cleaner.create()

  /**
    * True when new canvases get memory from libc `malloc`, which skips the zero-filling of
    * `Arena.allocate`. It needs native access for Uni's module (`--enable-native-access`).
    */
  def usesMalloc: Boolean = NativeMalloc.available

  def allocate(allocator: OffHeapMemoryAllocator, size: Long): Canvas = allocate(
    allocator,
    size,
    NativeMalloc.available
  )

  private[canvas] def allocate(
      allocator: OffHeapMemoryAllocator,
      size: Long,
      useMalloc: Boolean
  ): Canvas =
    // A shared arena lets any thread access the memory and close it
    val arena   = Arena.ofShared()
    val segment =
      try
        if useMalloc then
          // Closing the arena calls free
          NativeMalloc.allocate(size, arena)
        else
          // Zero-fills the memory
          arena.allocate(size, 1)
      catch
        case e: Throwable =>
          arena.close()
          throw e
    val allocation = allocator.register(size, () => arena.close())
    val owner      = MemoryOwner(allocation)
    // The action must not reference the owner, or the owner never becomes unreachable
    cleaner.register(owner, () => allocation.release())
    FfmCanvas(segment, owner)

end FfmCanvas
