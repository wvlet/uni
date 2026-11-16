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

import java.nio.ByteOrder
import scala.scalanative.libc.stdlib
import scala.scalanative.libc.string
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

private[canvas] object CanvasCompat:
  val unsupportedReason: Option[String] = None

  def allocate(allocator: OffHeapMemoryAllocator, size: Long): Canvas =
    // malloc(0) may return null, so allocate at least one byte
    val ptr = stdlib.malloc(math.max(size, 1L).toUSize)
    if ptr == null then
      throw OutOfMemoryError(s"Failed to allocate ${size} bytes of off-heap memory")
    val allocation = allocator.register(size, () => stdlib.free(ptr))
    NativeCanvas(ptr, size, MemoryOwner(allocation))

/**
  * An off-heap canvas over memory from `malloc`. Scala Native has no GC hook for freeing it, so the
  * memory is freed only by `close()` or by closing its allocator.
  */
private[canvas] final class NativeCanvas(
    private[canvas] val ptr: Ptr[Byte],
    override val size: Long,
    owner: MemoryOwner
) extends Canvas:
  import NativeCanvas.*

  override def isOffHeap: Boolean = true

  // Check the range and that the memory is not freed yet, then return the address
  private inline def at(offset: Long, length: Long): Ptr[Byte] =
    checkRange(offset, length)
    owner.allocation.ensureOpen()
    ptr + offset

  override def readByte(offset: Long): Byte   = !at(offset, 1)
  override def readShort(offset: Long): Short = toLE(!at(offset, 2).asInstanceOf[Ptr[Short]])
  override def readInt(offset: Long): Int     = toLE(!at(offset, 4).asInstanceOf[Ptr[Int]])
  override def readLong(offset: Long): Long   = toLE(!at(offset, 8).asInstanceOf[Ptr[Long]])

  override def readBytes(offset: Long, dest: Array[Byte], destOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(dest, destOffset, length)
    val p = at(offset, length)
    if length > 0 then
      string.memcpy(dest.at(destOffset), p, length.toUSize)

  override def readBytes(offset: Long, dest: Canvas, destOffset: Long, length: Long): Unit =
    dest match
      case n: NativeCanvas =>
        val src = at(offset, length)
        val dst = n.at(destOffset, length)
        // The canvases may share memory, so use memmove
        string.memmove(dst, src, length.toUSize)
      case _ =>
        super.readBytes(offset, dest, destOffset, length)

  override def writeByte(offset: Long, v: Byte): Unit   = !at(offset, 1) = v
  override def writeShort(offset: Long, v: Short): Unit =
    !at(offset, 2).asInstanceOf[Ptr[Short]] = toLE(v)

  override def writeInt(offset: Long, v: Int): Unit =
    !at(offset, 4).asInstanceOf[Ptr[Int]] = toLE(v)

  override def writeLong(offset: Long, v: Long): Unit =
    !at(offset, 8).asInstanceOf[Ptr[Long]] = toLE(v)

  override def writeBytes(offset: Long, src: Array[Byte], srcOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(src, srcOffset, length)
    val p = at(offset, length)
    if length > 0 then
      string.memcpy(p, src.at(srcOffset), length.toUSize)

  override def slice(offset: Long, length: Long): Canvas =
    at(offset, length)
    NativeCanvas(ptr + offset, length, owner)

  override def close(): Unit = owner.allocation.release()

end NativeCanvas

private[canvas] object NativeCanvas:
  private val isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

  private inline def toLE(v: Short): Short =
    if isLittleEndian then
      v
    else
      java.lang.Short.reverseBytes(v)

  private inline def toLE(v: Int): Int =
    if isLittleEndian then
      v
    else
      java.lang.Integer.reverseBytes(v)

  private inline def toLE(v: Long): Long =
    if isLittleEndian then
      v
    else
      java.lang.Long.reverseBytes(v)

end NativeCanvas
