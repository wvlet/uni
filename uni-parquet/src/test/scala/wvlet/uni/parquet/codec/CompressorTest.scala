package wvlet.uni.parquet.codec

import wvlet.uni.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.parquet.encoding.RleEncoder
import wvlet.uni.parquet.io.ByteBuf
import wvlet.uni.test.UniTest

class CompressorTest extends UniTest:
  private val rnd = scala.util.Random(42)

  private val inputs: Seq[(String, Array[Byte])] = Seq(
    "empty"      -> Array.emptyByteArray,
    "one byte"   -> Array[Byte](7),
    "short"      -> "abcabcabcabcabcabc".getBytes("UTF-8"),
    "random"     -> Array.fill(70000)(rnd.nextInt(256).toByte),
    "zeros"      -> new Array[Byte](200000),
    "text"       -> (0 until 20000).map(i => s"word${i % 97} ").mkString.getBytes("UTF-8"),
    "long match" -> (
      Array.fill(100)(rnd.nextInt(256).toByte) ++ new Array[Byte](100000) ++
        Array.fill(13)(1.toByte)
    )
  )

  for (name, data) <- inputs do
    test(s"snappy round trip: ${name}") {
      val c = SnappyCompressor.compress(data, data.length)
      Snappy.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
    }

    test(s"lz4 round trip: ${name}") {
      val c = Lz4Compressor.compress(data, data.length)
      Lz4.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
    }

  test("compressors shrink repetitive data") {
    val data = new Array[Byte](100000)
    (SnappyCompressor.compress(data, data.length).length < 6000) shouldBe true
    (Lz4Compressor.compress(data, data.length).length < 1000) shouldBe true
  }

  test("RLE/bit-packing hybrid round trip") {
    for bitWidth <- Seq(1, 3, 8, 13, 31) do
      val max    = (1 << bitWidth) - 1
      val values =
        Array.tabulate(1003) { i =>
          if i % 100 < 40 then
            1
          else
            rnd.nextInt(max)
        }
      val out = ByteBuf()
      RleEncoder.encode(values, values.length, bitWidth, out)
      val bytes   = out.toByteArray
      val decoded = new Array[Int](values.length)
      RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth).read(decoded, 0, decoded.length)
      decoded.toSeq shouldBe values.toSeq
  }

end CompressorTest
