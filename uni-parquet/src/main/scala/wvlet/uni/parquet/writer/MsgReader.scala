package wvlet.uni.parquet.writer

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.parquet.ParquetException
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.ValueType

/**
  * Reads MessagePack values from a byte array in place, for the shredder. Unlike an `Unpacker`, it
  * can compare a map key with expected UTF-8 bytes without building a String, and it can jump back
  * to a value it skipped. Methods named `read*` expect the value at the cursor to have that type;
  * the shredder checks the format byte with [[peek]] first.
  */
private[parquet] final class MsgReader:
  private var buf: Array[Byte] = Array.emptyByteArray
  private var end: Int         = 0
  var pos: Int                 = 0

  def reset(bytes: Array[Byte]): Unit =
    buf = bytes
    pos = 0
    end = bytes.length

  def bytes: Array[Byte] = buf

  private def need(n: Int): Unit =
    if n < 0 || pos + n > end then
      throw ParquetException("Truncated MessagePack data")

  private def u8(at: Int): Int = buf(at) & 0xff

  private def u16(at: Int): Int = (u8(at) << 8) | u8(at + 1)

  private def i32(at: Int): Int =
    (u8(at) << 24) | (u8(at + 1) << 16) | (u8(at + 2) << 8) | u8(at + 3)

  private def i64(at: Int): Long = (i32(at).toLong << 32) | (i32(at + 4).toLong & 0xffffffffL)

  /** The format byte of the next value */
  def peek: Int =
    need(1)
    u8(pos)

  def isNil: Boolean = peek == 0xc0

  def isString: Boolean =
    val b = peek
    (b >= 0xa0 && b <= 0xbf) || (b >= 0xd9 && b <= 0xdb)

  def isBinary: Boolean =
    val b = peek
    b >= 0xc4 && b <= 0xc6

  def isMap: Boolean =
    val b = peek
    (b >= 0x80 && b <= 0x8f) || b == 0xde || b == 0xdf

  def isArray: Boolean =
    val b = peek
    (b >= 0x90 && b <= 0x9f) || b == 0xdc || b == 0xdd

  /** An integer that fits in a Long (uint64 values past Long.MaxValue are not) */
  def isLong: Boolean =
    val b = peek
    b <= 0x7f || b >= 0xe0 || (b >= 0xcc && b <= 0xce) || (b >= 0xd0 && b <= 0xd3) ||
    (b == 0xcf && { need(9); buf(pos + 1) >= 0 })

  def isFloat: Boolean =
    val b = peek
    b == 0xca || b == 0xcb

  def isBoolean: Boolean =
    val b = peek
    b == 0xc2 || b == 0xc3

  /** The timestamp extension (type -1) */
  def isTimestamp: Boolean =
    peek match
      case 0xd6 | 0xd7 =>
        need(2)
        buf(pos + 1) == -1
      case 0xc7 =>
        need(3)
        buf(pos + 2) == -1
      case _ =>
        false

  def skipNil(): Unit = pos += 1

  def readBoolean(): Boolean =
    val v = peek == 0xc3
    pos += 1
    v

  def readLong(): Long =
    val b = peek
    if b <= 0x7f then
      pos += 1
      b.toLong
    else if b >= 0xe0 then
      pos += 1
      (b - 256).toLong
    else
      val v =
        b match
          case 0xcc =>
            need(2)
            u8(pos + 1).toLong
          case 0xcd =>
            need(3)
            u16(pos + 1).toLong
          case 0xce =>
            need(5)
            i32(pos + 1).toLong & 0xffffffffL
          case 0xcf | 0xd3 =>
            need(9)
            i64(pos + 1)
          case 0xd0 =>
            need(2)
            buf(pos + 1).toLong
          case 0xd1 =>
            need(3)
            u16(pos + 1).toShort.toLong
          case 0xd2 =>
            need(5)
            i32(pos + 1).toLong
          case _ =>
            throw ParquetException(s"not an integer: 0x${b.toHexString}")
      pos += intSize(b)
      v

  private def intSize(b: Int): Int =
    b match
      case 0xcc | 0xd0 =>
        2
      case 0xcd | 0xd1 =>
        3
      case 0xce | 0xd2 =>
        5
      case _ =>
        9

  def readDouble(): Double =
    peek match
      case 0xca =>
        need(5)
        val v = java.lang.Float.intBitsToFloat(i32(pos + 1)).toDouble
        pos += 5
        v
      case _ =>
        need(9)
        val v = java.lang.Double.longBitsToDouble(i64(pos + 1))
        pos += 9
        v

  /** Reads a timestamp extension as (epoch second, nano adjustment) into `out` */
  def readTimestamp(out: Array[Long]): Unit =
    val (dataPos, len) =
      peek match
        case 0xd6 =>
          (pos + 2, 4)
        case 0xd7 =>
          (pos + 2, 8)
        case _ =>
          need(3)
          (pos + 3, u8(pos + 1))
    pos = dataPos
    need(len)
    len match
      case 4 =>
        out(0) = i32(dataPos).toLong & 0xffffffffL
        out(1) = 0
      case 8 =>
        val v = i64(dataPos)
        out(0) = v & 0x3ffffffffL
        out(1) = v >>> 34
      case 12 =>
        out(1) = i32(dataPos).toLong & 0xffffffffL
        out(0) = i64(dataPos + 4)
      case _ =>
        throw ParquetException(s"Invalid timestamp extension of ${len} bytes")
    pos += len

  /** Reads a string or binary header; the payload is then at [[pos]] */
  def readRawHeader(): Int =
    val b = peek
    val n =
      if b >= 0xa0 && b <= 0xbf then
        pos += 1
        b & 0x1f
      else
        b match
          case 0xd9 | 0xc4 =>
            need(2)
            val n = u8(pos + 1)
            pos += 2
            n
          case 0xda | 0xc5 =>
            need(3)
            val n = u16(pos + 1)
            pos += 3
            n
          case 0xdb | 0xc6 =>
            need(5)
            val n = i32(pos + 1)
            pos += 5
            n
          case _ =>
            throw ParquetException(s"not a string or binary: 0x${b.toHexString}")
    need(n)
    n

  def readMapHeader(): Int =
    val b = peek
    if b >= 0x80 && b <= 0x8f then
      pos += 1
      b & 0x0f
    else
      containerHeader(b)

  def readArrayHeader(): Int =
    val b = peek
    if b >= 0x90 && b <= 0x9f then
      pos += 1
      b & 0x0f
    else
      containerHeader(b)

  private def containerHeader(b: Int): Int =
    if b == 0xdc || b == 0xde then
      need(3)
      val n = u16(pos + 1)
      pos += 3
      n
    else
      need(5)
      val n = i32(pos + 1)
      pos += 5
      if n < 0 then
        throw ParquetException("A MessagePack container is too large")
      n

  /**
    * Reads a string key and tells whether it equals `name` (UTF-8). A key that is not a string is
    * skipped and does not match.
    */
  def keyEquals(name: Array[Byte]): Boolean =
    if !isString then
      skip()
      false
    else
      val n     = readRawHeader()
      val start = pos
      pos += n
      // A loop rather than the ranged Arrays.equals, which Scala.js and Native may lack
      var same = n == name.length
      var i    = 0
      while same && i < n do
        same = buf(start + i) == name(i)
        i += 1
      same

  /** Reads a string key as a String, or null for a key that is not a string */
  def readKey(): String =
    if !isString then
      skip()
      null
    else
      val n = readRawHeader()
      val s = String(buf, pos, n, "UTF-8")
      pos += n
      s

  /** Skips one value, including everything a map or an array holds */
  def skip(): Unit =
    var remaining = 1
    while remaining > 0 do
      remaining -= 1
      val b = peek
      if b <= 0x7f || b >= 0xe0 || b == 0xc0 || b == 0xc2 || b == 0xc3 then
        pos += 1
      else if b >= 0x80 && b <= 0x8f then
        remaining += 2 * readMapHeader()
      else if b >= 0x90 && b <= 0x9f then
        remaining += readArrayHeader()
      else if isString || isBinary then
        // Not `pos += readRawHeader()`: that would read pos before the header advances it
        val n = readRawHeader()
        pos += n
      else
        b match
          case 0xdc | 0xdd =>
            remaining += readArrayHeader()
          case 0xde | 0xdf =>
            remaining += 2 * readMapHeader()
          case 0xca =>
            pos += 5
          case 0xcb =>
            pos += 9
          case 0xcc | 0xcd | 0xce | 0xcf | 0xd0 | 0xd1 | 0xd2 | 0xd3 =>
            pos += intSize(b)
          case 0xd4 =>
            pos += 3
          case 0xd5 =>
            pos += 4
          case 0xd6 =>
            pos += 6
          case 0xd7 =>
            pos += 10
          case 0xd8 =>
            pos += 18
          case 0xc7 =>
            need(2)
            pos += 3 + u8(pos + 1)
          case 0xc8 =>
            need(3)
            pos += 4 + u16(pos + 1)
          case 0xc9 =>
            need(5)
            pos += 6 + i32(pos + 1)
          case _ =>
            throw ParquetException(s"Invalid MessagePack format byte 0x${b.toHexString}")
      if pos > end || pos < 0 then
        throw ParquetException("Truncated MessagePack data")

  end skip

  /** Reads the next value as a `Value`, for the less common conversions */
  def readValue(): Value =
    val start = pos
    skip()
    // A copy: the unpacker that takes an offset reads strings from the start of the array
    MessagePack.newUnpacker(java.util.Arrays.copyOfRange(buf, start, pos)).unpackValue

  /** The type of the next value, for error messages */
  def valueType: ValueType =
    val b = peek
    if b == 0xc0 then
      ValueType.NIL
    else if isBoolean then
      ValueType.BOOLEAN
    else if b <= 0x7f || b >= 0xe0 || (b >= 0xcc && b <= 0xd3) then
      ValueType.INTEGER
    else if isFloat then
      ValueType.FLOAT
    else if isString then
      ValueType.STRING
    else if isBinary then
      ValueType.BINARY
    else if isArray then
      ValueType.ARRAY
    else if isMap then
      ValueType.MAP
    else
      ValueType.EXTENSION

end MsgReader
