package wvlet.uni.parquet.codec

import wvlet.uni.parquet.io.ByteBuf

/**
  * A Zstandard compressor (RFC 8878), the inverse of [[Zstd]]. Each call writes one frame with the
  * content size, which is how Parquet stores ZSTD pages.
  *
  * Matches come from a hash of 4-byte sequences with one step of lazy matching and a check of the
  * last offset, over a 2 MiB window. Each block gets Huffman-coded literals and FSE-coded
  * sequences, with a predefined, RLE, or custom table for each sequence code, whichever is
  * estimated smaller. A block that does not shrink is stored raw. The ratio is close to zstd's fast
  * levels; there are no levels to choose.
  */
private[parquet] object ZstdCompressor:
  import Zstd.highBit

  private final val Magic      = 0xfd2fb528
  private final val WindowLog  = 21
  private final val WindowSize = 1 << WindowLog
  private final val MinMatch   = 4
  // Literals sections shorter than this stay raw: a Huffman tree costs more than it saves
  private final val MinHuffmanLiterals = 64

  def compress(src: Array[Byte], len: Int): Array[Byte] =
    val out = ByteBuf(len / 2 + 32)
    writeFrameHeader(len, out)
    if len == 0 then
      // One empty raw block, marked last
      writeBlockHeader(last = true, blockType = 0, size = 0, out)
    else
      val state = FrameState(src, len)
      var pos   = 0
      while pos < len do
        val end = math.min(pos + Zstd.MaxBlockSize, len)
        state.writeBlock(pos, end, last = end == len, out)
        pos = end
    out.toByteArray

  private def writeFrameHeader(len: Int, out: ByteBuf): Unit =
    out.writeIntLE(Magic)
    if len <= WindowSize then
      // Single segment: the window is the content, and the content size is required
      if len < 256 then
        out.writeByte(0x20)
        out.writeByte(len)
      else if len < 65536 + 256 then
        out.writeByte(
          (
            1 << 6
          ) | 0x20
        )
        val v = len - 256
        out.writeByte(v)
        out.writeByte(v >>> 8)
      else
        out.writeByte(
          (
            2 << 6
          ) | 0x20
        )
        out.writeIntLE(len)
    else
      // A window descriptor (exponent only), then a 4-byte content size
      out.writeByte(2 << 6)
      out.writeByte(
        (
          WindowLog - 10
        ) << 3
      )
      out.writeIntLE(len)
    end if

  end writeFrameHeader

  private[codec] def writeBlockHeader(
      last: Boolean,
      blockType: Int,
      size: Int,
      out: ByteBuf
  ): Unit =
    val h =
      (
        if last then
          1
        else
          0
      ) | (blockType << 1) | (size << 3)
    out.writeByte(h)
    out.writeByte(h >>> 8)
    out.writeByte(h >>> 16)

  // ---- Sequence codes (RFC 8878 section 3.1.1.3.2.1.1) ----

  private val LLCodeTable: Array[Int] =
    Array.tabulate(64) { v =>
      var c = 0
      while c + 1 < Zstd.LLBase.length && Zstd.LLBase(c + 1) <= v do
        c += 1
      c
    }

  private val MLCodeTable: Array[Int] =
    Array.tabulate(128) { v =>
      var c = 0
      while c + 1 < Zstd.MLBase.length && Zstd.MLBase(c + 1) <= v + 3 do
        c += 1
      c
    }

  private[codec] inline def llCode(ll: Int): Int =
    if ll < 64 then
      LLCodeTable(ll)
    else
      highBit(ll) + 19

  private[codec] inline def mlCode(ml: Int): Int =
    val v = ml - 3
    if v < 128 then
      MLCodeTable(v)
    else
      highBit(v) + 36

  /** The match finder and block encoder for one frame */
  private final class FrameState(src: Array[Byte], len: Int):
    private val hashLog = math.max(8, math.min(16, highBit(math.max(1, len)) + 1))
    private val table   = new Array[Int](1 << hashLog)
    java.util.Arrays.fill(table, -1)

    private val rep      = Array(1, 4, 8)
    private val savedRep = new Array[Int](3)

    private val blockCap = math.min(len, Zstd.MaxBlockSize)
    private val literals = new Array[Byte](blockCap)
    private var litSize  = 0
    private val maxSeq   = blockCap / MinMatch + 1
    private val seqLL    = new Array[Int](maxSeq)
    private val seqML    = new Array[Int](maxSeq)
    private val seqOF = new Array[Int](maxSeq) // offset values: 1-3 repeat codes, else offset + 3
    private var nbSeq = 0

    private val body = ByteBuf(blockCap / 2 + 64)

    private inline def load32(i: Int): Int = Zstd.le32(src, i)

    private inline def hash(v: Int): Int =
      (
        v * 0x9e3779b1
      ) >>> (32 - hashLog)

    /** The match length between `a` and a later `b`, with `b` + length at most `limit` */
    private def matchLength(a: Int, b: Int, limit: Int): Int =
      var n = 0
      while b + n + 8 <= limit do
        val x = Word8.le64(src, a + n) ^ Word8.le64(src, b + n)
        if x != 0 then
          return n + (java.lang.Long.numberOfTrailingZeros(x) >>> 3)
        n += 8
      while b + n < limit && src(a + n) == src(b + n) do
        n += 1
      n

    def writeBlock(start: Int, end: Int, last: Boolean, out: ByteBuf): Unit =
      val size = end - start
      if isRun(start, end) then
        writeBlockHeader(last, blockType = 1, size, out)
        out.writeByte(src(start))
      else
        System.arraycopy(rep, 0, savedRep, 0, 3)
        findSequences(start, end)
        body.reset()
        writeLiterals(body)
        writeSequences(body)
        if body.size < size then
          writeBlockHeader(last, blockType = 2, body.size, out)
          out.write(body)
        else
          // Stored raw: the decoder's repeat offsets do not change
          System.arraycopy(savedRep, 0, rep, 0, 3)
          writeBlockHeader(last, blockType = 0, size, out)
          out.write(src, start, size)

    private def isRun(start: Int, end: Int): Boolean =
      val b = src(start)
      var i = start + 1
      while i < end && src(i) == b do
        i += 1
      i == end && end - start > 1

    // ---- Match finding ----

    private def findSequences(start: Int, end: Int): Unit =
      litSize = 0
      nbSeq = 0
      var anchor = start
      var ip     = start
      val ilimit = end - MinMatch - 1 // leaves room to check the next position lazily
      while ip <= ilimit do
        val r0 = rep(0)
        if ip > anchor && ip - r0 >= 0 && load32(ip - r0) == load32(ip) then
          // The last offset again: the cheapest match to encode
          val m = MinMatch + matchLength(ip - r0 + MinMatch, ip + MinMatch, end)
          insertHash(ip)
          addSequence(anchor, ip, r0, m)
          ip += m
          anchor = ip
          insertHash(ip - 2)
        else
          val v = load32(ip)
          val h = hash(v)
          var c = table(h)
          table(h) = ip
          if c >= 0 && ip - c <= WindowSize && load32(c) == v then
            var p = ip
            var m = MinMatch + matchLength(c + MinMatch, ip + MinMatch, end)
            // Lazy matching: a longer match at the next position wins
            val v1 = load32(ip + 1)
            val h1 = hash(v1)
            val c1 = table(h1)
            table(h1) = ip + 1
            if c1 >= 0 && ip + 1 - c1 <= WindowSize && load32(c1) == v1 then
              val m1 = MinMatch + matchLength(c1 + MinMatch, ip + 1 + MinMatch, end)
              if m1 > m then
                p = ip + 1
                c = c1
                m = m1
            // Extend the match backward over pending literals
            while p > anchor && c > 0 && src(p - 1) == src(c - 1) do
              p -= 1
              c -= 1
              m += 1
            addSequence(anchor, p, p - c, m)
            ip = p + m
            anchor = ip
            insertHash(ip - 2)
          else
            // Step faster through data that does not match
            ip += 1 + (
              (
                ip - anchor
              ) >>> 6
            )
          end if
        end if
      end while
      copyLiterals(anchor, end - anchor)

    end findSequences

    private inline def insertHash(i: Int): Unit =
      if i >= 0 && i + 4 <= len then
        table(hash(load32(i))) = i

    private def copyLiterals(from: Int, n: Int): Unit =
      System.arraycopy(src, from, literals, litSize, n)
      litSize += n

    private def addSequence(anchor: Int, at: Int, offset: Int, matchLen: Int): Unit =
      val ll = at - anchor
      copyLiterals(anchor, ll)
      // Choose a repeat code when the offset is one of the last three, as the decoder resolves it
      val ofValue =
        if ll > 0 then
          if offset == rep(0) then
            1
          else if offset == rep(1) then
            2
          else if offset == rep(2) then
            3
          else
            offset + 3
        else if offset == rep(1) then
          1
        else if offset == rep(2) then
          2
        else if offset == rep(0) - 1 then
          3
        else
          offset + 3
      updateRep(ofValue, ll)
      seqLL(nbSeq) = ll
      seqML(nbSeq) = matchLen
      seqOF(nbSeq) = ofValue
      nbSeq += 1

    /** Updates the repeat offsets exactly as [[ZstdSequences]] does */
    private def updateRep(ofValue: Int, ll: Int): Unit =
      if ofValue > 3 then
        rep(2) = rep(1)
        rep(1) = rep(0)
        rep(0) = ofValue - 3
      else
        val idx =
          if ll == 0 then
            ofValue + 1
          else
            ofValue
        if idx != 1 then
          val o =
            idx match
              case 2 =>
                rep(1)
              case 3 =>
                rep(2)
              case _ =>
                rep(0) - 1
          if idx != 2 then
            rep(2) = rep(1)
          rep(1) = rep(0)
          rep(0) = o

    // ---- Literals section ----

    private def writeLiterals(out: ByteBuf): Unit =
      val n = litSize
      if n == 0 then
        writeRawLiteralsHeader(0, 0, out)
      else
        val counts = new Array[Int](256)
        var i      = 0
        while i < n do
          counts(literals(i) & 0xff) += 1
          i += 1
        var distinct = 0
        var maxSym   = 0
        i = 0
        while i < 256 do
          if counts(i) > 0 then
            distinct += 1
            maxSym = i
          i += 1
        if distinct == 1 then
          writeRawLiteralsHeader(1, n, out)
          out.writeByte(literals(0))
        else if n < MinHuffmanLiterals || !writeHuffmanLiterals(counts, maxSym, out) then
          writeRawLiteralsHeader(0, n, out)
          out.write(literals, 0, n)

    /** The header of raw (type 0) or RLE (type 1) literals */
    private def writeRawLiteralsHeader(litType: Int, n: Int, out: ByteBuf): Unit =
      if n < 32 then
        out.writeByte(litType | (n << 3))
      else if n < 4096 then
        out.writeByte(
          litType | (1 << 2) | (
            (
              n & 0xf
            ) << 4
          )
        )
        out.writeByte(n >>> 4)
      else
        out.writeByte(
          litType | (3 << 2) | (
            (
              n & 0xf
            ) << 4
          )
        )
        out.writeByte(n >>> 4)
        out.writeByte(n >>> 12)

    /** Writes Huffman-coded literals. False, writing nothing, when they would not be smaller. */
    private def writeHuffmanLiterals(counts: Array[Int], maxSym: Int, out: ByteBuf): Boolean =
      val n       = litSize
      val lengths = HuffmanEncoder.codeLengths(counts, maxSym + 1, HuffmanEncoder.MaxBits)
      val tree    = ByteBuf(160)
      if !HuffmanEncoder.writeTree(lengths, maxSym, tree) then
        false
      else
        val codes   = HuffmanEncoder.codes(lengths, maxSym + 1)
        val streams = ByteBuf(n)
        val single  = n <= 1023
        if single then
          HuffmanEncoder.encodeStream(literals, 0, n, codes, lengths, streams)
        else
          val seg =
            (
              n + 3
            ) / 4
          val s1 = ByteBuf(seg)
          val s2 = ByteBuf(seg)
          val s3 = ByteBuf(seg)
          HuffmanEncoder.encodeStream(literals, 0, seg, codes, lengths, s1)
          HuffmanEncoder.encodeStream(literals, seg, seg, codes, lengths, s2)
          HuffmanEncoder.encodeStream(literals, 2 * seg, seg, codes, lengths, s3)
          for s <- Seq(s1, s2, s3) do
            streams.writeByte(s.size)
            streams.writeByte(s.size >>> 8)
          streams.write(s1)
          streams.write(s2)
          streams.write(s3)
          HuffmanEncoder.encodeStream(literals, 3 * seg, n - 3 * seg, codes, lengths, streams)
        // A block holds at most 128 KiB of literals, so each of four streams stays under the jump
        // table's 64 KiB limit even at 11 bits per literal
        val compSize   = tree.size + streams.size
        val headerSize =
          if single then
            3
          else if n <= 16383 && compSize <= 16383 then
            4
          else
            5
        // Raw literals would take n bytes plus a header of up to 3 bytes
        if headerSize + compSize >= n + 3 then
          false
        else
          val litType = 2
          if single then
            val h = litType | (n << 4) | (compSize << 14)
            writeLE(h, 3, out)
          else if headerSize == 4 then
            val h = litType | (2 << 2) | (n << 4) | (compSize << 18)
            writeLE(h, 4, out)
          else
            val h = litType | (3 << 2) | (n << 4) | (compSize << 22)
            writeLE(h, 4, out)
            out.writeByte(compSize >>> 10)
          out.write(tree)
          out.write(streams)
          true
        end if
      end if

    end writeHuffmanLiterals

    // ---- Sequences section ----

    private def writeSequences(out: ByteBuf): Unit =
      if nbSeq < 128 then
        out.writeByte(nbSeq)
      else if nbSeq < 0x7f00 then
        out.writeByte(
          (
            nbSeq >>> 8
          ) + 128
        )
        out.writeByte(nbSeq)
      else
        out.writeByte(255)
        val v = nbSeq - 0x7f00
        out.writeByte(v)
        out.writeByte(v >>> 8)
      if nbSeq > 0 then
        val llCodes = new Array[Int](nbSeq)
        val mlCodes = new Array[Int](nbSeq)
        val ofCodes = new Array[Int](nbSeq)
        var i       = 0
        while i < nbSeq do
          llCodes(i) = llCode(seqLL(i))
          mlCodes(i) = mlCode(seqML(i))
          ofCodes(i) = highBit(seqOF(i))
          i += 1
        val modesPos = out.size
        out.writeByte(0)
        val (llMode, llTable) = SeqTableChoice.choose(llCodes, nbSeq, 35, 9, Zstd.LLDefault, 6, out)
        val (ofMode, ofTable) = SeqTableChoice.choose(ofCodes, nbSeq, 31, 8, Zstd.OFDefault, 5, out)
        val (mlMode, mlTable) = SeqTableChoice.choose(mlCodes, nbSeq, 52, 9, Zstd.MLDefault, 6, out)
        out.array(modesPos) =
          (
            (
              llMode << 6
            ) | (ofMode << 4) | (mlMode << 2)
          ).toByte

        val w    = BackwardBitWriter(out)
        val last = nbSeq - 1
        var llS  = llTable.initState(llCodes(last))
        var ofS  = ofTable.initState(ofCodes(last))
        var mlS  = mlTable.initState(mlCodes(last))
        addExtraBits(w, last, llCodes(last), mlCodes(last), ofCodes(last))
        i = last - 1
        while i >= 0 do
          ofS = ofTable.encode(w, ofS, ofCodes(i))
          mlS = mlTable.encode(w, mlS, mlCodes(i))
          llS = llTable.encode(w, llS, llCodes(i))
          addExtraBits(w, i, llCodes(i), mlCodes(i), ofCodes(i))
          i -= 1
        mlTable.flush(w, mlS)
        ofTable.flush(w, ofS)
        llTable.flush(w, llS)
        w.close()
      end if

    end writeSequences

    private inline def addExtraBits(w: BackwardBitWriter, i: Int, ll: Int, ml: Int, of: Int): Unit =
      w.add(seqLL(i) - Zstd.LLBase(ll), Zstd.LLBits(ll))
      w.add(seqML(i) - Zstd.MLBase(ml), Zstd.MLBits(ml))
      w.add(seqOF(i) - (1 << of), of)

  end FrameState

  private def writeLE(v: Int, bytes: Int, out: ByteBuf): Unit =
    var i = 0
    while i < bytes do
      out.writeByte(v >>> (8 * i))
      i += 1

