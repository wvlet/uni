package wvlet.uni.io.codec

/** Decoder for the raw Snappy format (not the framing format), as Parquet stores it */
private[uni] object Snappy:

  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val out = new Array[Byte](expectedSize)
    decompressInto(src, offset, length, out, expectedSize)
    out

  /**
    * Decodes into `out(0)` to `out(expectedSize - 1)`, so a caller can reuse one buffer for many
    * pages. `out` may be longer than `expectedSize`; bytes past it are left as they were.
    *
    * On the JVM, when the application has snappy-java on its classpath and its native library
    * loads, the native decoder does the work: it is about 1.5x faster than [[decodeInto]], which C2
    * can't match on one stream (adr/2026-11-24-snappy-jvm-window-loop.md).
    */
  def decompressInto(
      src: Array[Byte],
      offset: Int,
      length: Int,
      out: Array[Byte],
      expectedSize: Int
  ): Unit =
    if SnappyJava.available then
      checkArgs(src, offset, length, out, expectedSize)
      // The length check before the call keeps the native decoder inside out(0 until expectedSize)
      val claimed = preambleLength(src, offset, offset + length)
      if claimed != expectedSize then
        fail(s"uncompressed length ${claimed} does not match the expected ${expectedSize}")
      val n =
        try
          SnappyJava.decompressInto(src, offset, length, out)
        catch
          case e: java.io.IOException =>
            throw CodecException.corrupt(s"Snappy: ${e.getMessage}")
      if n != expectedSize then
        fail(s"decoded ${n} bytes, expected ${expectedSize}")
    else
      decodeInto(src, offset, length, out, expectedSize)

  /** [[decompress]] with the pure-Scala decoder on every platform */
  def decode(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val out = new Array[Byte](expectedSize)
    decodeInto(src, offset, length, out, expectedSize)
    out

  private def checkArgs(
      src: Array[Byte],
      offset: Int,
      length: Int,
      out: Array[Byte],
      expectedSize: Int
  ): Unit =
    if offset < 0 || length < 0 || length > src.length - offset then
      throw IndexOutOfBoundsException(
        s"Snappy: input range ${offset} + ${length} is outside an array of ${src.length} bytes"
      )
    if expectedSize < 0 || out.length < expectedSize then
      throw IllegalArgumentException(
        s"Snappy: the output buffer holds ${out.length} bytes, fewer than ${expectedSize}"
      )

  /** The uncompressed length that the stream's varint preamble states */
  private def preambleLength(src: Array[Byte], offset: Int, end: Int): Long =
    var ip     = offset
    var outLen = 0L
    var shift  = 0
    var b      = 0
    while
      if shift > 28 then
        fail("length varint is too long")
      if ip >= end then
        fail("input ends early")
      b = src(ip) & 0xff
      ip += 1
      outLen |= (b & 0x7fL) << shift
      shift += 7
      (b & 0x80) != 0
    do
      (
    )
    outLen

  /** [[decompressInto]] with the pure-Scala decoder on every platform */
  def decodeInto(
      src: Array[Byte],
      offset: Int,
      length: Int,
      out: Array[Byte],
      expectedSize: Int
  ): Unit =
    if out.length < expectedSize then
      throw IllegalArgumentException(
        s"Snappy: the output buffer holds ${out.length} bytes, fewer than ${expectedSize}"
      )
    val end = offset + length
    var ip  = offset

    def byteAt(i: Int): Int =
      if i >= end then
        fail("input ends early")
      src(i) & 0xff

    // Preamble: the uncompressed length as a varint
    var outLen = 0L
    var shift  = 0
    var b      = 0
    while
      if shift > 28 then
        fail("length varint is too long")
      b = byteAt(ip)
      ip += 1
      outLen |= (b & 0x7fL) << shift
      shift += 7
      (b & 0x80) != 0
    do
      (
    )
    if outLen != expectedSize then
      fail(s"uncompressed length ${outLen} does not match the expected ${expectedSize}")

    var op = 0

    if SnappyLoop.enabled then
      // The JVM's loop decodes the common tags; each tag it stops at is decoded here, with checks
      while ip < end do
        val st = SnappyLoop.run(src, ip, end, out, op, expectedSize)
        ip = (st >>> 32).toInt
        op = st.toInt
        if ip < end then
          val next = checkedTag(src, ip, end, out, op, expectedSize)
          ip = (next >>> 32).toInt
          op = next.toInt
    else
      // Fast loop while a tag and its extra bytes fit in the input, so they need no bounds check
      while ip <= end - 5 do
        val tag  = src(ip) & 0xff
        val kind = tag & 3
        if kind == 0 then
          var len = (tag >>> 2) + 1
          ip += 1
          if len > 60 then
            val n = len - 60
            len = (Zstd.le32(src, ip) & ExtraMask(n)) + 1
            ip += n
          if len <= 0 || len > end - ip || len > expectedSize - op then
            fail("literal runs past the end of the buffer")
          WideCopy.literal(src, ip, out, op, len, expectedSize)
          ip += len
          op += len
        else
          var len    = 0
          var offset = 0
          if kind == 1 then
            len = 4 + ((tag >>> 2) & 7)
            offset = ((tag >>> 5) << 8) | (src(ip + 1) & 0xff)
            ip += 2
          else if kind == 2 then
            len = (tag >>> 2) + 1
            offset = (src(ip + 1) & 0xff) | ((src(ip + 2) & 0xff) << 8)
            ip += 3
          else
            len = (tag >>> 2) + 1
            offset = Zstd.le32(src, ip + 1)
            ip += 5
          if offset <= 0 || offset > op then
            fail(s"copy offset ${offset} is out of range")
          if len > expectedSize - op then
            fail("copy runs past the end of the output")
          WideCopy.matchCopy(out, op, offset, len, expectedSize)
          op += len
        end if
      end while
    end if

    // The last few tags, reading each byte with a bounds check
    while ip < end do
      val next = checkedTag(src, ip, end, out, op, expectedSize)
      ip = (next >>> 32).toInt
      op = next.toInt
    if op != expectedSize then
      fail(s"decoded ${op} bytes, expected ${expectedSize}")

  end decodeInto

  private def fail(msg: String): Nothing = throw CodecException.corrupt(s"Snappy: ${msg}")

  /**
    * Decodes the tag at `src(start)`, checking every input byte against `end` and every output byte
    * against `limit`. Returns the input position after the tag in the high 32 bits and the output
    * position in the low 32.
    */
  private def checkedTag(
      src: Array[Byte],
      start: Int,
      end: Int,
      out: Array[Byte],
      op: Int,
      limit: Int
  ): Long =
    def byteAt(i: Int): Int =
      if i >= end then
        fail("input ends early")
      src(i) & 0xff

    val tag = byteAt(start)
    var ip  = start + 1
    var len = 0
    (tag & 3) match
      case 0 =>
        len = tag >>> 2
        if len >= 60 then
          val n = len - 59
          len = 0
          var i = 0
          while i < n do
            len |= byteAt(ip + i) << (8 * i)
            i += 1
          ip += n
        len += 1
        if len <= 0 || len > end - ip || len > limit - op then
          fail("literal runs past the end of the buffer")
        WideCopy.literal(src, ip, out, op, len, limit)
        ip += len
      case kind =>
        var offset = 0
        if kind == 1 then
          len = 4 + ((tag >>> 2) & 7)
          offset = ((tag >>> 5) << 8) | byteAt(ip)
          ip += 1
        else if kind == 2 then
          len = (tag >>> 2) + 1
          offset = byteAt(ip) | (byteAt(ip + 1) << 8)
          ip += 2
        else
          len = (tag >>> 2) + 1
          offset =
            byteAt(ip) | (byteAt(ip + 1) << 8) | (byteAt(ip + 2) << 16) | (byteAt(ip + 3) << 24)
          ip += 4
        if offset <= 0 || offset > op then
          fail(s"copy offset ${offset} is out of range")
        if len > limit - op then
          fail("copy runs past the end of the output")
        WideCopy.matchCopy(out, op, offset, len, limit)
    end match
    (ip.toLong << 32) | (op + len)

  end checkedTag

  private val ExtraMask: Array[Int] = Array(0, 0xff, 0xffff, 0xffffff, -1)

end Snappy

private[uni] object Lz77:
  /** Copies `len` bytes from `op - offset`. The ranges may overlap, which repeats a pattern. */
  inline def copyMatch(out: Array[Byte], op: Int, offset: Int, len: Int): Unit =
    val from = op - offset
    if offset >= len then
      System.arraycopy(out, from, out, op, len)
    else
      var i = 0
      while i < len do
        out(op + i) = out(from + i)
        i += 1
