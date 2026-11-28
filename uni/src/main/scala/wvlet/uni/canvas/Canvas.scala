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

import java.lang as jl
import java.nio.ByteBuffer

/**
  * A memory buffer addressed by `Long` offsets, so it can be larger than the 2 GB limit of
  * `Array[Byte]`. A canvas is either backed by a heap byte array ([[Canvas.newCanvas]],
  * [[Canvas.wrap]]) or by memory outside the managed heap ([[Canvas.newOffHeapCanvas]]).
  *
  *   - Multi-byte values are read and written in little-endian byte order on every platform. Use
  *     the `*BigEndian` variants for network byte order.
  *   - Every read and write checks its range and throws `IndexOutOfBoundsException` when it falls
  *     outside the canvas. Accessing a closed off-heap canvas throws `IllegalStateException`.
  *   - [[close]] frees off-heap memory right away. Slices share the memory of their parent, so
  *     closing any of them frees the memory for all.
  */
abstract class Canvas extends AutoCloseable:
  /**
    * The canvas size in bytes
    */
  def size: Long

  /**
    * True if this canvas is backed by memory outside the managed heap
    */
  def isOffHeap: Boolean

  def readByte(offset: Long): Byte
  def readShort(offset: Long): Short
  def readInt(offset: Long): Int
  def readLong(offset: Long): Long

  def readBoolean(offset: Long): Boolean      = readByte(offset) != 0
  def readFloat(offset: Long): Float          = jl.Float.intBitsToFloat(readInt(offset))
  def readDouble(offset: Long): Double        = jl.Double.longBitsToDouble(readLong(offset))
  def readShortBigEndian(offset: Long): Short = jl.Short.reverseBytes(readShort(offset))
  def readIntBigEndian(offset: Long): Int     = jl.Integer.reverseBytes(readInt(offset))
  def readLongBigEndian(offset: Long): Long   = jl.Long.reverseBytes(readLong(offset))

  /**
    * Copy `length` bytes at `offset` into `dest` starting at `destOffset`
    */
  def readBytes(offset: Long, dest: Array[Byte], destOffset: Int, length: Int): Unit

  /**
    * Copy `length` bytes at `offset` into the canvas `dest` starting at `destOffset`
    */
  def readBytes(offset: Long, dest: Canvas, destOffset: Long, length: Long): Unit =
    dest match
      case h: HeapCanvas =>
        h.checkRange(destOffset, length)
        readBytes(offset, h.array, h.arrayIndex(destOffset), length.toInt)
      case _ =>
        Canvas.copyInChunks(this, offset, dest, destOffset, length)

  /**
    * Return a new byte array holding `length` bytes at `offset`
    */
  def readBytes(offset: Long, length: Long): Array[Byte] =
    if !length.isValidInt || length < 0 then
      throw IllegalArgumentException(
        s"read length must be between 0 and ${Int.MaxValue}: ${length}"
      )
    val b = Array.ofDim[Byte](length.toInt)
    readBytes(offset, b, 0, length.toInt)
    b

  def writeByte(offset: Long, v: Byte): Unit
  def writeShort(offset: Long, v: Short): Unit
  def writeInt(offset: Long, v: Int): Unit
  def writeLong(offset: Long, v: Long): Unit

  def writeBoolean(offset: Long, v: Boolean): Unit = writeByte(
    offset,
    if v then
      1
    else
      0
  )

  def writeFloat(offset: Long, v: Float): Unit   = writeInt(offset, jl.Float.floatToRawIntBits(v))
  def writeDouble(offset: Long, v: Double): Unit = writeLong(
    offset,
    jl.Double.doubleToRawLongBits(v)
  )

  def writeShortBigEndian(offset: Long, v: Short): Unit = writeShort(
    offset,
    jl.Short.reverseBytes(v)
  )

  def writeIntBigEndian(offset: Long, v: Int): Unit   = writeInt(offset, jl.Integer.reverseBytes(v))
  def writeLongBigEndian(offset: Long, v: Long): Unit = writeLong(offset, jl.Long.reverseBytes(v))

  def writeBytes(offset: Long, src: Array[Byte]): Unit = writeBytes(offset, src, 0, src.length)

  /**
    * Copy `length` bytes of `src` starting at `srcOffset` to `offset` of this canvas
    */
  def writeBytes(offset: Long, src: Array[Byte], srcOffset: Int, length: Int): Unit

  /**
    * Copy `length` bytes of the canvas `src` starting at `srcOffset` to `offset` of this canvas
    */
  def writeBytes(offset: Long, src: Canvas, srcOffset: Long, length: Long): Unit = src.readBytes(
    srcOffset,
    this,
    offset,
    length
  )

  /**
    * Set `length` bytes at `offset` to `value`. Use it to clear memory that a reused or freshly
    * allocated off-heap canvas may still hold.
    */
  def fill(offset: Long, length: Long, value: Byte): Unit =
    checkRange(offset, length)
    val buf     = Array.fill[Byte](math.min(length, Canvas.copyChunkSize.toLong).toInt)(value)
    var written = 0L
    while written < length do
      val n = math.min(length - written, buf.length.toLong).toInt
      writeBytes(offset + written, buf, 0, n)
      written += n

  /**
    * Set every byte of this canvas to `value`
    */
  def fill(value: Byte): Unit = fill(0, size, value)

  /**
    * Return a view of `length` bytes at `offset`, sharing the memory of this canvas
    */
  def slice(offset: Long, length: Long): Canvas

  /**
    * Copy the whole canvas into a new byte array
    */
  def toByteArray: Array[Byte] = readBytes(0, size)

  /**
    * Free the off-heap memory of this canvas. Heap canvases have nothing to free. Calling close
    * more than once is safe.
    */
  def close(): Unit

  /**
    * Throw `IndexOutOfBoundsException` unless `[offset, offset + length)` is inside this canvas
    */
  protected[canvas] final def checkRange(offset: Long, length: Long): Unit =
    if offset < 0 || length < 0 || offset > size - length then
      throw IndexOutOfBoundsException(
        s"Range [${offset}, ${offset} + ${length}) is outside the canvas of size ${size}"
      )

