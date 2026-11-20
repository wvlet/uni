package wvlet.uni.parquet.codec

/**
  * Reads a bitstream backward, from its last bit toward its first, as ZSTD's FSE and Huffman
  * streams require. The last byte holds a 1 marking where the data starts.
  */
private[codec] final class BackwardBitReader(buf: Array[Byte], start: Int, end: Int):
  if end <= start then
    Zstd.fail("bitstream is empty")

  private val last = buf(end - 1) & 0xff
  if last == 0 then
    Zstd.fail("bitstream has no end marker")

  // As in zstd's BIT_DStream: `container` holds the 8 stream bytes at [ptr, ptr + 8) as a
  // little-endian word, and reads take bits from its top down; `consumed` counts the bits already
  // taken. `ptr` may go below `start`: the bytes there read as zeros, as the format requires.
  // Not private: ZstdSequences keeps these in locals in its hot loop
  private[codec] var ptr: Int        = 0
  private[codec] var container: Long = 0L
  private[codec] var consumed: Int   = 0

  locally {
    val length = end - start
    if length >= 8 then
      ptr = end - 8
      container = le64(ptr)
    else
      // Place the short stream at the top of the word; the missing low bytes are zeros
      var v = 0L
      var i = 0
      while i < length do
        v |= (buf(start + i) & 0xffL) << (8 * i)
        i += 1
      ptr = end - 8
      container = v << (8 * (8 - length))
    // Skip the zero padding above the end marker, and the marker itself
    consumed = 8 - Zstd.highBit(last)
  }

  /** Bits not yet read; negative after reading past the start (the missing bits are zeros) */
  def remaining: Long = (ptr - start).toLong * 8 + 64 - consumed

  private[codec] inline def le64(i: Int): Long =
    val lo = Zstd.le32(buf, i) & 0xffffffffL
    val hi = Zstd.le32(buf, i + 4).toLong
    lo | (hi << 32)

  /** Moves the window down until `n` more bits fit in it */
  private[codec] def refill(n: Int): Unit =
    while consumed + n > 64 do
      val bytes = consumed >>> 3
      if ptr - bytes >= start then
        ptr -= bytes
        consumed &= 7
        container = le64(ptr)
      else if ptr > start then
        // Only part of a step is left before the start: load the first 8 bytes
        consumed -= (ptr - start) * 8
        ptr = start
        container = le64(ptr)
      else
        // Below the start: shift in zero bytes
        container =
          if bytes >= 8 then
            0L
          else
            container << (8 * bytes)
        ptr -= bytes
        consumed &= 7

  /** The next `n` bits (n <= 32) without consuming them */
  def peek(n: Int): Int =
    if consumed + n > 64 then
      refill(n)
    // The extra 1-bit shift makes n = 0 return 0
    ((container << consumed) >>> 1 >>> (63 - n)).toInt

  def read(n: Int): Int =
    val v = peek(n)
    consumed += n
    v

  def skip(n: Int): Unit = consumed += n

  def isOverflowed: Boolean = remaining < 0

end BackwardBitReader

/** A forward bit reader for FSE table descriptions */
private[codec] final class ForwardBitReader(buf: Array[Byte], start: Int, end: Int):
  var bitPos: Long = 0L

  def peek(n: Int): Int =
    var v = 0
    var i = 0
    while i < n do
      val p       = bitPos + i
      val byteIdx = start + (p >>> 3).toInt
      if byteIdx < end then
        v |= ((buf(byteIdx) >>> (p & 7).toInt) & 1) << i
      i += 1
    v

  def skip(n: Int): Unit = bitPos += n

  def read(n: Int): Int =
    val v = peek(n)
    skip(n)
    v

  def bytesUsed: Int = ((bitPos + 7) >>> 3).toInt
