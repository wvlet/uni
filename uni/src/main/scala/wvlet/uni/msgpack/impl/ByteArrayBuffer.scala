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
package wvlet.uni.msgpack.impl

import wvlet.uni.msgpack.spi.*

/**
  * `require` without its cost: `require`'s message is a by-name argument, so every call allocates a
  * closure on Scala Native even when the check passes, and these checks run on every buffer write.
  * Inlined, the message is built only on failure. Throws what `require` throws.
  */
private inline def check(inline requirement: Boolean, inline message: String = ""): Unit =
  if !requirement then
    throw IllegalArgumentException(
      if message.isEmpty then
        "requirement failed"
      else
        s"requirement failed: ${message}"
    )

object ByteArrayBuffer:
  def newBuffer(size: Int): ByteArrayBuffer = apply(new Array[Byte](size))

  def apply(a: Array[Byte]): ByteArrayBuffer = fromArray(a, 0, a.length)

  def fromArray(a: Array[Byte], offset: Int, size: Int): ByteArrayBuffer =
    check(
      offset + size <= a.length,
      s"input array is smaller than offset:${offset} + size:${size}: ${a.length}"
    )
    new ByteArrayBuffer(a, offset, size)

  val emptyReadBuffer  = ByteArrayBuffer(Array.emptyByteArray)
  val emptyWriteBuffer = ByteArrayBuffer(Array.emptyByteArray)

/**
  */
class ByteArrayBuffer(a: Array[Byte], offset: Int, size: Int)
    extends ByteArrayBufferBase(a, offset, size)

// Starts at 64 bytes, which holds most small values without regrowing
class InfiniteByteArrayBuffer extends ByteArrayBufferBase(new Array[Byte](64), 0, 64):
  override def ensureCapacity(position: Int, requestedLength: Int): Unit =
    if !hasCapacity(position, requestedLength) then
      var newLength = a.length
      while newLength < position + requestedLength do
        newLength *= 2
      val newArray = new Array[Byte](newLength)
      System.arraycopy(a, 0, newArray, 0, position)
      a = newArray
      capacity = newLength

