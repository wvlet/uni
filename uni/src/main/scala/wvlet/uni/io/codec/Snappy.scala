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
    */
  def decompressInto(
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

    def fail(msg: String): Nothing = throw CodecException.corrupt(s"Snappy: ${msg}")

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

    // The last few tags, reading each byte with a bounds check
    while ip < end do
      val tag = byteAt(ip)
      ip += 1
      (tag & 3) match
        case 0 =>
          var len = tag >>> 2
          if len >= 60 then
            val n = len - 59
            len = 0
            var i = 0
            while i < n do
              len |= byteAt(ip + i) << (8 * i)
              i += 1
            ip += n
          len += 1
          if len <= 0 || len > end - ip || len > expectedSize - op then
            fail("literal runs past the end of the buffer")
          WideCopy.literal(src, ip, out, op, len, expectedSize)
          ip += len
          op += len
        case kind =>
          var len    = 0
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
          if len > expectedSize - op then
            fail("copy runs past the end of the output")
          WideCopy.matchCopy(out, op, offset, len, expectedSize)
          op += len
      end match
    end while
    if op != expectedSize then
      fail(s"decoded ${op} bytes, expected ${expectedSize}")

  end decompressInto

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
