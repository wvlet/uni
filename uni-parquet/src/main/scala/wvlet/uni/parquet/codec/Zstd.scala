package wvlet.uni.parquet.codec

import wvlet.uni.parquet.ParquetException

/**
  * A Zstandard decoder (RFC 8878), written in Scala. It decodes whole frames into an output buffer
  * of known size, which is how Parquet uses ZSTD: each page is compressed on its own. Dictionaries
  * are not supported, since Parquet writers do not use them.
  */
private[parquet] object Zstd:
  private final val Magic               = 0xfd2fb528
  private[codec] final val MaxBlockSize = 128 * 1024

  private[codec] def fail(msg: String): Nothing = throw ParquetException.corrupt(s"ZSTD: ${msg}")

  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val out = new Array[Byte](expectedSize)
    var ip  = offset
    val end = offset + length
    var op  = 0
    while ip < end do
      if end - ip < 4 then
        fail("frame header is truncated")
      val magic = le32(src, ip)
      if (magic & 0xfffffff0) == 0x184d2a50 then
        // Skippable frame
        if end - ip < 8 then
          fail("skippable frame is truncated")
        val size = le32(src, ip + 4).toLong & 0xffffffffL
        if size > end - ip - 8 then
          fail("skippable frame is truncated")
        ip += 8 + size.toInt
      else if magic != Magic then
        fail(f"unknown frame magic 0x${magic}%08x")
      else
        val frame = FrameDecoder(src, ip + 4, end, out, op)
        frame.run()
        ip = frame.inputPos
        op = frame.outputPos
    if op != expectedSize then
      fail(s"decoded ${op} bytes, expected ${expectedSize}")
    out

  private[codec] inline def le32(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  private[codec] inline def highBit(v: Int): Int = 31 - Integer.numberOfLeadingZeros(v)

  // ---- Predefined tables and code values (RFC 8878 section 3.1.1.3.2) ----

  private[codec] val LLDefault: Array[Int] = Array(
    4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
    -1, -1, -1, -1
  )

  private[codec] val MLDefault: Array[Int] = Array(
    1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1
  )

  private[codec] val OFDefault: Array[Int] = Array(
    1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
  )

  private[codec] val LLBase: Array[Int] = Array(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64,
    128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
  )

  private[codec] val LLBits: Array[Int] = Array(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11,
    12, 13, 14, 15, 16
  )

  private[codec] val MLBase: Array[Int] = Array(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
    29, 30, 31, 32, 33, 34, 35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
    4099, 8195, 16387, 32771, 65539
  )

  private[codec] val MLBits: Array[Int] = Array(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
  )

end Zstd

/** An FSE decoding table */
private[codec] final class FseTable(
    val accuracyLog: Int,
    val symbols: Array[Int],
    val nbBits: Array[Int],
    val newState: Array[Int]
):
  /** The entries [[SeqTables]] packed from this table, kept for repeat-mode blocks */
  private[codec] var seqEntries: Array[Long] = null

private[codec] object FseTable:
  /** Builds a decoding table from normalized probabilities (-1 means "less than 1") */
  def build(norm: Array[Int], numSymbols: Int, accuracyLog: Int): FseTable =
    val size          = 1 << accuracyLog
    val symbols       = new Array[Int](size)
    val nbBits        = new Array[Int](size)
    val newState      = new Array[Int](size)
    val next          = new Array[Int](numSymbols)
    var highThreshold = size - 1
    var s             = 0
    while s < numSymbols do
      if norm(s) == -1 then
        symbols(highThreshold) = s
        highThreshold -= 1
        next(s) = 1
      else
        next(s) = norm(s)
      s += 1
    val step = (size >>> 1) + (size >>> 3) + 3
    val mask = size - 1
    var pos  = 0
    s = 0
    while s < numSymbols do
      var i = 0
      while i < norm(s) do
        symbols(pos) = s
        pos = (pos + step) & mask
        while pos > highThreshold do
          pos = (pos + step) & mask
        i += 1
      s += 1
    if pos != 0 then
      Zstd.fail("FSE table probabilities do not fill the table")
    var u = 0
    while u < size do
      val sym = symbols(u)
      val ns  = next(sym)
      next(sym) += 1
      val bits = accuracyLog - Zstd.highBit(ns)
      nbBits(u) = bits
      newState(u) = (ns << bits) - size
      u += 1
    FseTable(accuracyLog, symbols, nbBits, newState)

  end build

  /** A table that always decodes `symbol`, for the RLE mode */
  def rle(symbol: Int): FseTable = FseTable(0, Array(symbol), Array(0), Array(0))

  /** Reads a table description. Returns the table and the number of bytes it used. */
  def read(buf: Array[Byte], start: Int, end: Int, maxSymbol: Int, maxLog: Int): (FseTable, Int) =
    if start >= end then
      Zstd.fail("FSE table description is missing")
    val r           = ForwardBitReader(buf, start, end)
    val accuracyLog = r.read(4) + 5
    if accuracyLog > maxLog then
      Zstd.fail(s"FSE accuracy log ${accuracyLog} exceeds ${maxLog}")
    val norm      = new Array[Int](maxSymbol + 1)
    var remaining = (1 << accuracyLog) + 1
    var threshold = 1 << accuracyLog
    var nbBits    = accuracyLog + 1
    var symbol    = 0
    var previous0 = false
    while remaining > 1 && symbol <= maxSymbol do
      if previous0 then
        var n0   = symbol
        var flag = r.read(2)
        while flag == 3 do
          n0 += 3
          flag = r.read(2)
        n0 += flag
        if n0 > maxSymbol + 1 then
          Zstd.fail("FSE table has too many zero-probability symbols")
        while symbol < n0 do
          norm(symbol) = 0
          symbol += 1
      if symbol <= maxSymbol then
        val max   = (2 * threshold - 1) - remaining
        val bs    = r.peek(nbBits)
        var count =
          if (bs & (threshold - 1)) < max then
            r.skip(nbBits - 1)
            bs & (threshold - 1)
          else
            r.skip(nbBits)
            val c = bs & (2 * threshold - 1)
            if c >= threshold then
              c - max
            else
              c
        count -= 1
        remaining -= math.abs(count)
        norm(symbol) = count
        symbol += 1
        previous0 = count == 0
        while remaining < threshold && threshold > 1 do
          nbBits -= 1
          threshold >>>= 1
      if r.bytesUsed > end - start then
        Zstd.fail("FSE table description is truncated")
    end while
    if remaining != 1 then
      Zstd.fail("FSE table probabilities do not add up")
    (build(norm, symbol, accuracyLog), r.bytesUsed)

  end read

end FseTable

/** A Huffman decoding table for literals: index by the next `maxBits` bits */
private[codec] final class HuffmanTable(
    val maxBits: Int,
    val symbols: Array[Byte],
    val bits: Array[Byte]
)

private[codec] object HuffmanTable:
  /** Reads a Huffman tree description. Returns the table and the bytes used. */
  def read(buf: Array[Byte], start: Int, end: Int): (HuffmanTable, Int) =
    if start >= end then
      Zstd.fail("Huffman tree description is missing")
    val header = buf(start) & 0xff
    // Up to 255 decoded weights plus the implied last one
    val weights = new Array[Int](260)
    var count   = 0
    var used    = 0
    if header >= 128 then
      // Weights stored directly, 4 bits each
      count = header - 127
      val bytes = (count + 1) / 2
      if bytes > end - start - 1 then
        Zstd.fail("Huffman weights are truncated")
      var i = 0
      while i < count do
        val b = buf(start + 1 + i / 2) & 0xff
        weights(i) =
          if i % 2 == 0 then
            b >>> 4
          else
            b & 0xf
        i += 1
      used = 1 + bytes
    else
      // Weights compressed with FSE, decoded by two interleaved states
      val size = header
      if size > end - start - 1 then
        Zstd.fail("Huffman weights are truncated")
      val (table, tableBytes) = FseTable.read(buf, start + 1, start + 1 + size, 255, 6)
      if tableBytes >= size then
        Zstd.fail("Huffman weight stream is empty")
      val br     = BackwardBitReader(buf, start + 1 + tableBytes, start + 1 + size)
      var state1 = br.read(table.accuracyLog)
      var state2 = br.read(table.accuracyLog)
      var done   = false
      while !done do
        if count > 252 then
          Zstd.fail("too many Huffman weights")
        weights(count) = table.symbols(state1)
        count += 1
        state1 = table.newState(state1) + br.read(table.nbBits(state1))
        if br.isOverflowed then
          weights(count) = table.symbols(state2)
          count += 1
          done = true
        else
          weights(count) = table.symbols(state2)
          count += 1
          state2 = table.newState(state2) + br.read(table.nbBits(state2))
          if br.isOverflowed then
            weights(count) = table.symbols(state1)
            count += 1
            done = true
      end while
      used = 1 + size
    end if
    (build(weights, count), used)

  end read

  private def build(weights: Array[Int], count: Int): HuffmanTable =
    // The last symbol's weight is implied: it fills the total to the next power of two
    var sum = 0L
    var i   = 0
    while i < count do
      val w = weights(i)
      if w > 11 then
        Zstd.fail(s"Huffman weight ${w} is too large")
      if w > 0 then
        sum += 1L << (w - 1)
      i += 1
    if sum == 0 then
      Zstd.fail("Huffman weights are all zero")
    val maxBits = Zstd.highBit(sum.toInt) + 1
    if maxBits > 11 then
      Zstd.fail(s"Huffman code length ${maxBits} is too large")
    val left = (1L << maxBits) - sum
    if left <= 0 || (left & (left - 1)) != 0 then
      Zstd.fail("Huffman weights do not form a complete tree")
    weights(count) = Zstd.highBit(left.toInt) + 1
    val numSymbols = count + 1

    val rankCount = new Array[Int](maxBits + 1)
    i = 0
    while i < numSymbols do
      rankCount(weights(i)) += 1
      i += 1
    val rankStart = new Array[Int](maxBits + 2)
    var next      = 0
    var w         = 1
    while w <= maxBits do
      rankStart(w) = next
      next += rankCount(w) << (w - 1)
      w += 1
    val size    = 1 << maxBits
    val symbols = new Array[Byte](size)
    val bits    = new Array[Byte](size)
    i = 0
    while i < numSymbols do
      val wt = weights(i)
      if wt > 0 then
        val len = 1 << (wt - 1)
        val st  = rankStart(wt)
        java.util.Arrays.fill(symbols, st, st + len, i.toByte)
        java.util.Arrays.fill(bits, st, st + len, (maxBits + 1 - wt).toByte)
        rankStart(wt) += len
      i += 1
    HuffmanTable(maxBits, symbols, bits)

  end build

  /** Decodes `n` literals from one stream into `out` */
  def decodeStream(
      t: HuffmanTable,
      buf: Array[Byte],
      start: Int,
      end: Int,
      out: Array[Byte],
      outPos: Int,
      n: Int
  ): Unit = ZstdHuffman.decode(t, buf, start, end, out, outPos, n)

end HuffmanTable

/** Decodes one frame, keeping the state shared by its blocks */
private[codec] final class FrameDecoder(
    src: Array[Byte],
    start: Int,
    end: Int,
    out: Array[Byte],
    outStart: Int
):
  import Zstd.*

  var inputPos: Int  = start
  var outputPos: Int = outStart

  private var huffman: HuffmanTable = null
  private var llTable: FseTable     = null
  private var ofTable: FseTable     = null
  private var mlTable: FseTable     = null
  private val rep                   = Array(1, 4, 8)
  private val literals              = new Array[Byte](MaxBlockSize + 16) // slack for wide copies
  private var maxBlock              = MaxBlockSize

  private def byteAt(i: Int): Int =
    if i >= end then
      fail("frame is truncated")
    src(i) & 0xff

  def run(): Unit =
    val descriptor = byteAt(inputPos)
    inputPos += 1
    val fcsFlag       = descriptor >>> 6
    val singleSegment = (descriptor & 0x20) != 0
    val hasChecksum   = (descriptor & 0x04) != 0
    val dictIdFlag    = descriptor & 0x03
    if (descriptor & 0x08) != 0 then
      fail("reserved frame header bit is set")
    if !singleSegment then
      val wd = byteAt(inputPos)
      inputPos += 1
      val exponent  = wd >>> 3
      val mantissa  = wd & 7
      val windowLog = 10 + exponent
      val base      = 1L << windowLog
      val window    = base + (base / 8) * mantissa
      maxBlock = math.min(MaxBlockSize.toLong, window).toInt
    val dictIdSize =
      dictIdFlag match
        case 0 =>
          0
        case 1 =>
          1
        case 2 =>
          2
        case _ =>
          4
    var dictId = 0L
    var i      = 0
    while i < dictIdSize do
      dictId |= byteAt(inputPos + i).toLong << (8 * i)
      i += 1
    inputPos += dictIdSize
    if dictId != 0 then
      throw ParquetException.unsupported("ZSTD frames that need a dictionary")
    val fcsSize =
      fcsFlag match
        case 0 =>
          if singleSegment then
            1
          else
            0
        case 1 =>
          2
        case 2 =>
          4
        case _ =>
          8
    var contentSize = -1L
    if fcsSize > 0 then
      var v = 0L
      i = 0
      while i < fcsSize do
        v |= byteAt(inputPos + i).toLong << (8 * i)
        i += 1
      contentSize =
        if fcsSize == 2 then
          v + 256
        else
          v
      inputPos += fcsSize
    if contentSize > out.length - outputPos then
      fail(s"frame content size ${contentSize} exceeds the expected output")
    val frameStart = outputPos

    var last = false
    while !last do
      val h = byteAt(inputPos) | (byteAt(inputPos + 1) << 8) | (byteAt(inputPos + 2) << 16)
      inputPos += 3
      last = (h & 1) != 0
      val blockType = (h >>> 1) & 3
      val blockSize = h >>> 3
      blockType match
        case 0 =>
          // Raw block
          if blockSize > end - inputPos || blockSize > out.length - outputPos then
            fail("raw block is out of range")
          System.arraycopy(src, inputPos, out, outputPos, blockSize)
          inputPos += blockSize
          outputPos += blockSize
        case 1 =>
          // RLE block: one byte repeated blockSize times
          val b = byteAt(inputPos).toByte
          inputPos += 1
          if blockSize > out.length - outputPos then
            fail("RLE block exceeds the expected output")
          java.util.Arrays.fill(out, outputPos, outputPos + blockSize, b)
          outputPos += blockSize
        case 2 =>
          if blockSize > end - inputPos || blockSize > MaxBlockSize then
            fail("compressed block is out of range")
          decodeBlock(inputPos, inputPos + blockSize, frameStart)
          inputPos += blockSize
        case _ =>
          fail("reserved block type")
    end while
    if hasChecksum then
      if end - inputPos < 4 then
        fail("frame checksum is truncated")
      inputPos += 4
    if contentSize >= 0 && outputPos - frameStart != contentSize then
      fail(s"frame decoded to ${outputPos - frameStart} bytes, but its header says ${contentSize}")

  end run

  private def decodeBlock(blockStart: Int, blockEnd: Int, frameStart: Int): Unit =
    // ---- Literals section ----
    var ip         = blockStart
    val b0         = byteAt(ip)
    val litType    = b0 & 3
    val sizeFormat = (b0 >>> 2) & 3
    var regenSize  = 0
    var compSize   = 0
    var numStreams = 1
    if litType <= 1 then
      sizeFormat match
        case 0 | 2 =>
          regenSize = b0 >>> 3
          ip += 1
        case 1 =>
          regenSize = (b0 >>> 4) | (byteAt(ip + 1) << 4)
          ip += 2
        case _ =>
          regenSize = (b0 >>> 4) | (byteAt(ip + 1) << 4) | (byteAt(ip + 2) << 12)
          ip += 3
    else
      sizeFormat match
        case 0 | 1 =>
          numStreams =
            if sizeFormat == 0 then
              1
            else
              4
          val h = b0 | (byteAt(ip + 1) << 8) | (byteAt(ip + 2) << 16)
          regenSize = (h >>> 4) & 0x3ff
          compSize = (h >>> 14) & 0x3ff
          ip += 3
        case 2 =>
          numStreams = 4
          val h = b0 | (byteAt(ip + 1) << 8) | (byteAt(ip + 2) << 16) | (byteAt(ip + 3) << 24)
          regenSize = (h >>> 4) & 0x3fff
          compSize = (h >>> 18) & 0x3fff
          ip += 4
        case _ =>
          numStreams = 4
          val h = b0 | (byteAt(ip + 1) << 8) | (byteAt(ip + 2) << 16) | (byteAt(ip + 3) << 24)
          regenSize = (h >>> 4) & 0x3ffff
          compSize = (h >>> 22) | (byteAt(ip + 4) << 10)
          ip += 5
    end if
    if regenSize > maxBlock then
      fail(s"literals size ${regenSize} exceeds the block size")

    litType match
      case 0 =>
        if regenSize > blockEnd - ip then
          fail("raw literals are truncated")
        System.arraycopy(src, ip, literals, 0, regenSize)
        ip += regenSize
      case 1 =>
        java.util.Arrays.fill(literals, 0, regenSize, byteAt(ip).toByte)
        ip += 1
      case _ =>
        if compSize > blockEnd - ip then
          fail("compressed literals are truncated")
        val litEnd = ip + compSize
        var p      = ip
        if litType == 2 then
          val (t, used) = HuffmanTable.read(src, p, litEnd)
          huffman = t
          p += used
        else if huffman == null then
          fail("treeless literals without a previous Huffman table")
        if numStreams == 1 then
          HuffmanTable.decodeStream(huffman, src, p, litEnd, literals, 0, regenSize)
        else
          if litEnd - p < 6 then
            fail("literal jump table is truncated")
          val s1   = (src(p) & 0xff) | ((src(p + 1) & 0xff) << 8)
          val s2   = (src(p + 2) & 0xff) | ((src(p + 3) & 0xff) << 8)
          val s3   = (src(p + 4) & 0xff) | ((src(p + 5) & 0xff) << 8)
          val base = p + 6
          val s4   = litEnd - base - s1 - s2 - s3
          if s4 <= 0 then
            fail("literal streams do not fit")
          val seg = (regenSize + 3) / 4
          if seg * 3 > regenSize then
            fail("too few literals for four streams")
          var at = base
          HuffmanTable.decodeStream(huffman, src, at, at + s1, literals, 0, seg)
          at += s1
          HuffmanTable.decodeStream(huffman, src, at, at + s2, literals, seg, seg)
          at += s2
          HuffmanTable.decodeStream(huffman, src, at, at + s3, literals, 2 * seg, seg)
          at += s3
          HuffmanTable.decodeStream(
            huffman,
            src,
            at,
            litEnd,
            literals,
            3 * seg,
            regenSize - 3 * seg
          )
        end if
        ip = litEnd
    end match

    // ---- Sequences section ----
    val n0    = byteAt(ip)
    var nbSeq = 0
    if n0 == 0 then
      ip += 1
    else if n0 < 128 then
      nbSeq = n0
      ip += 1
    else if n0 < 255 then
      nbSeq = ((n0 - 128) << 8) + byteAt(ip + 1)
      ip += 2
    else
      nbSeq = byteAt(ip + 1) + (byteAt(ip + 2) << 8) + 0x7f00
      ip += 3

    var litPos = 0
    if nbSeq > 0 then
      val modes = byteAt(ip)
      ip += 1
      if (modes & 3) != 0 then
        fail("reserved sequence mode bits are set")
      ip = readTable(
        (modes >>> 6) & 3,
        ip,
        blockEnd,
        35,
        9,
        LLDefault,
        6,
        t => llTable = t,
        llTable
      )
      ip = readTable(
        (modes >>> 4) & 3,
        ip,
        blockEnd,
        31,
        8,
        OFDefault,
        5,
        t => ofTable = t,
        ofTable
      )
      ip = readTable(
        (modes >>> 2) & 3,
        ip,
        blockEnd,
        52,
        9,
        MLDefault,
        6,
        t => mlTable = t,
        mlTable
      )

      val r = ZstdSequences.decode(
        src,
        ip,
        blockEnd,
        nbSeq,
        llTable,
        mlTable,
        ofTable,
        literals,
        regenSize,
        out,
        outputPos,
        frameStart,
        rep
      )
      outputPos = r.toInt
      litPos = (r >>> 32).toInt
    end if

    // Remaining literals
    val rest = regenSize - litPos
    if rest > out.length - outputPos then
      fail("literals exceed the expected output")
    System.arraycopy(literals, litPos, out, outputPos, rest)
    outputPos += rest

  end decodeBlock

  private def readTable(
      mode: Int,
      ip: Int,
      blockEnd: Int,
      maxSymbol: Int,
      maxLog: Int,
      default: Array[Int],
      defaultLog: Int,
      set: FseTable => Unit,
      previous: FseTable
  ): Int =
    mode match
      case 0 =>
        set(FseTable.build(default, default.length, defaultLog))
        ip
      case 1 =>
        val sym = byteAt(ip)
        if sym > maxSymbol then
          fail(s"RLE symbol ${sym} is out of range")
        set(FseTable.rle(sym))
        ip + 1
      case 2 =>
        val (t, used) = FseTable.read(src, ip, blockEnd, maxSymbol, maxLog)
        set(t)
        ip + used
      case _ =>
        if previous == null then
          fail("repeat mode without a previous table")
        ip

end FrameDecoder
