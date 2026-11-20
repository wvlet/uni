package wvlet.uni.parquet.codec

import wvlet.uni.io.FileSystem
import wvlet.uni.io.Gzip
import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.format.CompressionCodec
import wvlet.uni.parquet.io.ByteBuf

/** Compresses page data for the writer */
private[parquet] object Compressors:

  /** True when this platform can write the codec */
  def isSupported(codec: CompressionCodec): Boolean =
    codec match
      case CompressionCodec.UNCOMPRESSED | CompressionCodec.SNAPPY | CompressionCodec.LZ4_RAW |
          CompressionCodec.ZSTD =>
        true
      case CompressionCodec.GZIP =>
        !FileSystem.isBrowser
      case _ =>
        false

  def check(codec: CompressionCodec): Unit =
    if !isSupported(codec) then
      val where =
        if codec == CompressionCodec.GZIP then
          " in browsers"
        else
          ""
      throw ParquetException.unsupported(s"writing the ${codec} codec${where}")

  def compress(codec: CompressionCodec, src: Array[Byte], len: Int): Array[Byte] =
    codec match
      case CompressionCodec.UNCOMPRESSED =>
        java.util.Arrays.copyOf(src, len)
      case CompressionCodec.SNAPPY =>
        SnappyCompressor.compress(src, len)
      case CompressionCodec.LZ4_RAW =>
        Lz4Compressor.compress(src, len)
      case CompressionCodec.ZSTD =>
        ZstdCompressor.compress(src, len)
      case CompressionCodec.GZIP =>
        Gzip.compress(java.util.Arrays.copyOf(src, len))
      case other =>
        throw ParquetException.unsupported(s"writing the ${other} codec")

end Compressors

/** Shared hash table for the LZ77-style compressors */
private object MatchTable:
  final val HashBits = 14

  inline def load32(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  inline def hash(v: Int): Int = (v * 0x1e35a7bd) >>> (32 - HashBits)

  /** The length of the match between positions `a` and `b`, up to `limit` (exclusive) for `b` */
  def matchLength(src: Array[Byte], a: Int, b: Int, limit: Int): Int =
    var n = 0
    while b + n < limit && src(a + n) == src(b + n) do
      n += 1
    n

/**
  * A Snappy block compressor: greedy matching with a hash of 4-byte sequences. The output is the
  * raw Snappy format (a varint length, then literals and copies) that Parquet stores in pages.
  */
private[parquet] object SnappyCompressor:
  import MatchTable.*

  private final val MaxOffset = 65535

  def compress(src: Array[Byte], len: Int): Array[Byte] =
    val out = ByteBuf(len / 2 + 16)
    out.writeUnsignedVarInt(len)
    val table   = new Array[Int](1 << HashBits)
    var lit     = 0 // start of pending literal bytes
    var i       = 0
    val inLimit = len - 4
    java.util.Arrays.fill(table, -1)
    while i <= inLimit do
      val v    = load32(src, i)
      val h    = hash(v)
      val cand = table(h)
      table(h) = i
      if cand >= 0 && i - cand <= MaxOffset && load32(src, cand) == v then
        val m = 4 + matchLength(src, cand + 4, i + 4, len)
        emitLiteral(src, lit, i - lit, out)
        emitCopy(i - cand, m, out)
        i += m
        lit = i
      else
        i += 1
    emitLiteral(src, lit, len - lit, out)
    out.toByteArray

  private def emitLiteral(src: Array[Byte], from: Int, n: Int, out: ByteBuf): Unit =
    if n > 0 then
      val l = n - 1
      if l < 60 then
        out.writeByte(l << 2)
      else if l < (1 << 8) then
        out.writeByte(60 << 2)
        out.writeByte(l)
      else if l < (1 << 16) then
        out.writeByte(61 << 2)
        out.writeByte(l)
        out.writeByte(l >>> 8)
      else if l < (1 << 24) then
        out.writeByte(62 << 2)
        out.writeByte(l)
        out.writeByte(l >>> 8)
        out.writeByte(l >>> 16)
      else
        out.writeByte(63 << 2)
        out.writeIntLE(l)
      out.write(src, from, n)

  private def emitCopy(offset: Int, length: Int, out: ByteBuf): Unit =
    var remaining = length
    // Copies with a 2-byte offset hold 1 to 64 bytes; keep the last piece at least 4 bytes long
    while remaining > 0 do
      val n =
        if remaining > 64 && remaining < 68 then
          60
        else
          math.min(remaining, 64)
      if n >= 4 && n <= 11 && offset < 2048 then
        out.writeByte(((offset >>> 8) << 5) | ((n - 4) << 2) | 1)
        out.writeByte(offset)
      else
        out.writeByte(((n - 1) << 2) | 2)
        out.writeByte(offset)
        out.writeByte(offset >>> 8)
      remaining -= n

end SnappyCompressor

/**
  * An LZ4 block compressor (the LZ4_RAW codec). Follows the block format's end-of-block rules: the
  * last 5 bytes are literals, and no match starts in the last 12 bytes.
  */
private[parquet] object Lz4Compressor:
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
        val m = MinMatch + matchLength(src, cand + MinMatch, i + MinMatch, matchLimit)
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