abstract class ByteArrayBufferBase(
    protected var a: Array[Byte],
    offset: Int,
    protected var capacity: Int
) extends ReadBuffer
    with WriteBuffer:
  check(offset >= 0, s"baseOffset ${offset} < 0")
  check(
    offset + capacity <= a.length,
    s"insufficient buffer size. baseOffset:${offset} + capacity:${capacity} <= array size:${a
        .length}"
  )

  def size: Int = capacity

  /** The backing array, for reading what was written without copying it */
  private[uni] def array: Array[Byte] = a

  def toByteArray(position: Int, arraySize: Int): Array[Byte] =
    check(arraySize >= 0)
    check(
      position + arraySize <= capacity,
      s"Insufficient array length (${a
          .length}, offset:${offset}, size:${capacity}) for slice(${position}, ${arraySize})"
    )
    if a.length == offset + position + arraySize then
      a
    else
      val newArray = new Array[Byte](arraySize)
      System.arraycopy(a, offset + position, newArray, 0, arraySize)
      newArray

  override def slice(position: Int, newSize: Int): ReadBuffer =
    check(
      position + newSize <= capacity,
      s"Insufficient array length (${a
          .length}, offset:${offset}, size:${size}) for slice(${position}, ${newSize})"
    )
    new ByteArrayBuffer(a, offset + position, newSize)

  def hasCapacity(position: Int, byteLength: Int): Boolean = position + byteLength <= capacity

  def ensureCapacity(position: Int, requestedLength: Int): Unit =
    if !hasCapacity(position, requestedLength) then
      throw new InsufficientBufferException(position, requestedLength)

  override def readByte(position: Int): Byte =
    ensureCapacity(position, 1)
    val pos = offset + position
    a(pos)

  override def readShort(position: Int): Short =
    ensureCapacity(position, 2)
    val pos = offset + position
    ((a(pos) << 8) | (a(pos + 1) & 0xff)).toShort

  override def readInt(position: Int): Int =
    ensureCapacity(position, 4)
    val pos = offset + position
    (
      (a(pos).toInt << 24) |
        ((a(pos + 1) & 0xff) << 16) |
        ((a(pos + 2) & 0xff) << 8) |
        (a(pos + 3) & 0xff)
    )

  override def readLong(position: Int): Long =
    ensureCapacity(position, 8)
    val pos = offset + position
    (
      (a(pos).toLong << 56) |
        ((a(pos + 1).toLong & 0xff) << 48) |
        ((a(pos + 2).toLong & 0xff) << 40) |
        ((a(pos + 3).toLong & 0xff) << 32) |
        ((a(pos + 4).toLong & 0xff) << 24) |
        ((a(pos + 5).toLong & 0xff) << 16) |
        ((a(pos + 6).toLong & 0xff) << 8) |
        (a(pos + 7).toLong & 0xff)
    )

  override def readBytes(position: Int, length: Int): Array[Byte] =
    ensureCapacity(position, length)
    val dest = new Array[Byte](length)
    System.arraycopy(a, offset + position, dest, 0, length)
    dest

  override def readBytes(position: Int, length: Int, dest: Array[Byte], destOffset: Int): Unit =
    ensureCapacity(position, length)
    System.arraycopy(a, offset + position, dest, destOffset, length)

  override def readBytes(position: Int, length: Int, dest: WriteBuffer, destIndex: Int): Unit =
    ensureCapacity(position, length)
    dest.writeBytes(destIndex, a, offset + position, length)

  // Decode from the array in place, without copying the bytes out first
  override def readString(position: Int, length: Int): String =
    ensureCapacity(position, length)
    wvlet.uni.util.Utf8.decode(a, offset + position, length)

  def writeByte(position: Int, v: Byte): Int =
    ensureCapacity(position, 1)
    val pos = offset + position
    a(pos) = v
    1

  def writeBytes(position: Int, source: Array[Byte], sourceOffset: Int, length: Int): Int =
    check(source != null, "source is null")
    check(
      sourceOffset + length <= source.length,
      s"Insufficient input buffer size ${source.length} for reading ${sourceOffset}+${length} bytes"
    )

    ensureCapacity(position, length)
    System.arraycopy(source, sourceOffset, a, offset + position, length)
    length

  override def writeBytes(position: Int, src: ReadBuffer, srcPosition: Int, length: Int): Int =
    check(src != null, "source is nul")
    ensureCapacity(position, length)
    src.readBytes(srcPosition, length, a, offset + position)
    length

  def writeShort(position: Int, v: Short): Int =
    ensureCapacity(position, 2)
    val pos = offset + position
    // Use big-endian order
    a(pos) = ((v & 0xff00) >> 8).toByte
    a(pos + 1) = (v & 0xff).toByte
    2

  def writeInt(position: Int, v: Int): Int =
    ensureCapacity(position, 4)
    val pos = offset + position
    // Use big-endian order
    a(pos) = ((v & 0xff000000) >> 24).toByte
    a(pos + 1) = ((v & 0xff0000) >> 16).toByte
    a(pos + 2) = ((v & 0xff00) >> 8).toByte
    a(pos + 3) = (v & 0xff).toByte
    4

  def writeLong(position: Int, v: Long): Int =
    ensureCapacity(position, 8)
    val pos = offset + position
    // Use big-endian order
    a(pos) = ((v & 0xff00000000000000L) >> 56).toByte
    a(pos + 1) = ((v & 0xff000000000000L) >> 48).toByte
    a(pos + 2) = ((v & 0xff0000000000L) >> 40).toByte
    a(pos + 3) = ((v & 0xff00000000L) >> 32).toByte
    a(pos + 4) = ((v & 0xff000000L) >> 24).toByte
    a(pos + 5) = ((v & 0xff0000L) >> 16).toByte
    a(pos + 6) = ((v & 0xff00L) >> 8).toByte
    a(pos + 7) = (v & 0xffL).toByte
    8

end ByteArrayBufferBase
