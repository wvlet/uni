package wvlet.uni.parquet.codec

import wvlet.uni.parquet.ParquetException

private[parquet] object Inflate:
  def gunzip(src: Array[Byte], expectedSize: Int): Array[Byte] =
    throw ParquetException.unsupported("gzip pages in browsers (not yet implemented)")

private[parquet] object Brotli:
  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    throw ParquetException.unsupported("the BROTLI codec (not yet implemented)")
