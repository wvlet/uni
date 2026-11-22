package wvlet.uni.io.codec

/**
  * A DEFLATE (RFC 1951) and gzip (RFC 1952) decoder in Scala. Browsers have no synchronous gzip
  * API, so gzip pages use this there; other platforms use the platform's zlib through
  * wvlet.uni.io.Gzip.
  */
private[uni] object Inflate:
  private def fail(msg: String): Nothing = throw CodecException.corrupt(s"gzip: ${msg}")

  private val LengthBase = Array(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131,
    163, 195, 227, 258
  )

  private val LengthExtra = Array(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
  )

  private val DistBase = Array(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049,
    3073, 4097, 6145, 8193, 12289, 16385, 24577
  )

  private val DistExtra = Array(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
  )

  private val CodeLengthOrder = Array(
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
  )

  /** Canonical Huffman code: symbol counts per code length, and symbols in code order */
  private final class Huffman(val count: Array[Int], val symbol: Array[Int])

  private def huffman(lengths: Array[Int], offset: Int, n: Int): Huffman =
    val count = new Array[Int](16)
    var i     = 0
    while i < n do
      count(lengths(offset + i)) += 1
      i += 1
    // Check the code is not over-subscribed
    var left = 1
    var len  = 1
    while len <= 15 do
      left <<= 1
      left -= count(len)
      if left < 0 then
        fail("over-subscribed Huffman code")
      len += 1
    val offs = new Array[Int](16)
    len = 1
    while len < 15 do
      offs(len + 1) = offs(len) + count(len)
      len += 1
    val symbol = new Array[Int](n)
    i = 0
    while i < n do
      val l = lengths(offset + i)
      if l != 0 then
        symbol(offs(l)) = i
        offs(l) += 1
      i += 1
    count(0) = 0
    Huffman(count, symbol)

  end huffman

  private lazy val fixedLitLen: Huffman =
    val l = new Array[Int](288)
    java.util.Arrays.fill(l, 0, 144, 8)
    java.util.Arrays.fill(l, 144, 256, 9)
    java.util.Arrays.fill(l, 256, 280, 7)
    java.util.Arrays.fill(l, 280, 288, 8)
    huffman(l, 0, 288)

  private lazy val fixedDist: Huffman = huffman(Array.fill(30)(5), 0, 30)

  private final class State(src: Array[Byte], var pos: Int, end: Int, expected: Int):
    var out: Array[Byte] = new Array[Byte](math.max(expected, 16))
    var outPos: Int      = 0
    private var bitBuf   = 0
    private var bitCnt   = 0

    def bits(n: Int): Int =
      while bitCnt < n do
        if pos >= end then
          fail("input ends early")
        bitBuf |= (src(pos) & 0xff) << bitCnt
        pos += 1
        bitCnt += 8
      val v = bitBuf & ((1 << n) - 1)
      bitBuf >>>= n
      bitCnt -= n
      v

    def alignToByte(): Unit =
      bitBuf = 0
      bitCnt = 0

    def ensureOut(n: Int): Unit =
      if outPos + n > out.length then
        if outPos.toLong + n > Int.MaxValue - 8 then
          fail("output is too large")
        out = java.util.Arrays.copyOf(out, math.max(out.length * 2, outPos + n))

    def decode(h: Huffman): Int =
      var code  = 0
      var first = 0
      var index = 0
      var len   = 1
      while len <= 15 do
        code |= bits(1)
        val c = h.count(len)
        if code - c < first then
          return h.symbol(index + (code - first))
        index += c
        first += c
        first <<= 1
        code <<= 1
        len += 1
      fail("invalid Huffman code")

  end State

  private def inflateBlocks(s: State): Unit =
    var last = false
    while !last do
      last = s.bits(1) == 1
      s.bits(2) match
        case 0 =>
          s.alignToByte()
          val len  = s.bits(16)
          val nlen = s.bits(16)
          if (len ^ 0xffff) != nlen then
            fail("stored block length does not match its complement")
          s.ensureOut(len)
          var i = 0
          while i < len do
            s.out(s.outPos) = s.bits(8).toByte
            s.outPos += 1
            i += 1
        case 1 =>
          codes(s, fixedLitLen, fixedDist)
        case 2 =>
          val nlen  = s.bits(5) + 257
          val ndist = s.bits(5) + 1
          val ncode = s.bits(4) + 4
          if nlen > 286 || ndist > 30 then
            fail("too many length or distance codes")
          val lengths = new Array[Int](320)
          var i       = 0
          while i < ncode do
            lengths(CodeLengthOrder(i)) = s.bits(3)
            i += 1
          val lencode = huffman(lengths, 0, 19)
          i = 0
          while i < nlen + ndist do
            val sym = s.decode(lencode)
            if sym < 16 then
              lengths(i) = sym
              i += 1
            else
              var len = 0
              val rep =
                if sym == 16 then
                  if i == 0 then
                    fail("repeat with no previous length")
                  len = lengths(i - 1)
                  3 + s.bits(2)
                else if sym == 17 then
                  3 + s.bits(3)
                else
                  11 + s.bits(7)
              if i + rep > nlen + ndist then
                fail("too many code lengths")
              var k = 0
              while k < rep do
                lengths(i) = len
                i += 1
                k += 1
          if lengths(256) == 0 then
            fail("no end-of-block code")
          val litLen = huffman(lengths, 0, nlen)
          val dist   = huffman(lengths, nlen, ndist)
          codes(s, litLen, dist)
        case _ =>
          fail("invalid block type")
      end match
    end while

  end inflateBlocks

  private def codes(s: State, litLen: Huffman, dist: Huffman): Unit =
    var done = false
    while !done do
      val sym = s.decode(litLen)
      if sym < 256 then
        s.ensureOut(1)
        s.out(s.outPos) = sym.toByte
        s.outPos += 1
      else if sym == 256 then
        done = true
      else
        val li = sym - 257
        if li >= 29 then
          fail("invalid length code")
        val len = LengthBase(li) + s.bits(LengthExtra(li))
        val di  = s.decode(dist)
        if di >= 30 then
          fail("invalid distance code")
        val d = DistBase(di) + s.bits(DistExtra(di))
        if d > s.outPos then
          fail("distance is too far back")
        s.ensureOut(len)
        Lz77.copyMatch(s.out, s.outPos, d, len)
        s.outPos += len

  /** Decodes gzip data, including several members back to back */
  def gunzip(src: Array[Byte], expectedSize: Int): Array[Byte] =
    val end    = src.length
    var pos    = 0
    var out    = new Array[Byte](math.max(expectedSize, 16))
    var outLen = 0
    while pos < end do
      if end - pos < 18 then
        fail("member is truncated")
      if (src(pos) & 0xff) != 0x1f || (src(pos + 1) & 0xff) != 0x8b then
        fail("not gzip data")
      if src(pos + 2) != 8 then
        fail("unknown compression method")
      val flags = src(pos + 3) & 0xff
      var p     = pos + 10
      if (flags & 4) != 0 then
        if p + 2 > end then
          fail("extra field is truncated")
        p += 2 + ((src(p) & 0xff) | ((src(p + 1) & 0xff) << 8))
      if (flags & 8) != 0 then
        while p < end && src(p) != 0 do
          p += 1
        p += 1
      if (flags & 16) != 0 then
        while p < end && src(p) != 0 do
          p += 1
        p += 1
      if (flags & 2) != 0 then
        p += 2
      if p > end then
        fail("header is truncated")
      val s = State(src, p, end, math.max(expectedSize - outLen, 16))
      inflateBlocks(s)
      if s.outPos > out.length - outLen then
        out = java.util.Arrays.copyOf(out, outLen + s.outPos)
      System.arraycopy(s.out, 0, out, outLen, s.outPos)
      outLen += s.outPos
      // Skip the CRC32 and size trailer
      pos = s.pos + 8
      if pos > end then
        fail("trailer is truncated")
    end while
    java.util.Arrays.copyOf(out, outLen)

  end gunzip

  /** Decodes raw DEFLATE data with no gzip or zlib wrapper, as Avro's deflate codec stores it */
  def inflate(src: Array[Byte], offset: Int, length: Int, sizeHint: Int): Array[Byte] =
    val s = State(src, offset, offset + length, sizeHint)
    inflateBlocks(s)
    java.util.Arrays.copyOf(s.out, s.outPos)

end Inflate
