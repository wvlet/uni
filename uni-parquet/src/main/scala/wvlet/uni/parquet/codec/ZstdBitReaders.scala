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

  /** Bits not yet read; negative after reading past the start (the missing bits are zeros) */
  var remaining: Long = (end - start).toLong * 8 - 8 + Zstd.highBit(last)

  private val length = end - start

  // A cached 64-bit window of the stream: bits [windowLo, windowLo + 64). Reads go backward, so a
  // window is loaded to end at the requested bits and serves the next few reads below them. Before
  // the first load, no position falls inside it (and windowLo + 64 does not overflow).
  private var windowLo: Long = Long.MaxValue - 64
  private var window: Long   = 0L

  /** Bits [pos, pos + n) of the stream, with bit 0 the lowest bit of the first byte; n <= 32 */
  private def bitsAt(pos: Long, n: Int): Long =
    if pos >= windowLo && pos + n <= windowLo + 64 then
      (window >>> (pos - windowLo).toInt) & ((1L << n) - 1)
    else
      load(pos, n)

  private def load(pos: Long, n: Int): Long =
    if pos >= 0 && length >= 8 then
      // The 8 bytes that end at the byte holding the read's last bit, kept inside the stream
      val b = math.min(((pos + n + 7) >>> 3).toInt - 8, length - 8)
      if b >= 0 && b.toLong * 8 <= pos then
        window = le64(start + b)
        windowLo = b.toLong * 8
        (window >>> (pos - windowLo).toInt) & ((1L << n) - 1)
      else
        bitsAtSlow(pos, n)
    else
      bitsAtSlow(pos, n)

  private inline def le64(i: Int): Long =
    val lo = Zstd.le32(buf, i) & 0xffffffffL
    val hi = Zstd.le32(buf, i + 4).toLong
    lo | (hi << 32)

  /** Bits near the stream's ends, or below position 0 (which read as zeros) */
  private def bitsAtSlow(pos: Long, n: Int): Long =
    if n == 0 then
      0L
    else
      var v   = 0L
      var got = 0
      var p   = pos
      // Bits below position 0 read as zero
      if p < 0 then
        got = math.min(n.toLong, -p).toInt
        p += got
      while got < n do
        val byteIdx = (p >>> 3).toInt
        val inByte  = (p & 7).toInt
        val take    = math.min(8 - inByte, n - got)
        val b       = (buf(start + byteIdx) & 0xff) >>> inByte
        v |= (b & ((1 << take) - 1)).toLong << got
        got += take
        p += take
      v

  def read(n: Int): Int =
    val v = bitsAt(remaining - n, n)
    remaining -= n
    v.toInt

  def peek(n: Int): Int = bitsAt(remaining - n, n).toInt

  def skip(n: Int): Unit = remaining -= n

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
