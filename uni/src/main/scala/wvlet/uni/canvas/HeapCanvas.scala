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

/**
  * A canvas over `size` bytes of a heap byte array starting at `base`. Values are encoded in
  * little-endian byte order in pure Scala, so this works the same way on every platform.
  */
final class HeapCanvas private[canvas] (
    private[canvas] val array: Array[Byte],
    private[canvas] val base: Int,
    sizeInBytes: Int
) extends Canvas:
  override def size: Long         = sizeInBytes.toLong
  override def isOffHeap: Boolean = false

  /**
    * The array index of the given canvas offset
    */
  private[canvas] inline def arrayIndex(offset: Long): Int = base + offset.toInt

  private inline def at(offset: Long, length: Int): Int =
    checkRange(offset, length)
    arrayIndex(offset)

  private inline def b(i: Int): Int = array(i) & 0xff

  override def readByte(offset: Long): Byte = array(at(offset, 1))

  override def readShort(offset: Long): Short =
    val i = at(offset, 2)
    (b(i) | (b(i + 1) << 8)).toShort

  override def readInt(offset: Long): Int =
    val i = at(offset, 4)
    b(i) | (b(i + 1) << 8) | (b(i + 2) << 16) | (b(i + 3) << 24)

  override def readLong(offset: Long): Long =
    val i  = at(offset, 8)
    val lo = b(i) | (b(i + 1) << 8) | (b(i + 2) << 16) | (b(i + 3) << 24)
    val hi = b(i + 4) | (b(i + 5) << 8) | (b(i + 6) << 16) | (b(i + 7) << 24)
    (hi.toLong << 32) | (lo.toLong & 0xffffffffL)

  override def readBytes(offset: Long, dest: Array[Byte], destOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(dest, destOffset, length)
    val i = at(offset, length)
    System.arraycopy(array, i, dest, destOffset, length)

  override def readBytes(offset: Long, dest: Canvas, destOffset: Long, length: Long): Unit =
    checkRange(offset, length)
    dest.writeBytes(destOffset, array, arrayIndex(offset), length.toInt)

  override def writeByte(offset: Long, v: Byte): Unit = array(at(offset, 1)) = v

  override def writeShort(offset: Long, v: Short): Unit =
    val i = at(offset, 2)
    array(i) = v.toByte
    array(i + 1) = (v >> 8).toByte

  override def writeInt(offset: Long, v: Int): Unit =
    val i = at(offset, 4)
    array(i) = v.toByte
    array(i + 1) = (v >> 8).toByte
    array(i + 2) = (v >> 16).toByte
    array(i + 3) = (v >> 24).toByte

  override def writeLong(offset: Long, v: Long): Unit =
    val i = at(offset, 8)
    var k = 0
    while k < 8 do
      array(i + k) = (v >> (8 * k)).toByte
      k += 1

  override def writeBytes(offset: Long, src: Array[Byte], srcOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(src, srcOffset, length)
    val i = at(offset, length)
    System.arraycopy(src, srcOffset, array, i, length)

  override def fill(offset: Long, length: Long, value: Byte): Unit =
    checkRange(offset, length)
    val i = arrayIndex(offset)
    java.util.Arrays.fill(array, i, i + length.toInt, value)

  override def slice(offset: Long, length: Long): Canvas =
    checkRange(offset, length)
    HeapCanvas(array, arrayIndex(offset), length.toInt)

  override def close(): Unit = ()

end HeapCanvas
