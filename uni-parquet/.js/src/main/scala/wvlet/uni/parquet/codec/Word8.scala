package wvlet.uni.parquet.codec

/** 8-byte reads from byte arrays; the JVM and Scala Native versions also copy 8-byte words */
private[parquet] object Word8:

  /** The little-endian long at `b(i)` */
  inline def le64(b: Array[Byte], i: Int): Long =
    (Zstd.le32(b, i) & 0xffffffffL) | (Zstd.le32(b, i + 4).toLong << 32)
