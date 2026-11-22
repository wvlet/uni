package wvlet.uni.parquet.codec

import wvlet.uni.parquet.ParquetException

/**
  * The deprecated LZ4 codec (id 5). parquet-java wrote it with Hadoop's block framing: a sequence
  * of [4-byte big-endian uncompressed size][4-byte big-endian compressed size][LZ4 block]. A few
  * writers stored a single raw LZ4 block instead, so fall back to that when the framing does not
  * fit.
  */
private[parquet] object Lz4Hadoop:

  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    try
      decodeFramed(src, offset, length, expectedSize)
    catch
      case _: ParquetException =>
        Lz4.decompress(src, offset, length, expectedSize)

  private def intBE(b: Array[Byte], i: Int): Int =
    ((b(i) & 0xff) << 24) | ((b(i + 1) & 0xff) << 16) | ((b(i + 2) & 0xff) << 8) | (b(i + 3) & 0xff)

  private def decodeFramed(
      src: Array[Byte],
      offset: Int,
      length: Int,
      expectedSize: Int
  ): Array[Byte] =
    def fail(msg: String): Nothing = throw ParquetException.corrupt(s"Hadoop LZ4: ${msg}")
    val out                        = new Array[Byte](expectedSize)
    val end                        = offset + length
    var ip                         = offset
    var op                         = 0
    while ip < end do
      if end - ip < 8 then
        fail("frame header is truncated")
      val rawSize  = intBE(src, ip)
      val compSize = intBE(src, ip + 4)
      ip += 8
      if rawSize < 0 || rawSize > expectedSize - op || compSize < 0 || compSize > end - ip then
        fail("frame size is out of range")
      val n = Lz4.decompressInto(src, ip, compSize, out, op, rawSize)
      if n != rawSize then
        fail("frame decoded to the wrong size")
      ip += compSize
      op += rawSize
    if op != expectedSize then
      fail(s"decoded ${op} bytes, expected ${expectedSize}")
    out

  end decodeFramed

end Lz4Hadoop