end ZstdCompressor

/** Picks the table mode for one kind of sequence code and writes its description */
private object SeqTableChoice:
  /**
    * Chooses between the predefined table (mode 0), a single symbol (mode 1), and a table described
    * in the block (mode 2), writing the description of mode 2 to `out`.
    */
  def choose(
      codes: Array[Int],
      n: Int,
      maxSymbol: Int,
      maxLog: Int,
      default: Array[Int],
      defaultLog: Int,
      out: ByteBuf
  ): (Int, FseCTable) =
    val counts = new Array[Int](maxSymbol + 1)
    var max    = 0
    var i      = 0
    while i < n do
      val c = codes(i)
      counts(c) += 1
      if c > max then
        max = c
      i += 1
    var distinct = 0
    i = 0
    while i <= max do
      if counts(i) > 0 then
        distinct += 1
      i += 1
    if distinct == 1 then
      out.writeByte(max)
      val norm = new Array[Int](max + 1)
      norm(max) = 1
      (1, FseCTable.build(norm, max + 1, 0))
    else
      val predefinedCost =
        if max < default.length then
          cost(counts, max, default, defaultLog)
        else
          Double.MaxValue
      val log  = FseCTable.optimalLog(maxLog, n, max, distinct)
      val norm = FseCTable.normalize(counts, max, n, log, 1 << log)
      val desc = ByteBuf(64)
      FseCTable.writeNCount(norm, max + 1, log, desc)
      val custom = desc.size * 8 + cost(counts, max, norm, log)
      if predefinedCost <= custom then
        (0, FseCTable.build(default, default.length, defaultLog))
      else
        out.write(desc)
        (2, FseCTable.build(norm, max + 1, log))

  end choose

  /** The estimated bits to encode the counted symbols with a table of these probabilities */
  private def cost(counts: Array[Int], max: Int, norm: Array[Int], log: Int): Double =
    var bits = 0.0
    var s    = 0
    while s <= max do
      if counts(s) > 0 then
        val p =
          if norm(s) == -1 then
            1
          else
            norm(s)
        if p <= 0 then
          return Double.MaxValue
        bits += counts(s) * (log - math.log(p.toDouble) / math.log(2.0))
      s += 1
    bits

