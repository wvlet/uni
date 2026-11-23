package wvlet.uni.cloud.parquet.io

import wvlet.uni.cloud.parquet.ParquetException

/**
  * A cursor over a byte range of an array. Reads little-endian values and checks every read against
  * the range end, so corrupt lengths fail with a ParquetException instead of reading out of bounds.
  */
private[parquet] final class ByteReader(val buf: Array[Byte], start: Int, val limit: Int):
  require(start >= 0 && limit <= buf.length && start <= limit, "Invalid byte range")

  var pos: Int = start

  def this(buf: Array[Byte]) = this(buf, 0, buf.length)

  inline def remaining: Int = limit - pos

  def ensure(n: Int): Unit =
    if n < 0 || n > limit - pos then
      throw ParquetException.corrupt(
        s"needed ${n} bytes at offset ${pos}, but only ${limit - pos} remain"
      )

  def skip(n: Int): Unit =
    ensure(n)
    pos += n

  def readByte(): Byte =
    ensure(1)
    val b = buf(pos)
    pos += 1
    b

  def readUnsignedByte(): Int = readByte() & 0xff

  def readIntLE(): Int =
    ensure(4)
    val v = ByteReader.intLE(buf, pos)
    pos += 4
    v

  def readLongLE(): Long =
    ensure(8)
    val v = ByteReader.longLE(buf, pos)
    pos += 8
    v

  def readFloatLE(): Float = java.lang.Float.intBitsToFloat(readIntLE())

  def readDoubleLE(): Double = java.lang.Double.longBitsToDouble(readLongLE())

  def readBytes(n: Int): Array[Byte] =
    ensure(n)
    val out = new Array[Byte](n)
    System.arraycopy(buf, pos, out, 0, n)
    pos += n
    out

  /** Unsigned LEB128 varint, up to 32 bits */
  def readUnsignedVarInt(): Int =
    var result = 0
    var shift  = 0
    var b      = 0
    while
      if shift > 28 then
        throw ParquetException.corrupt("varint is too long")
      b = readUnsignedByte()
      result |= (b & 0x7f) << shift
      shift += 7
      (b & 0x80) != 0
    do
      (
    )
    result

  /** Unsigned LEB128 varint, up to 64 bits */
  def readUnsignedVarLong(): Long =
    var result = 0L
    var shift  = 0
    var b      = 0
    while
      if shift > 63 then
        throw ParquetException.corrupt("varint is too long")
      b = readUnsignedByte()
      result |= (b & 0x7fL) << shift
      shift += 7
      (b & 0x80) != 0
    do
      (
    )
    result

  def readZigZagInt(): Int =
    val n = readUnsignedVarInt()
    (n >>> 1) ^ -(n & 1)

  def readZigZagLong(): Long =
    val n = readUnsignedVarLong()
    (n >>> 1) ^ -(n & 1L)

end ByteReader

private[uni] object ByteReader:
  inline def intLE(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  inline def longLE(b: Array[Byte], i: Int): Long =
    (intLE(b, i).toLong & 0xffffffffL) | (intLE(b, i + 4).toLong << 32)

  inline def longBE(b: Array[Byte], i: Int): Long =
    var v = 0L
    var k = 0
    while k < 8 do
      v = (v << 8) | (b(i + k) & 0xffL)
      k += 1
    v