end Canvas

object Canvas:
  /**
    * Create a new canvas backed by a zero-filled heap byte array
    */
  def newCanvas(size: Int): Canvas = wrap(Array.ofDim[Byte](size))

  /**
    * Allocate a new off-heap canvas from the default allocator. Its contents are unspecified: they
    * may be zeros or bytes left by earlier use of the memory, so write before you read, or call
    * `fill(0)`. Release it with `close()`.
    *
    * @throws UnsupportedOperationException
    *   if this platform cannot allocate off-heap memory (JVMs older than Java 22)
    */
  def newOffHeapCanvas(size: Long): Canvas = OffHeapMemoryAllocator.default.newCanvas(size)

  /**
    * True if [[newOffHeapCanvas]] works on this platform. It is false on JVMs older than Java 22,
    * which lack the Foreign Function & Memory API.
    */
  def isOffHeapSupported: Boolean = CanvasCompat.unsupportedReason.isEmpty

  def wrap(arr: Array[Byte]): Canvas = wrap(arr, 0, arr.length)

  /**
    * Create a canvas that reads and writes `length` bytes of `arr` starting at `offset`, without
    * copying
    */
  def wrap(arr: Array[Byte], offset: Int, length: Int): Canvas =
    checkArrayRange(arr, offset, length)
    HeapCanvas(arr, offset, length)

  /**
    * Create a canvas over the remaining bytes of an array-backed ByteBuffer, without copying
    */
  def wrap(buf: ByteBuffer): Canvas =
    if buf.hasArray then
      wrap(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining())
    else
      throw IllegalArgumentException(
        s"Canvas.wrap supports only array-backed ByteBuffers: ${buf.getClass.getName}"
      )

  private[canvas] val copyChunkSize = 8192

  /**
    * Throw `IndexOutOfBoundsException` unless `[offset, offset + length)` is inside `arr`
    */
  private[canvas] def checkArrayRange(arr: Array[Byte], offset: Int, length: Int): Unit =
    if offset < 0 || length < 0 || offset > arr.length - length then
      throw IndexOutOfBoundsException(
        s"Range [${offset}, ${offset} + ${length}) is outside the array of size ${arr.length}"
      )

  /**
    * Copy bytes between canvases of different kinds through a small heap buffer
    */
  private[canvas] def copyInChunks(
      src: Canvas,
      srcOffset: Long,
      dest: Canvas,
      destOffset: Long,
      length: Long
  ): Unit =
    src.checkRange(srcOffset, length)
    dest.checkRange(destOffset, length)
    val buf    = Array.ofDim[Byte](math.min(length, copyChunkSize.toLong).toInt)
    var copied = 0L
    while copied < length do
      val n = math.min(length - copied, buf.length.toLong).toInt
      src.readBytes(srcOffset + copied, buf, 0, n)
      dest.writeBytes(destOffset + copied, buf, 0, n)
      copied += n

end Canvas
