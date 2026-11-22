package wvlet.uni.parquet.codec

/** Decodes one Huffman-coded literals stream (RFC 8878 section 4.2.2) */
private[codec] object ZstdHuffman:

  /**
    * Decodes `n` literals from `buf[start, end)` into `out(outPos)`. While the stream has at least
    * 8 bytes left below the read position, each reload of the 64-bit container serves 4 symbols of
    * at most 11 bits, with the bit state in locals the JIT keeps in registers. The last symbols go
    * through [[BackwardBitReader]].
    */
  def decode(
      t: HuffmanTable,
      buf: Array[Byte],
      start: Int,
      end: Int,
      out: Array[Byte],
      outPos: Int,
      n: Int
  ): Unit =
    val br        = BackwardBitReader(buf, start, end)
    val symbols   = t.symbols
    val bits      = t.bits
    val shift     = 64 - t.maxBits
    var container = br.container
    var consumed  = br.consumed
    var ptr       = br.ptr
    var i         = 0

    inline def decodeOne(): Unit =
      val idx = ((container << consumed) >>> shift).toInt
      out(outPos + i) = symbols(idx)
      consumed += bits(idx)
      i += 1

    var fast = true
    while fast && i + 4 <= n do
      val bytes = consumed >>> 3
      if ptr - bytes >= start then
        ptr -= bytes
        consumed &= 7
        container = Word8.le64(buf, ptr)
        decodeOne()
        decodeOne()
        decodeOne()
        decodeOne()
      else
        fast = false
    br.container = container
    br.consumed = consumed
    br.ptr = ptr

    while i < n do
      val idx = br.peek(t.maxBits)
      out(outPos + i) = symbols(idx)
      br.skip(bits(idx))
      i += 1
    if br.remaining != 0 then
      Zstd.fail("Huffman stream has the wrong length")

  end decode

end ZstdHuffman
