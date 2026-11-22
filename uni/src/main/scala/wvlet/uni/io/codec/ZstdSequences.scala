package wvlet.uni.io.codec

/**
  * FSE tables for the sequences section, packed so one lookup per state gives everything a sequence
  * needs, as zstd's `ZSTD_seqSymbol` does. An entry holds:
  *
  *   - bits 0-15: the next state's base (`FseTable.newState`)
  *   - bits 16-23: the number of state bits to read (`FseTable.nbBits`)
  *   - bits 24-31: the number of extra value bits to read
  *   - bits 32-63: the value's base, as an unsigned 32-bit number
  */
private[codec] object SeqTables:

  inline def nextState(e: Long): Int = (e & 0xffff).toInt
  inline def stateBits(e: Long): Int = ((e >>> 16) & 0xff).toInt
  inline def extraBits(e: Long): Int = ((e >>> 24) & 0xff).toInt
  inline def base(e: Long): Long     = e >>> 32

  def literalLengths(t: FseTable): Array[Long] = packed(t, Zstd.LLBase, Zstd.LLBits)

  def matchLengths(t: FseTable): Array[Long] = packed(t, Zstd.MLBase, Zstd.MLBits)

  /** Offset code c reads c extra bits on top of a base of 2^c */
  def offsets(t: FseTable): Array[Long] = packed(t, OffsetBase, OffsetBits)

  private val OffsetBits: Array[Int] = Array.tabulate(32)(c => c)
  private val OffsetBase: Array[Int] = Array.tabulate(32)(c => 1 << c)

  /** Packs `t`, reusing the entries packed for it by an earlier block (repeat mode) */
  private def packed(t: FseTable, bases: Array[Int], bits: Array[Int]): Array[Long] =
    if t.seqEntries == null then
      val size    = t.symbols.length
      val entries = new Array[Long](size)
      var i       = 0
      while i < size do
        val sym = t.symbols(i)
        if sym >= bases.length then
          Zstd.fail(s"sequence code ${sym} is out of range")
        entries(i) =
          (t.newState(i) & 0xffffL) |
            ((t.nbBits(i) & 0xffL) << 16) |
            ((bits(sym) & 0xffL) << 24) |
            ((bases(sym) & 0xffffffffL) << 32)
        i += 1
      t.seqEntries = entries
    t.seqEntries

end SeqTables

/** Decodes and executes the sequences of one block (RFC 8878 section 3.1.1.3.2) */
private[codec] object ZstdSequences:

  /**
    * Decodes `nbSeq` sequences from `src[start, end)`, copying literals from `literals` and matches
    * within `out` from `outputPos`, and updates the repeat offsets in `rep`.
    *
    * The bit reader's state lives in local variables here, so the JIT keeps it in registers: with
    * it in the reader's fields, every read waits on the previous read's store. Only refills near
    * the stream's start, which are rare, go through the reader.
    *
    * @return
    *   the output position in the low 32 bits and the literals consumed in the high 32 bits
    */
  def decode(
      src: Array[Byte],
      start: Int,
      end: Int,
      nbSeq: Int,
      llTable: FseTable,
      mlTable: FseTable,
      ofTable: FseTable,
      literals: Array[Byte],
      regenSize: Int,
      out: Array[Byte],
      outputPos: Int,
      frameStart: Int,
      rep: Array[Int]
  ): Long =
    val llT       = SeqTables.literalLengths(llTable)
    val mlT       = SeqTables.matchLengths(mlTable)
    val ofT       = SeqTables.offsets(ofTable)
    val br        = BackwardBitReader(src, start, end)
    var container = br.container
    var consumed  = br.consumed
    var ptr       = br.ptr

    // Makes the next n (<= 56) bits readable
    inline def ensure(n: Int): Unit =
      if consumed + n > 64 then
        val bytes = consumed >>> 3
        if ptr - bytes >= start then
          ptr -= bytes
          consumed &= 7
          container = Word8.le64(src, ptr)
        else
          br.container = container
          br.consumed = consumed
          br.ptr = ptr
          br.refill(n)
          container = br.container
          consumed = br.consumed
          ptr = br.ptr

    // Reloads the container so at least 57 bits are readable, without a data-dependent branch
    inline def reload(): Unit =
      val bytes = consumed >>> 3
      if ptr - bytes >= start then
        ptr -= bytes
        consumed &= 7
        container = Word8.le64(src, ptr)
      else
        ensure(57)

    // The next n (<= 56) bits, made readable by ensure
    inline def read(n: Int): Long =
      val v = (container << consumed) >>> 1 >>> (63 - n)
      consumed += n
      v

    ensure(llTable.accuracyLog + ofTable.accuracyLog + mlTable.accuracyLog)
    var llState = read(llTable.accuracyLog).toInt
    var ofState = read(ofTable.accuracyLog).toInt
    var mlState = read(mlTable.accuracyLog).toInt
    var op      = outputPos
    var litPos  = 0
    var s       = 0
    while s < nbSeq do
      val llE = llT(llState)
      val mlE = mlT(mlState)
      val ofE = ofT(ofState)
      // Up to 31 offset bits, then up to 16 + 16 length bits, then up to 9 + 9 + 8 state bits
      val ofBits = SeqTables.extraBits(ofE)
      reload()
      val offsetValue = SeqTables.base(ofE) + read(ofBits)
      val mlBits      = SeqTables.extraBits(mlE)
      val llBits      = SeqTables.extraBits(llE)
      reload()
      val ml = SeqTables.base(mlE).toInt + read(mlBits).toInt
      val ll = SeqTables.base(llE).toInt + read(llBits).toInt
      if s + 1 < nbSeq then
        reload()
        llState = SeqTables.nextState(llE) + read(SeqTables.stateBits(llE)).toInt
        mlState = SeqTables.nextState(mlE) + read(SeqTables.stateBits(mlE)).toInt
        ofState = SeqTables.nextState(ofE) + read(SeqTables.stateBits(ofE)).toInt

      // Resolve repeat offsets
      val offset: Int =
        if offsetValue > 3 then
          val o = offsetValue - 3
          if o > Int.MaxValue then
            Zstd.fail("offset is too large")
          rep(2) = rep(1)
          rep(1) = rep(0)
          rep(0) = o.toInt
          o.toInt
        else
          var idx = offsetValue.toInt
          if ll == 0 then
            idx += 1
          if idx == 1 then
            rep(0)
          else
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
            o

      // Copy literals, then the match
      if ll > regenSize - litPos || ll > out.length - op then
        Zstd.fail("literal length is out of range")
      WideCopy.literal(literals, litPos, out, op, ll)
      litPos += ll
      op += ll
      if offset <= 0 || offset > op - frameStart then
        Zstd.fail(s"match offset ${offset} is out of range")
      if ml > out.length - op then
        Zstd.fail("match exceeds the expected output")
      WideCopy.matchCopy(out, op, offset, ml)
      op += ml
      s += 1
    end while
    br.container = container
    br.consumed = consumed
    br.ptr = ptr
    if br.remaining != 0 then
      Zstd.fail("sequence bitstream has the wrong length")
    (litPos.toLong << 32) | (op & 0xffffffffL)

  end decode

end ZstdSequences
