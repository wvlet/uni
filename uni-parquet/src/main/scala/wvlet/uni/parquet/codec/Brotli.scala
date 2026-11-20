package wvlet.uni.parquet.codec

import wvlet.uni.parquet.ParquetException

/**
  * A Brotli decoder (RFC 7932) in Scala, for Parquet's BROTLI codec. It decodes a whole stream into
  * an output buffer of known size.
  */
private[parquet] object Brotli:
  private[codec] def fail(msg: String): Nothing = throw ParquetException.corrupt(s"Brotli: ${msg}")

  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val d = BrotliDecoder(src, offset, offset + length, expectedSize)
    d.run()
    if d.outPos != expectedSize then
      fail(s"decoded ${d.outPos} bytes, expected ${expectedSize}")
    d.out

  // Block count codes (RFC 7932, section 6)
  private[codec] val BlockLengthBase = Array(
    1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497, 753, 1265,
    2289, 4337, 8433, 16625
  )

  private[codec] val BlockLengthBits = Array(
    2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24
  )

  // Insert and copy length codes (RFC 7932, section 5)
  private[codec] val InsertBase = Array(
    0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210,
    22594
  )

  private[codec] val InsertBits = Array(
    0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24
  )

  private[codec] val CopyBase = Array(
    2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118
  )

  private[codec] val CopyBits = Array(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24
  )

  // Insert and copy code bases for each cell of 64 commands
  private[codec] val CellInsert = Array(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)
  private[codec] val CellCopy   = Array(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)

  private[codec] val CodeLengthOrder = Array(
    1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15
  )

  // The fixed code for code length code lengths, indexed by the next 4 bits
  private[codec] val CodeLengthPrefixLength = Array(2, 2, 2, 3, 2, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 4)
  private[codec] val CodeLengthPrefixValue  = Array(0, 4, 3, 2, 0, 4, 3, 1, 0, 4, 3, 2, 0, 4, 3, 5)

  // Static dictionary layout: bits of the word index per word length
  private[codec] val DictionaryBits = Array(
    0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5
  )

  private[codec] lazy val DictionaryOffsets: Array[Int] =
    val offs = new Array[Int](DictionaryBits.length + 1)
    var i    = 0
    while i < DictionaryBits.length do
      offs(i + 1) =
        offs(i) + (
          if DictionaryBits(i) > 0 then
            i << DictionaryBits(i)
          else
            0
        )
      i += 1
    offs

  private[codec] lazy val transformPrefixes: Array[Array[Byte]] = BrotliTables
    .prefixes
    .map(s => s.map(_.toByte).toArray)

  private[codec] lazy val transformSuffixes: Array[Array[Byte]] = BrotliTables
    .suffixes
    .map(s => s.map(_.toByte).toArray)

end Brotli

/** A canonical prefix code. A code with one symbol reads no bits. */
private[codec] final class PrefixCode(
    val count: Array[Int],
    val symbol: Array[Int],
    val single: Int
)