end SeqTableChoice

/**
  * Writes a bitstream that [[BackwardBitReader]] reads: bits go in from the lowest bit up, and
  * `close` adds the 1 bit that marks the end. The last bits written are the first read.
  */
private[codec] final class BackwardBitWriter(out: ByteBuf):
  private var container = 0L
  private var bitCount  = 0

  /** Appends the low `n` (at most 32) bits of `v` */
  def add(v: Long, n: Int): Unit =
    container |= (
      v & (
        (
          1L << n
        ) - 1
      )
    ) << bitCount
    bitCount += n
    while bitCount >= 8 do
      out.writeByte(container.toInt)
      container >>>= 8
      bitCount -= 8

  def close(): Unit =
    add(1, 1)
    if bitCount > 0 then
      out.writeByte(container.toInt)
      container = 0
      bitCount = 0

end BackwardBitWriter

/**
  * An FSE encoding table, built from the same normalized probabilities as the decoder's
  * [[FseTable]] (zstd's `FSE_buildCTable`). States are kept in `[size, 2 * size)`.
  */
private[codec] final class FseCTable(
    val tableLog: Int,
    stateTable: Array[Int],
    deltaNbBits: Array[Int],
    deltaFindState: Array[Int]
):
  /** The state that encodes `sym` first, for the last symbol of a stream */
  def initState(sym: Int): Int =
    val nbBitsOut =
      (
        deltaNbBits(sym) + (1 << 15)
      ) >> 16
    val value =
      (
        nbBitsOut << 16
      ) - deltaNbBits(sym)
    stateTable(
      (
        value >> nbBitsOut
      ) + deltaFindState(sym)
    )

  /** Writes the bits that lead from `sym`'s state to `state`, returning `sym`'s state */
  def encode(w: BackwardBitWriter, state: Int, sym: Int): Int =
    val nbBitsOut =
      (
        state + deltaNbBits(sym)
      ) >> 16
    w.add(state, nbBitsOut)
    stateTable(
      (
        state >> nbBitsOut
      ) + deltaFindState(sym)
    )

  def flush(w: BackwardBitWriter, state: Int): Unit = w.add(state, tableLog)

