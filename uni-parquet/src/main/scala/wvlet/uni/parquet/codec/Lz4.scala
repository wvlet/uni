package wvlet.uni.parquet.codec

import wvlet.uni.parquet.ParquetException

/** Decoder for the LZ4 block format, which Parquet's LZ4_RAW codec uses */
private[parquet] object Lz4:

  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val out = new Array[Byte](expectedSize)
    val n   = decompressInto(src, offset, length, out, 0, expectedSize)
    if n != expectedSize then
      throw ParquetException.corrupt(s"LZ4: decoded ${n} bytes, expected ${expectedSize}")
    out

  /** Decodes one block and returns the number of bytes written */
  def decompressInto(
      src: Array[Byte],
      offset: Int,
      length: Int,
      out: Array[Byte],
      outOffset: Int,
      outLimit: Int
  ): Int =
    def fail(msg: String): Nothing = throw ParquetException.corrupt(s"LZ4: ${msg}")
    val end                        = offset + length
    var ip                         = offset
    var op                         = outOffset
    val oe                         = outOffset + outLimit

    def byteAt(i: Int): Int =
      if i >= end then
        fail("input ends early")
      src(i) & 0xff

    def readLength(base: Int): Int =
      var len = base
      if base == 15 then
        var b = 255
        while b == 255 do
          b = byteAt(ip)
          ip += 1
          len += b
          if len < 0 then
            fail("length overflows")
      len

    var done = length == 0
    while !done do
      val token = byteAt(ip)
      ip += 1
      val litLen = readLength(token >>> 4)
      if litLen > end - ip || litLen > oe - op then
        fail("literal runs past the end of the buffer")
      System.arraycopy(src, ip, out, op, litLen)
      ip += litLen
      op += litLen
      if ip == end then
        // The last sequence has literals only
        done = true
      else
        val matchOffset = byteAt(ip) | (byteAt(ip + 1) << 8)
        ip += 2
        if matchOffset == 0 || matchOffset > op - outOffset then
          fail(s"match offset ${matchOffset} is out of range")
        val matchLen = readLength(token & 0x0f) + 4
        if matchLen > oe - op then
          fail("match runs past the end of the output")
        Lz77.copyMatch(out, op, matchOffset, matchLen)
        op += matchLen
    end while
    op - outOffset

  end decompressInto

end Lz4
