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

  private def decompressPage(
      codec: CompressionCodec,
      src: Array[Byte],
      offset: Int,
      length: Int,
      uncompressedSize: Int
  ): Array[Byte] =
    if uncompressedSize < 0 then
      throw ParquetException.corrupt(s"negative uncompressed page size ${uncompressedSize}")
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
