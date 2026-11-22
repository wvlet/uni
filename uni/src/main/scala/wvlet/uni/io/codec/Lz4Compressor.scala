package wvlet.uni.io.codec

/** Shared hash table for the LZ77-style compressors */
private[codec] object MatchTable:
  final val HashBits = 14

  inline def load32(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  inline def hash(v: Int): Int = (v * 0x1e35a7bd) >>> (32 - HashBits)

/**
  * An LZ4 block compressor (the LZ4_RAW codec). Follows the block format's end-of-block rules: the
  * last 5 bytes are literals, and no match starts in the last 12 bytes.
  */
private[uni] object Lz4Compressor:
  import MatchTable.*

  private final val MinMatch     = 4
  private final val LastLiterals = 5
  private final val MfLimit      = 12
  private final val MaxOffset    = 65535

  def compress(src: Array[Byte], len: Int): Array[Byte] =
    val out        = ByteBuf(len / 2 + 16)
    val table      = new Array[Int](1 << HashBits)
    val matchLimit = len - LastLiterals
    var lit        = 0
    var i          = 0
    java.util.Arrays.fill(table, -1)
    while i < len - MfLimit do
      val v    = load32(src, i)
      val h    = hash(v)
      val cand = table(h)
      table(h) = i
      if cand >= 0 && i - cand <= MaxOffset && load32(src, cand) == v then
        val m = MinMatch + Word8.matchLength(src, cand + MinMatch, i + MinMatch, matchLimit)
        sequence(src, lit, i - lit, i - cand, m, out)
        i += m
        lit = i
      else
        i += 1
    // The last sequence has literals only
    val n = len - lit
    token(n, 0, out)
    if n >= 15 then
      extraLength(n - 15, out)
    out.write(src, lit, n)
    out.toByteArray

  private def token(litLen: Int, matchCode: Int, out: ByteBuf): Unit = out.writeByte(
    (math.min(litLen, 15) << 4) | math.min(matchCode, 15)
  )

  private def extraLength(n: Int, out: ByteBuf): Unit =
    var r = n
    while r >= 255 do
      out.writeByte(255)
      r -= 255
    out.writeByte(r)

  private def sequence(
      src: Array[Byte],
      lit: Int,
      litLen: Int,
      offset: Int,
      matchLen: Int,
      out: ByteBuf
  ): Unit =
    val code = matchLen - MinMatch
    token(litLen, code, out)
    if litLen >= 15 then
      extraLength(litLen - 15, out)
    out.write(src, lit, litLen)
    out.writeByte(offset)
    out.writeByte(offset >>> 8)
    if code >= 15 then
      extraLength(code - 15, out)

end Lz4Compressor
