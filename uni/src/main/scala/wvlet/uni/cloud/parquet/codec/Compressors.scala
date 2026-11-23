package wvlet.uni.cloud.parquet.codec

import wvlet.uni.io.codec.*
import wvlet.uni.io.FileSystem
import wvlet.uni.io.Gzip
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.io.codec.ByteBuf

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

  /** True when the codec takes a compression level */
  def hasLevels(codec: CompressionCodec): Boolean = codec == CompressionCodec.ZSTD

  def check(codec: CompressionCodec, level: Option[Int] = None): Unit =
    level.foreach { l =>
      if !hasLevels(codec) then
        throw ParquetException.unsupported(s"a compression level for the ${codec} codec")
      if l < ZstdCompressor.MinLevel || l > ZstdCompressor.MaxLevel then
        throw IllegalArgumentException(
          s"ZSTD level must be ${ZstdCompressor.MinLevel} to ${ZstdCompressor.MaxLevel}: ${l}"
        )
    }
    if !isSupported(codec) then
      val where =
        if codec == CompressionCodec.GZIP then
          " in browsers"
        else
          ""
      throw ParquetException.unsupported(s"writing the ${codec} codec${where}")

  /** Compresses `src[0, len)`; `level` is used by ZSTD, with None for its default */
  def compress(
      codec: CompressionCodec,
      src: Array[Byte],
      len: Int,
      level: Option[Int] = None
  ): Array[Byte] = ParquetException.fromCodec(compressPage(codec, src, len, level))

  private def compressPage(
      codec: CompressionCodec,
      src: Array[Byte],
      len: Int,
      level: Option[Int]
  ): Array[Byte] =
    codec match
      case CompressionCodec.UNCOMPRESSED =>
        java.util.Arrays.copyOf(src, len)
      case CompressionCodec.SNAPPY =>
        SnappyCompressor.compress(src, len)
      case CompressionCodec.LZ4_RAW =>
        Lz4Compressor.compress(src, len)
      case CompressionCodec.ZSTD =>
        ZstdCompressor.compress(src, len, level.getOrElse(ZstdCompressor.DefaultLevel))
      case CompressionCodec.GZIP =>
        Gzip.compress(java.util.Arrays.copyOf(src, len))
      case other =>
        throw ParquetException.unsupported(s"writing the ${other} codec")

end Compressors
