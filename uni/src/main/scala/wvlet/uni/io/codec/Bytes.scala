package wvlet.uni.io.codec

/**
  * Fixed-width integer reads from a byte array, shared by the Parquet reader and the Arrow writer
  */
private[uni] object Bytes:
  inline def intLE(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  inline def longLE(b: Array[Byte], i: Int): Long =
    (intLE(b, i).toLong & 0xffffffffL) | (intLE(b, i + 4).toLong << 32)

  inline def longBE(b: Array[Byte], i: Int): Long =
    var v = 0L
    var k = 0
    while k < 8 do
      v = (v << 8) | (b(i + k) & 0xffL)
      k += 1
    v
