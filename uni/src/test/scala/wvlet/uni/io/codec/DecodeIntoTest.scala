package wvlet.uni.io.codec

import wvlet.uni.test.UniTest

/**
  * Codecs that decode into a caller's buffer, which may be longer than the output and hold bytes of
  * an earlier page, and the bulk little-endian reads of PLAIN Parquet values
  */
class DecodeIntoTest extends UniTest:
  private val rnd = scala.util.Random(42)

  // Compressible, with runs and repeats, so the decoders copy matches as well as literals
  private def input(n: Int): Array[Byte] =
    Array.tabulate[Byte](n) { i =>
      if i % 97 < 40 then
        (i % 7).toByte
      else
        rnd.nextInt(256).toByte
    }

  private def dirty(n: Int): Array[Byte] = Array.fill[Byte](n)(0x5a.toByte)

  test("Snappy decodes into a longer, reused buffer") {
    val out = dirty(200_000)
    Seq(100_000, 3, 150_000, 0).foreach { n =>
      val src = input(n)
      val c   = SnappyCompressor.compress(src, n)
      Snappy.decompressInto(c, 0, c.length, out, n)
      out.take(n).toSeq shouldBe src.toSeq
    }
  }

  test("ZSTD decodes into a longer, reused buffer") {
    val out = dirty(400_000)
    Seq(300_000, 5, 150_000).foreach { n =>
      val src = input(n)
      val c   = ZstdCompressor.compress(src, n)
      Zstd.decompressInto(c, 0, c.length, out, n)
      out.take(n).toSeq shouldBe src.toSeq
    }
  }

  test("reject a buffer shorter than the output") {
    val src = input(1000)
    val c   = SnappyCompressor.compress(src, src.length)
    intercept[IllegalArgumentException] {
      Snappy.decompressInto(c, 0, c.length, new Array[Byte](999), 1000)
    }
    val z = ZstdCompressor.compress(src, src.length)
    intercept[IllegalArgumentException] {
      Zstd.decompressInto(z, 0, z.length, new Array[Byte](999), 1000)
    }
  }

  test("fail when a page decodes to fewer bytes than expected") {
    val src = input(1000)
    val z   = ZstdCompressor.compress(src, src.length)
    intercept[CodecException] {
      Zstd.decompressInto(z, 0, z.length, dirty(2000), 1500)
    }
  }

  test("read little-endian values in bulk at any offset") {
    val bytes = Array.fill[Byte](1003)(rnd.nextInt(256).toByte)
    Seq(0, 1, 3).foreach { off =>
      val n4 = (bytes.length - off) / 4
      val n8 = (bytes.length - off) / 8

      val ints = new Array[Int](n4 + 2)
      Bytes.intsLE(bytes, off, ints, 2, n4)
      ints.drop(2).toSeq shouldBe (0 until n4).map(i => Bytes.intLE(bytes, off + i * 4))

      val wide = new Array[Long](n4 + 1)
      Bytes.intsAsLongsLE(bytes, off, wide, 1, n4)
      wide.drop(1).toSeq shouldBe (0 until n4).map(i => Bytes.intLE(bytes, off + i * 4).toLong)

      val longs = new Array[Long](n8)
      Bytes.longsLE(bytes, off, longs, 0, n8)
      longs.toSeq shouldBe (0 until n8).map(i => Bytes.longLE(bytes, off + i * 8))

      val floats = new Array[Float](n4)
      Bytes.floatsLE(bytes, off, floats, 0, n4)
      floats.map(java.lang.Float.floatToRawIntBits).toSeq shouldBe
        (0 until n4).map(i =>
          // As the decoder did before; on Scala.js a NaN keeps no payload bits
          java
            .lang
            .Float
            .floatToRawIntBits(java.lang.Float.intBitsToFloat(Bytes.intLE(bytes, off + i * 4)))
        )

      val doubles = new Array[Double](n8)
      Bytes.doublesLE(bytes, off, doubles, 0, n8)
      doubles.map(java.lang.Double.doubleToRawLongBits).toSeq shouldBe
        (0 until n8).map(i =>
          java
            .lang
            .Double
            .doubleToRawLongBits(
              java.lang.Double.longBitsToDouble(Bytes.longLE(bytes, off + i * 8))
            )
        )
    }
  }

  test("check the ranges of bulk reads") {
    val bytes = new Array[Byte](16)
    intercept[IndexOutOfBoundsException] {
      Bytes.longsLE(bytes, 1, new Array[Long](2), 0, 2)
    }
    intercept[IndexOutOfBoundsException] {
      Bytes.intsLE(bytes, 0, new Array[Int](3), 0, 4)
    }
    intercept[IndexOutOfBoundsException] {
      Bytes.intsLE(bytes, -1, new Array[Int](4), 0, 1)
    }
    intercept[IndexOutOfBoundsException] {
      Bytes.doublesLE(bytes, 0, new Array[Double](2), 1, 2)
    }
    // Reads of nothing succeed at the ends
    Bytes.longsLE(bytes, 16, new Array[Long](0), 0, 0)
  }

end DecodeIntoTest
