package wvlet.uni.cloud.parquet.codec

import wvlet.uni.io.codec.*
import wvlet.uni.io.FileSystem
import wvlet.uni.io.Gzip
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.format.CompressionCodec

/** Decompresses page data with the codec named in the column chunk metadata */
private[parquet] object Codecs:

  def decompress(
      codec: CompressionCodec,
      src: Array[Byte],
      offset: Int,
      length: Int,
      uncompressedSize: Int
  ): Array[Byte] = ParquetException.fromCodec(
    decompressPage(codec, src, offset, length, uncompressedSize)
  )

  /**
    * Rejects a page whose header claims more bytes than `length` compressed bytes of `codec` can
    * decode to. Decoders allocate the claimed size up front, so without this a few corrupt bytes
    * could ask for 2 GB. The bounds are each format's largest expansion: a 3-byte Snappy copy of 64
    * bytes, a 255-byte LZ4 match per length byte, DEFLATE's 1032:1, and a 4-byte zstd RLE block of
    * 128 KiB. Brotli can expand further than any useful bound, so only its sign is checked.
    */
  def checkUncompressedSize(codec: CompressionCodec, length: Int, uncompressedSize: Int): Unit =
    if uncompressedSize < 0 then
      throw ParquetException.corrupt(s"negative uncompressed page size ${uncompressedSize}")
    val ratio =
      codec match
        case CompressionCodec.SNAPPY =>
          Some(32L)
        case CompressionCodec.LZ4_RAW | CompressionCodec.LZ4 =>
          Some(256L)
        case CompressionCodec.GZIP =>
          Some(1032L)
        case CompressionCodec.ZSTD =>
          Some(32768L)
        case _ =>
          // UNCOMPRESSED pages are checked for an exact length where they are read
          None
    ratio.foreach { r =>
      if uncompressedSize > math.max(0, length).toLong * r + 64 then
        throw ParquetException.corrupt(
          s"a page of ${length} ${codec} bytes cannot hold ${uncompressedSize} uncompressed bytes"
        )
    }

  /** Whether [[decompressInto]] can decode pages of `codec` into a caller's buffer */
  def decodesInto(codec: CompressionCodec): Boolean =
    codec match
      case CompressionCodec.SNAPPY | CompressionCodec.LZ4_RAW | CompressionCodec.ZSTD =>
        true
      case _ =>
        false

  /**
    * Decompresses a page of a codec that [[decodesInto]] covers into `out(0)` to
    * `out(uncompressedSize - 1)`, so one buffer can serve every page of a column. `out` must hold
    * `uncompressedSize` bytes.
    */
  def decompressInto(
      codec: CompressionCodec,
      src: Array[Byte],
      offset: Int,
      length: Int,
      out: Array[Byte],
      uncompressedSize: Int
  ): Unit = ParquetException.fromCodec {
    checkUncompressedSize(codec, length, uncompressedSize)
    codec match
      case CompressionCodec.SNAPPY =>
        Snappy.decompressInto(src, offset, length, out, uncompressedSize)
      case CompressionCodec.LZ4_RAW =>
        val n = Lz4.decompressInto(src, offset, length, out, 0, uncompressedSize)
        if n != uncompressedSize then
          throw ParquetException.corrupt(s"LZ4: decoded ${n} bytes, expected ${uncompressedSize}")
      case CompressionCodec.ZSTD =>
        Zstd.decompressInto(src, offset, length, out, uncompressedSize)
      case other =>
        throw IllegalArgumentException(s"${other} pages are not decoded into a buffer")
  }

  private def decompressPage(
      codec: CompressionCodec,
      src: Array[Byte],
      offset: Int,
      length: Int,
      uncompressedSize: Int
  ): Array[Byte] =
    checkUncompressedSize(codec, length, uncompressedSize)
    codec match
      case CompressionCodec.UNCOMPRESSED =>
        if length != uncompressedSize then
          throw ParquetException.corrupt(
            s"uncompressed page has ${length} bytes, but its header says ${uncompressedSize}"
          )
        java.util.Arrays.copyOfRange(src, offset, offset + length)
      case CompressionCodec.SNAPPY =>
        Snappy.decompress(src, offset, length, uncompressedSize)
      case CompressionCodec.LZ4_RAW =>
        Lz4.decompress(src, offset, length, uncompressedSize)
      case CompressionCodec.GZIP =>
        val in  = java.util.Arrays.copyOfRange(src, offset, offset + length)
        val out =
          if FileSystem.isBrowser then
            Inflate.gunzip(in, uncompressedSize)
          else
            try
              Gzip.decompress(in)
            catch
              case e: java.io.IOException =>
                throw ParquetException(s"Corrupt Parquet data: gzip: ${e.getMessage}", e)
        if out.length != uncompressedSize then
          throw ParquetException.corrupt(
            s"gzip page decoded to ${out.length} bytes, but its header says ${uncompressedSize}"
          )
        out
      case CompressionCodec.ZSTD =>
        Zstd.decompress(src, offset, length, uncompressedSize)
      case CompressionCodec.BROTLI =>
        Brotli.decompress(src, offset, length, uncompressedSize)
      case CompressionCodec.LZ4 =>
        // The deprecated LZ4 codec: Hadoop framing on most writers, a raw block on a few
        Lz4Hadoop.decompress(src, offset, length, uncompressedSize)
      case CompressionCodec.LZO =>
        throw ParquetException.unsupported("the LZO codec")
    end match

  end decompressPage

end Codecs
