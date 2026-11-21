package wvlet.uni.parquet.codec

/**
  * A Snappy block compressor. The output is the raw Snappy format (a varint length, then literals
  * and copies) that Parquet stores in pages.
  *
  * It follows the reference compressor's main loop: a hash table of 4-byte sequences, a lookup
  * stride that grows while no match turns up (so incompressible input passes quickly), and after a
  * copy an immediate check for another copy at the next position. Unlike the reference, it doesn't
  * split the input into 64 KiB fragments: the table holds absolute positions, and a candidate
  * farther back than a 2-byte offset reaches is skipped, so one table serves the whole page.
  */
private[parquet] object SnappyCompressor:
  import MatchTable.HashBits
  import MatchTable.hash

  private final val MaxOffset = 65535
  // Matches start at least this many bytes before the end, so the loop's word loads (8 bytes on
  // Scala Native) stay inside the input
  private final val InputMargin = 15
  // A literal up to this length is written as one 16-byte wide copy into the output slack
  private final val ShortLiteral = 16

  /** The largest output for `len` input bytes, as the reference defines it, plus copy slack */
  private def maxCompressedLength(len: Int): Int = 32 + len + len / 6 + ShortLiteral

  def compress(src: Array[Byte], len: Int): Array[Byte] =
    val out = new Array[Byte](maxCompressedLength(len))
    var op  = writeVarInt(out, 0, len)
    op = compressBlock(src, len, out, op)
    java.util.Arrays.copyOf(out, op)

  private def writeVarInt(out: Array[Byte], pos: Int, value: Int): Int =
    var v  = value
    var op = pos
    while (v & ~0x7f) != 0 do
      out(op) = ((v & 0x7f) | 0x80).toByte
      v >>>= 7
      op += 1
    out(op) = v.toByte
    op + 1

  private def compressBlock(src: Array[Byte], len: Int, out: Array[Byte], start: Int): Int =
    var op  = start
    var lit = 0 // start of the pending literal bytes
    if len >= InputMargin + 1 then
      // Zero-filled: position 0 is a real candidate, and every hit is checked against the input
      val table = new Array[Int](1 << HashBits)
      val limit = len - InputMargin
      var i     = 1
      var done  = false
      while !done do
        // Search for a match, looking up fewer positions the longer none turns up
        var skip      = 32
        var cand      = 0
        var found     = false
        var next      = i
        var nextValue = Word8.le32(src, i)
        while !found && !done do
          i = next
          val v = nextValue
          next = i + (skip >>> 5)
          skip += 1
          if next > limit then
            done = true
          else
            nextValue = Word8.le32(src, next)
            val h = hash(v)
            cand = table(h)
            table(h) = i
            found = i - cand <= MaxOffset && Word8.le32(src, cand) == v
        if found then
          op = emitLiteral(src, lit, i - lit, out, op)
          // Emit copies for as long as the position after a copy starts another one
          var more = true
          while more do
            val m = 4 + Word8.matchLength(src, cand + 4, i + 4, len)
            op = emitCopy(out, op, i - cand, m)
            i += m
            lit = i
            if i >= limit then
              done = true
              more = false
            else
              // Index the last position of the copy and look for a copy at the next one
              table(hash(Word8.le32(src, i - 1))) = i - 1
              val v = Word8.le32(src, i)
              val h = hash(v)
              cand = table(h)
              table(h) = i
              more = i - cand <= MaxOffset && Word8.le32(src, cand) == v
          end while
          i += 1
        end if
      end while
    end if
    emitLiteral(src, lit, len - lit, out, op)

  end compressBlock

  private def emitLiteral(src: Array[Byte], from: Int, n: Int, out: Array[Byte], pos: Int): Int =
    var op = pos
    if n > 0 then
      val l = n - 1
      if l < 60 then
        out(op) = (l << 2).toByte
        op += 1
        if n <= ShortLiteral then
          // The output has slack for 16 bytes, and the next tag overwrites what lands past n
          WideCopy.literal(src, from, out, op, n)
          return op + n
      else if l < (1 << 8) then
        out(op) = (60 << 2).toByte
        out(op + 1) = l.toByte
        op += 2
      else if l < (1 << 16) then
        out(op) = (61 << 2).toByte
        out(op + 1) = l.toByte
        out(op + 2) = (l >>> 8).toByte
        op += 3
      else if l < (1 << 24) then
        out(op) = (62 << 2).toByte
        out(op + 1) = l.toByte
        out(op + 2) = (l >>> 8).toByte
        out(op + 3) = (l >>> 16).toByte
        op += 4
      else
        out(op) = (63 << 2).toByte
        out(op + 1) = l.toByte
        out(op + 2) = (l >>> 8).toByte
        out(op + 3) = (l >>> 16).toByte
        out(op + 4) = (l >>> 24).toByte
        op += 5
      System.arraycopy(src, from, out, op, n)
      op += n
    end if
    op

  end emitLiteral

  private def emitCopy(out: Array[Byte], pos: Int, offset: Int, length: Int): Int =
    var op  = pos
    var len = length
    // A copy with a 2-byte offset holds 1 to 64 bytes; keep the last piece at least 4 bytes long
    if len >= 68 then
      // A long match (a run in a page) becomes identical 64-byte copies: write one, then double
      // the written tags, since one array copy beats many 3-byte writes
      val count = (len - 68) / 64 + 1
      val bytes = 3 * count
      copy2(out, op, offset, 64)
      var written = 3
      while written < bytes do
        val n = math.min(written, bytes - written)
        System.arraycopy(out, op, out, op + written, n)
        written += n
      op += bytes
      len -= 64 * count
    if len > 64 then
      op = copy2(out, op, offset, 60)
      len -= 60
    if len <= 11 && offset < 2048 then
      out(op) = (((offset >>> 8) << 5) | ((len - 4) << 2) | 1).toByte
      out(op + 1) = offset.toByte
      op + 2
    else
      copy2(out, op, offset, len)

  private inline def copy2(out: Array[Byte], op: Int, offset: Int, n: Int): Int =
    out(op) = (((n - 1) << 2) | 2).toByte
    out(op + 1) = offset.toByte
    out(op + 2) = (offset >>> 8).toByte
    op + 3

end SnappyCompressor