end FseCTable

private[codec] object FseCTable:
  import Zstd.highBit

  def build(norm: Array[Int], numSymbols: Int, tableLog: Int): FseCTable =
    val size        = 1 << tableLog
    val mask        = size - 1
    val tableSymbol = new Array[Int](size)
    val cumul       = new Array[Int](numSymbols + 1)
    var high        = size - 1
    var s           = 0
    while s < numSymbols do
      if norm(s) == -1 then
        cumul(s + 1) = cumul(s) + 1
        tableSymbol(high) = s
        high -= 1
      else
        cumul(s + 1) = cumul(s) + norm(s)
      s += 1
    // Spread the symbols as the decoder does
    val step =
      (
        size >>> 1
      ) + (size >>> 3) + 3
    var pos = 0
    s = 0
    while s < numSymbols do
      var i = 0
      while i < norm(s) do
        tableSymbol(pos) = s
        pos =
          (
            pos + step
          ) & mask
        while pos > high do
          pos =
            (
              pos + step
            ) & mask
        i += 1
      s += 1
    val stateTable = new Array[Int](size)
    var u          = 0
    while u < size do
      val sym = tableSymbol(u)
      stateTable(cumul(sym)) = size + u
      cumul(sym) += 1
      u += 1
    val deltaNbBits    = new Array[Int](numSymbols)
    val deltaFindState = new Array[Int](numSymbols)
    var total          = 0
    s = 0
    while s < numSymbols do
      norm(s) match
        case 0 =>
          deltaNbBits(s) =
            (
              (
                tableLog + 1
              ) << 16
            ) - size
        case -1 | 1 =>
          deltaNbBits(s) =
            (
              tableLog << 16
            ) - size
          deltaFindState(s) = total - 1
          total += 1
        case n =>
          val maxBitsOut   = tableLog - highBit(n - 1)
          val minStatePlus = n << maxBitsOut
          deltaNbBits(s) =
            (
              maxBitsOut << 16
            ) - minStatePlus
          deltaFindState(s) = total - n
          total += n
      s += 1
    FseCTable(tableLog, stateTable, deltaNbBits, deltaFindState)

  end build

  /** A table size for `total` symbols up to `maxSymbol` (zstd's `FSE_optimalTableLog`) */
  def optimalLog(maxLog: Int, total: Int, maxSymbol: Int, distinct: Int): Int =
    var log = maxLog
    if total > 1 then
      val maxBitsSrc = highBit(total - 1) - 2
      if maxBitsSrc < log then
        log = maxBitsSrc
    val minBits = math.min(highBit(math.max(1, total)) + 1, highBit(math.max(1, maxSymbol)) + 2)
    if minBits > log then
      log = minBits
    log = math.max(5, math.min(maxLog, log))
    while (
        1 << log
      ) < distinct && log < maxLog
    do
      log += 1
    log

  /**
    * Scales `counts` to probabilities summing to `2^log`, giving every present symbol at least 1
    * and none more than `maxProb`. Symbols absent from `counts` may get probability to reach the
    * total when every present one is at `maxProb`.
    */
  def normalize(
      counts: Array[Int],
      maxSymbol: Int,
      total: Int,
      log: Int,
      maxProb: Int
  ): Array[Int] =
    val size = 1 << log
    val norm = new Array[Int](maxSymbol + 1)
    var sum  = 0
    var s    = 0
    while s <= maxSymbol do
      val c = counts(s)
      if c > 0 then
        val p =
          (
            (
              c.toLong * size + total / 2
            ) / total
          ).toInt
        norm(s) = math.min(maxProb, math.max(1, p))
        sum += norm(s)
      s += 1
    // Take the excess from the most probable symbols
    while sum > size do
      var best = -1
      s = 0
      while s <= maxSymbol do
        if norm(s) > 1 && (best < 0 || norm(s) > norm(best)) then
          best = s
        s += 1
      norm(best) -= 1
      sum -= 1
    // Give the shortfall to the most frequent symbols that can take more
    while sum < size do
      var best = -1
      s = 0
      while s <= maxSymbol do
        if norm(s) < maxProb && (best < 0 || counts(s) > counts(best)) then
          best = s
        s += 1
      norm(best) += 1
      sum += 1
    norm

  end normalize

  /** Writes a table description that [[FseTable.read]] reads (zstd's `FSE_writeNCount`) */
  def writeNCount(norm: Array[Int], numSymbols: Int, log: Int, out: ByteBuf): Unit =
    var container                 = 0L
    var bitCount                  = 0
    def put(v: Int, n: Int): Unit =
      container |= (
        v.toLong & (
          (
            1L << n
          ) - 1
        )
      ) << bitCount
      bitCount += n
      while bitCount >= 8 do
        out.writeByte(container.toInt)
        container >>>= 8
        bitCount -= 8
    put(log - 5, 4)
    var remaining =
      (
        1 << log
      ) + 1
    var threshold = 1 << log
    var nbBits    = log + 1
    var symbol    = 0
    var previous0 = false
    while symbol < numSymbols && remaining > 1 do
      if previous0 then
        var start = symbol
        while symbol < numSymbols && norm(symbol) == 0 do
          symbol += 1
        while symbol >= start + 24 do
          start += 24
          put(0xffff, 16)
        while symbol >= start + 3 do
          start += 3
          put(3, 2)
        put(symbol - start, 2)
      var count = norm(symbol)
      symbol += 1
      val max =
        (
          2 * threshold - 1
        ) - remaining
      remaining -= math.abs(count)
      count += 1
      if count >= threshold then
        count += max
      put(
        count,
        nbBits -
          (
            if count < max then
              1
            else
              0
          )
      )
      previous0 = count == 1
      while remaining < threshold && threshold > 1 do
        nbBits -= 1
        threshold >>>= 1
    end while
    if bitCount > 0 then
      out.writeByte(container.toInt)

  end writeNCount