private[codec] final class BrotliDecoder(src: Array[Byte], start: Int, end: Int, expected: Int):
  import Brotli.*

  val out: Array[Byte] = new Array[Byte](expected)
  var outPos: Int      = 0

  // ---- Bit reading, least significant bit first ----
  private var pos    = start
  private var bitBuf = 0L
  private var bitCnt = 0

  private def fill(): Unit =
    while bitCnt <= 56 && pos < end do
      bitBuf |= (src(pos) & 0xffL) << bitCnt
      pos += 1
      bitCnt += 8

  private def bits(n: Int): Int =
    if n == 0 then
      0
    else
      if bitCnt < n then
        fill()
        if bitCnt < n then
          fail("input ends early")
      val v = (bitBuf & ((1L << n) - 1)).toInt
      bitBuf >>>= n
      bitCnt -= n
      v

  private def bit(): Int = bits(1)

  /** Drops bits up to the next byte boundary; they must be zero */
  private def alignToByte(): Unit =
    val n = bitCnt & 7
    if n > 0 && bits(n) != 0 then
      fail("padding bits are not zero")

  private def readByte(): Int = bits(8)

  // ---- Prefix codes (RFC 7932, section 3) ----

  private def canonical(lengths: Array[Int], n: Int): PrefixCode =
    val count = new Array[Int](16)
    var i     = 0
    while i < n do
      count(lengths(i)) += 1
      i += 1
    val offs = new Array[Int](16)
    var len  = 1
    while len < 15 do
      offs(len + 1) = offs(len) + count(len)
      len += 1
    val symbol = new Array[Int](n)
    i = 0
    while i < n do
      val l = lengths(i)
      if l != 0 then
        symbol(offs(l)) = i
        offs(l) += 1
      i += 1
    count(0) = 0
    PrefixCode(count, symbol, -1)

  private def decode(h: PrefixCode): Int =
    if h.single >= 0 then
      h.single
    else
      var code  = 0
      var first = 0
      var index = 0
      var len   = 1
      while len <= 15 do
        code |= bit()
        val c = h.count(len)
        if code - c < first then
          return h.symbol(index + (code - first))
        index += c
        first += c
        first <<= 1
        code <<= 1
        len += 1
      fail("invalid prefix code")

  private def readPrefixCode(alphabetSize: Int): PrefixCode =
    val hskip = bits(2)
    if hskip == 1 then
      // Simple prefix code
      val nsym       = bits(2) + 1
      val symbolBits = 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)
      val syms       = Array.fill(nsym)(bits(symbolBits))
      if syms.exists(_ >= alphabetSize) then
        fail("simple prefix code symbol is out of range")
      if syms.distinct.length != nsym then
        fail("simple prefix code repeats a symbol")
      if nsym == 1 then
        PrefixCode(Array.empty, Array.empty, syms(0))
      else
        val lengths = new Array[Int](alphabetSize)
        nsym match
          case 2 =>
            lengths(syms(0)) = 1
            lengths(syms(1)) = 1
          case 3 =>
            lengths(syms(0)) = 1
            lengths(syms(1)) = 2
            lengths(syms(2)) = 2
          case _ =>
            if bit() == 0 then
              syms.foreach(s => lengths(s) = 2)
            else
              lengths(syms(0)) = 1
              lengths(syms(1)) = 2
              lengths(syms(2)) = 3
              lengths(syms(3)) = 3
        canonical(lengths, alphabetSize)
    else
      // Complex prefix code: first the code length code lengths
      val clLengths = new Array[Int](18)
      var space     = 32
      var numCodes  = 0
      var i         = hskip
      while i < 18 && space > 0 do
        if bitCnt < 4 then
          fill()
        val peek = (bitBuf & 0xf).toInt
        val len  = CodeLengthPrefixLength(peek)
        if bitCnt < len then
          fail("input ends early")
        bitBuf >>>= len
        bitCnt -= len
        val v = CodeLengthPrefixValue(peek)
        clLengths(CodeLengthOrder(i)) = v
        if v != 0 then
          space -= 32 >> v
          numCodes += 1
        i += 1
      if numCodes != 1 && space != 0 then
        fail("code length code is incomplete")
      val clCode =
        if numCodes == 1 then
          PrefixCode(Array.empty, Array.empty, clLengths.indexWhere(_ != 0))
        else
          canonical(clLengths, 18)

      // Then the symbol code lengths
      val lengths   = new Array[Int](alphabetSize)
      var symbol    = 0
      var prevLen   = 8
      var repeat    = 0
      var repeatLen = 0
      var symSpace  = 32768
      while symbol < alphabetSize && symSpace > 0 do
        val p = decode(clCode)
        if p < 16 then
          lengths(symbol) = p
          symbol += 1
          repeat = 0
          if p != 0 then
            prevLen = p
            symSpace -= 32768 >> p
        else
          val extraBits =
            if p == 16 then
              2
            else
              3
          val newLen =
            if p == 16 then
              prevLen
            else
              0
          if repeatLen != newLen then
            repeat = 0
            repeatLen = newLen
          val oldRepeat = repeat
          if repeat > 0 then
            repeat -= 2
            repeat <<= extraBits
          repeat += bits(extraBits) + 3
          val delta = repeat - oldRepeat
          if symbol + delta > alphabetSize then
            fail("code lengths overflow the alphabet")
          var k = 0
          while k < delta do
            lengths(symbol) = repeatLen
            symbol += 1
            k += 1
          if repeatLen != 0 then
            symSpace -= delta << (15 - repeatLen)
        end if
      end while
      if symSpace != 0 then
        fail("prefix code is incomplete")
      val nonZero = lengths.count(_ != 0)
      if nonZero == 1 then
        PrefixCode(Array.empty, Array.empty, lengths.indexWhere(_ != 0))
      else
        canonical(lengths, alphabetSize)
    end if

  end readPrefixCode

  // ---- Header fields ----

  private def readVarLenUint8(): Int =
    if bit() == 0 then
      0
    else
      val n = bits(3)
      if n == 0 then
        1
      else
        (1 << n) + bits(n)

  private def readBlockLength(code: PrefixCode): Int =
    val c = decode(code)
    BlockLengthBase(c) + bits(BlockLengthBits(c))

  private def readContextMap(size: Int, numTrees: Int): Array[Int] =
    val map = new Array[Int](size)
    if numTrees >= 2 then
      val rleMax =
        if bit() == 1 then
          bits(4) + 1
        else
          0
      val code = readPrefixCode(numTrees + rleMax)
      var i    = 0
      while i < size do
        val c = decode(code)
        if c == 0 then
          map(i) = 0
          i += 1
        else if c <= rleMax then
          val run = (1 << c) + bits(c)
          if i + run > size then
            fail("context map run overflows")
          java.util.Arrays.fill(map, i, i + run, 0)
          i += run
        else
          map(i) = c - rleMax
          i += 1
      if bit() == 1 then
        // Inverse move-to-front
        val mtf = Array.tabulate(256)(identity)
        i = 0
        while i < size do
          val idx = map(i)
          val v   = mtf(idx)
          map(i) = v
          var k = idx
          while k > 0 do
            mtf(k) = mtf(k - 1)
            k -= 1
          mtf(0) = v
          i += 1
    end if
    map

  end readContextMap

  // ---- Block switching ----

  private final class BlockCategory:
    var numTypes: Int          = 1
    var typeCode: PrefixCode   = null
    var lengthCode: PrefixCode = null
    var current: Int           = 0
    var previous: Int          = 1
    var remaining: Int         = 1 << 28

  private def readCategory(c: BlockCategory): Unit =
    c.numTypes = readVarLenUint8() + 1
    c.current = 0
    c.previous = 1
    if c.numTypes >= 2 then
      c.typeCode = readPrefixCode(c.numTypes + 2)
      c.lengthCode = readPrefixCode(26)
      c.remaining = readBlockLength(c.lengthCode)
    else
      c.remaining = 1 << 28

  private def switchBlock(c: BlockCategory): Unit =
    val t    = decode(c.typeCode)
    val next =
      t match
        case 0 =>
          c.previous
        case 1 =>
          (c.current + 1) % c.numTypes
        case _ =>
          t - 2
    if next >= c.numTypes then
      fail("block type is out of range")
    c.previous = c.current
    c.current = next
    c.remaining = readBlockLength(c.lengthCode)

  // ---- Stream ----

  // The last four distances, most recent first
  private val dist = Array(4, 11, 15, 16)

  def run(): Unit =
    // Window size
    val windowBits =
      if bit() == 0 then
        16
      else
        val n = bits(3)
        if n != 0 then
          17 + n
        else
          val m = bits(3)
          if m == 1 then
            fail("large-window Brotli streams are not supported")
          if m != 0 then
            8 + m
          else
            17
    val windowSize = (1 << windowBits) - 16

    var last = false
    while !last do
      last = bit() == 1
      var empty = false
      if last && bit() == 1 then
        empty = true
      if !empty then
        val nibbles =
          bits(2) match
            case 3 =>
              0
            case n =>
              n + 4
        if nibbles == 0 then
          // Metadata block: skip it
          if bit() != 0 then
            fail("reserved bit is set")
          val skipBytes = bits(2)
          var skipLen   = 0
          var i         = 0
          while i < skipBytes do
            val b = bits(8)
            if i + 1 == skipBytes && skipBytes > 1 && b == 0 then
              fail("metadata length has a leading zero byte")
            skipLen |= b << (8 * i)
            i += 1
          if skipBytes > 0 then
            skipLen += 1
          alignToByte()
          i = 0
          while i < skipLen do
            readByte()
            i += 1
        else
          var mlen = 0
          var i    = 0
          while i < nibbles do
            val nb = bits(4)
            if i + 1 == nibbles && nibbles > 4 && nb == 0 then
              fail("meta-block length has a leading zero nibble")
            mlen |= nb << (4 * i)
            i += 1
          mlen += 1
          if mlen > out.length - outPos then
            fail("meta-block exceeds the expected output")
          val uncompressed = !last && bit() == 1
          if uncompressed then
            alignToByte()
            i = 0
            while i < mlen do
              out(outPos) = readByte().toByte
              outPos += 1
              i += 1
          else
            metaBlock(mlen, windowSize)
        end if
      end if
    end while
    // Remaining bits of the last byte must be zero
    alignToByte()

  end run

  private def metaBlock(mlen: Int, windowSize: Int): Unit =
    val lit = BlockCategory()
    val cmd = BlockCategory()
    val dis = BlockCategory()
    readCategory(lit)
    readCategory(cmd)
    readCategory(dis)
    val npostfix     = bits(2)
    val ndirect      = bits(4) << npostfix
    val postfixMask  = (1 << npostfix) - 1
    val contextModes = Array.fill(lit.numTypes)(bits(2))
    val nTreesL      = readVarLenUint8() + 1
    val litMap       = readContextMap(64 * lit.numTypes, nTreesL)
    val nTreesD      = readVarLenUint8() + 1
    val distMap      = readContextMap(4 * dis.numTypes, nTreesD)
    val litCodes     = Array.fill(nTreesL)(readPrefixCode(256))
    val cmdCodes     = Array.fill(cmd.numTypes)(readPrefixCode(704))
    val distAlphabet = 16 + ndirect + (48 << npostfix)
    val distCodes    = Array.fill(nTreesD)(readPrefixCode(distAlphabet))
    val ctxTable     = BrotliTables.contextLookup
    val metaEnd      = outPos + mlen

    while outPos < metaEnd do
      if cmd.remaining == 0 then
        switchBlock(cmd)
      cmd.remaining -= 1
      val code = decode(cmdCodes(cmd.current))
      val cell = code >>> 6
      if cell > 10 then
        fail("insert-and-copy code is out of range")
      val insCode = CellInsert(cell) + ((code >>> 3) & 7)
      val cpCode  = CellCopy(cell) + (code & 7)
      val insLen  = InsertBase(insCode) + bits(InsertBits(insCode))
      val copyLen = CopyBase(cpCode) + bits(CopyBits(cpCode))

      if insLen > metaEnd - outPos then
        fail("insert length exceeds the meta-block")
      var i = 0
      while i < insLen do
        if lit.remaining == 0 then
          switchBlock(lit)
        lit.remaining -= 1
        val p1 =
          if outPos > 0 then
            out(outPos - 1) & 0xff
          else
            0
        val p2 =
          if outPos > 1 then
            out(outPos - 2) & 0xff
          else
            0
        val mode = contextModes(lit.current)
        val ctx  = ctxTable(512 * mode + p1) | ctxTable(512 * mode + 256 + p2)
        val tree = litMap(64 * lit.current + ctx)
        if tree >= nTreesL then
          fail("literal context map is out of range")
        out(outPos) = decode(litCodes(tree)).toByte
        outPos += 1
        i += 1

      if outPos < metaEnd then
        // Distance
        var distance = 0
        var pushDist = true
        if cell < 2 then
          // Commands 0-127 reuse the last distance
          distance = dist(0)
          pushDist = false
        else
          if dis.remaining == 0 then
            switchBlock(dis)
          dis.remaining -= 1
          val ctx =
            if copyLen > 4 then
              3
            else
              copyLen - 2
          val tree = distMap(4 * dis.current + ctx)
          if tree >= nTreesD then
            fail("distance context map is out of range")
          val dcode = decode(distCodes(tree))
          if dcode < 16 then
            distance =
              dcode match
                case 0 =>
                  pushDist = false
                  dist(0)
                case 1 =>
                  dist(1)
                case 2 =>
                  dist(2)
                case 3 =>
                  dist(3)
                case 4 =>
                  dist(0) - 1
                case 5 =>
                  dist(0) + 1
                case 6 =>
                  dist(0) - 2
                case 7 =>
                  dist(0) + 2
                case 8 =>
                  dist(0) - 3
                case 9 =>
                  dist(0) + 3
                case 10 =>
                  dist(1) - 1
                case 11 =>
                  dist(1) + 1
                case 12 =>
                  dist(1) - 2
                case 13 =>
                  dist(1) + 2
                case 14 =>
                  dist(1) - 3
                case _ =>
                  dist(1) + 3
            if distance <= 0 then
              fail("distance is not positive")
          else if dcode < 16 + ndirect then
            distance = dcode - 15
          else
            val d     = dcode - ndirect - 16
            val nbits = 1 + (d >>> (npostfix + 1))
            val hcode = d >>> npostfix
            val lcode = d & postfixMask
            val off   = ((2 + (hcode & 1)).toLong << nbits) - 4
            val v     = ((off + bits(nbits)) << npostfix) + lcode + ndirect + 1
            if v > Int.MaxValue then
              fail("distance is too large")
            distance = v.toInt
          end if
        end if

        val maxDistance = math.min(windowSize, outPos)
        if distance > maxDistance then
          // A static dictionary reference
          if copyLen < 4 || copyLen > 24 then
            fail(s"dictionary word length ${copyLen} is out of range")
          val nbits     = DictionaryBits(copyLen)
          val wordId    = distance - maxDistance - 1
          val index     = wordId & ((1 << nbits) - 1)
          val transform = wordId >>> nbits
          if transform >= 121 then
            fail("dictionary transform is out of range")
          val written = transformWord(copyLen, index, transform, metaEnd)
          outPos += written
        else
          if copyLen > metaEnd - outPos then
            fail("copy length exceeds the meta-block")
          Lz77.copyMatch(out, outPos, distance, copyLen)
          outPos += copyLen
          if pushDist then
            dist(3) = dist(2)
            dist(2) = dist(1)
            dist(1) = dist(0)
            dist(0) = distance
      end if
    end while
    if outPos != metaEnd then
      fail("meta-block length does not match its commands")

  end metaBlock

  /** Writes a transformed dictionary word and returns its length */
  private def transformWord(len: Int, index: Int, transform: Int, limit: Int): Int =
    val dict   = BrotliDictionary.data
    val offset = DictionaryOffsets(len) + index * len
    val prefix = transformPrefixes(transform)
    val suffix = transformSuffixes(transform)
    val tpe    = BrotliTables.types(transform)
    var from   = offset
    var n      = len
    if tpe >= 1 && tpe <= 9 then
      n -= tpe
    else if tpe >= 12 && tpe <= 20 then
      val skip = math.min(tpe - 11, n)
      from += skip
      n -= skip
    val total = prefix.length + n + suffix.length
    if total > limit - outPos then
      fail("dictionary word exceeds the meta-block")
    var p = outPos
    System.arraycopy(prefix, 0, out, p, prefix.length)
    p += prefix.length
    val wordStart = p
    System.arraycopy(dict, from, out, p, n)
    p += n
    if tpe == 10 then
      uppercase(wordStart, n, all = false)
    else if tpe == 11 then
      uppercase(wordStart, n, all = true)
    System.arraycopy(suffix, 0, out, p, suffix.length)
    total

  /** The RFC's ASCII and UTF-8 aware uppercasing of the first or every character */
  private def uppercase(start: Int, n: Int, all: Boolean): Unit =
    var i    = 0
    var done = false
    while i < n && !done do
      val c = out(start + i) & 0xff
      if c < 0xc0 then
        if c >= 'a' && c <= 'z' then
          out(start + i) = (c ^ 32).toByte
        i += 1
      else if c < 0xe0 then
        if i + 1 < n then
          out(start + i + 1) = (out(start + i + 1) ^ 32).toByte
        i += 2
      else
        if i + 2 < n then
          out(start + i + 2) = (out(start + i + 2) ^ 5).toByte
        i += 3
      if !all then
        done = true

end BrotliDecoder
