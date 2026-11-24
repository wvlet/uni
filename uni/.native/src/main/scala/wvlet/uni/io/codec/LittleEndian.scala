package wvlet.uni.io.codec

import scala.scalanative.libc.string.memcpy
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/**
  * Bulk reads of little-endian values from byte arrays, for decoding PLAIN Parquet pages. Scala
  * Native targets are little-endian, so same-width reads are one `memcpy`. Callers check the
  * ranges.
  */
private[uni] object LittleEndian:
  def ints(src: Array[Byte], off: Int, dst: Array[Int], dstOff: Int, n: Int): Unit =
    if n > 0 then
      memcpy(dst.atUnsafe(dstOff), src.atUnsafe(off), (n * 4).toCSize)

  def intsAsLongs(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = Word8.le32(src, off + i * 4).toLong
      i += 1

  def longs(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    if n > 0 then
      memcpy(dst.atUnsafe(dstOff), src.atUnsafe(off), (n * 8).toCSize)

  def floats(src: Array[Byte], off: Int, dst: Array[Float], dstOff: Int, n: Int): Unit =
    if n > 0 then
      memcpy(dst.atUnsafe(dstOff), src.atUnsafe(off), (n * 4).toCSize)

  def doubles(src: Array[Byte], off: Int, dst: Array[Double], dstOff: Int, n: Int): Unit =
    if n > 0 then
      memcpy(dst.atUnsafe(dstOff), src.atUnsafe(off), (n * 8).toCSize)

end LittleEndian