end FseCTable

/** Huffman coding of literals (RFC 8878 section 4.2) */
private[codec] object HuffmanEncoder:
  final val MaxBits = 11

  /**
    * Code lengths for the symbols `0 until numSymbols` (0 for absent ones): a Huffman code, limited
    * to `maxBits` and complete, as the format requires. Needs at least two present symbols.
    */
  def codeLengths(counts: Array[Int], numSymbols: Int, maxBits: Int): Array[Int] =
    val present =
      (
        0 until numSymbols
      ).filter(counts(_) > 0).sortBy(s => (counts(s), s)).toArray
    val n      = present.length
    val weight = new Array[Long](2 * n)
    val parent = new Array[Int](2 * n)
    var k      = 0
    while k < n do
      weight(k) = counts(present(k))
      k += 1
    // Two queues: sorted leaves, and internal nodes, which are created in increasing weight
    var leaf        = 0
    var internal    = n
    var next        = n
    def pick(): Int =
      if leaf < n && (internal >= next || weight(leaf) <= weight(internal)) then
        leaf += 1
        leaf - 1
      else
        internal += 1
        internal - 1
    while next < 2 * n - 1 do
      val a = pick()
      val b = pick()
      weight(next) = weight(a) + weight(b)
      parent(a) = next
      parent(b) = next
      next += 1
    val depth = new Array[Int](2 * n)
    k = 2 * n - 3
    while k >= 0 do
      depth(k) = depth(parent(k)) + 1
      k -= 1
    // `present` is sorted by count, so index 0 is the least frequent symbol
    val len   = Array.tabulate(n)(i => math.min(depth(i), maxBits))
    val cap   = 1L << maxBits
    var kraft = 0L
    k = 0
    while k < n do
      kraft += 1L << (maxBits - len(k))
      k += 1
    // Clamping overfills the code: lengthen the rarest symbols among the longest below the limit
    while kraft > cap do
      var best = -1
      k = 0
      while k < n do
        if len(k) < maxBits && (best < 0 || len(k) > len(best)) then
          best = k
        k += 1
      kraft -= 1L << (maxBits - len(best) - 1)
      len(best) += 1
    // Then fill any gap by shortening the most frequent symbols that fit in it
    while kraft < cap do
      val gap  = cap - kraft
      var best = -1
      k = n - 1
      while k >= 0 && best < 0 do
        if len(k) > 1 && (
            1L << (maxBits - len(k))
          ) <= gap
        then
          best = k
        k -= 1
      kraft += 1L << (maxBits - len(best))
      len(best) -= 1
    val lengths = new Array[Int](numSymbols)
    k = 0
    while k < n do
      lengths(present(k)) = len(k)
      k += 1
    lengths

  end codeLengths

  /** Code values matching the decoder's table: by weight, then by symbol */
  def codes(lengths: Array[Int], numSymbols: Int): Array[Int] =
    val maxLen    = lengths.max
    val rankCount = new Array[Int](maxLen + 2)
    var s         = 0
    while s < numSymbols do
      if lengths(s) > 0 then
        rankCount(maxLen + 1 - lengths(s)) += 1
      s += 1
    val rankStart = new Array[Int](maxLen + 2)
    var next      = 0
    var w         = 1
    while w <= maxLen do
      rankStart(w) = next
      next += rankCount(w) << (w - 1)
      w += 1
    val codes = new Array[Int](numSymbols)
    s = 0
    while s < numSymbols do
      if lengths(s) > 0 then
        val wt = maxLen + 1 - lengths(s)
        codes(s) = rankStart(wt) >>> (wt - 1)
        rankStart(wt) += 1 << (wt - 1)
      s += 1
    codes

  end codes

  /**
    * Writes the tree description: the weights of symbols below `maxSym` (the last weight is
    * implied), FSE-compressed or 4 bits each, whichever is smaller. False when neither fits.
    */
  def writeTree(lengths: Array[Int], maxSym: Int, out: ByteBuf): Boolean =
    val maxLen  = lengths.max
    val count   = maxSym
    val weights =
      Array.tabulate(count) { s =>
        if lengths(s) > 0 then
          maxLen + 1 - lengths(s)
        else
          0
      }
    val fse =
      if count >= 2 then
        compressWeights(weights, count)
      else
        null
    val directSize =
      1 + (
        count + 1
      ) / 2
    if fse != null && fse.size < 128 && (count > 128 || fse.size + 1 < directSize) then
      out.writeByte(fse.size)
      out.write(fse)
      true
    else if count <= 128 then
      out.writeByte(127 + count)
      var i = 0
      while i < count do
        val hi = weights(i)
        val lo =
          if i + 1 < count then
            weights(i + 1)
          else
            0
        out.writeByte(
          (
            hi << 4
          ) | lo
        )
        i += 2
      true
    else
      false

  end writeTree

  /** FSE-compresses the weights with two interleaved states, as [[HuffmanTable.read]] decodes */
  private def compressWeights(weights: Array[Int], n: Int): ByteBuf =
    val counts = new Array[Int](MaxBits + 2)
    var max    = 0
    var i      = 0
    while i < n do
      counts(weights(i)) += 1
      max = math.max(max, weights(i))
      i += 1
    val distinct = counts.count(_ > 0)
    val log      = FseCTable.optimalLog(6, n, max, distinct)
    // No symbol above half the table: then every state reads at least one bit, which the decoder
    // relies on to find the end of the stream
    val norm = FseCTable.normalize(counts, max, n, log, 1 << (log - 1))
    val out  = ByteBuf(64)
    FseCTable.writeNCount(norm, max + 1, log, out)
    val ct = FseCTable.build(norm, max + 1, log)
    val w  = BackwardBitWriter(out)
    var ip = n
    var s1 = 0
    var s2 = 0
    if (
        n & 1
      ) != 0
    then
      s1 = ct.initState(weights(n - 1))
      s2 = ct.initState(weights(n - 2))
      s1 = ct.encode(w, s1, weights(n - 3))
      ip = n - 3
    else
      s2 = ct.initState(weights(n - 1))
      s1 = ct.initState(weights(n - 2))
      ip = n - 2
    while ip > 0 do
      s2 = ct.encode(w, s2, weights(ip - 1))
      s1 = ct.encode(w, s1, weights(ip - 2))
      ip -= 2
    ct.flush(w, s2)
    ct.flush(w, s1)
    w.close()
    out

  end compressWeights

  /** Encodes `n` literals as one stream, last literal first, so the decoder reads them in order */
  def encodeStream(
      src: Array[Byte],
      from: Int,
      n: Int,
      codes: Array[Int],
      lengths: Array[Int],
      out: ByteBuf
  ): Unit =
    val w = BackwardBitWriter(out)
    var i = from + n - 1
    while i >= from do
      val b = src(i) & 0xff
      w.add(codes(b), lengths(b))
      i -= 1
    w.close()

end HuffmanEncoder
