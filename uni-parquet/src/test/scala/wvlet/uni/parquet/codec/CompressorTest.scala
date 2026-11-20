package wvlet.uni.parquet.codec

import wvlet.uni.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.parquet.encoding.RleEncoder
import wvlet.uni.parquet.io.ByteBuf
import wvlet.uni.test.UniTest

class CompressorTest extends UniTest:
  private val rnd = scala.util.Random(42)

  private val inputs: Seq[(String, Array[Byte])] = Seq(
    "empty"            -> Array.emptyByteArray,
    "one byte"         -> Array[Byte](7),
    "short"            -> "abcabcabcabcabcabc".getBytes("UTF-8"),
    "random"           -> Array.fill(70000)(rnd.nextInt(256).toByte),
    "zeros"            -> new Array[Byte](200000),
    "text"             -> (0 until 20000).map(i => s"word${i % 97} ").mkString.getBytes("UTF-8"),
    "multi-block text" ->
      (0 until 60000).map(i => s"${i % 1013},${(i * 7) % 31};").mkString.getBytes("UTF-8"),
    "binary ints" -> {
      // PLAIN-encoded INT64 values with small deltas: most bytes are above 127
      val b = ByteBuf()
      for i <- 0 until 40000 do
        b.writeLongLE(-1000000L + i * 3 + rnd.nextInt(5))
      b.toByteArray
    },
    "skewed bytes" -> Array.fill(150000)((rnd.nextGaussian() * 6).toInt.toByte),
    "long match"   -> (
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

    test(s"zstd round trip: ${name}") {
      val c = ZstdCompressor.compress(data, data.length)
      Zstd.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
    }

  test("compressors shrink repetitive data") {
    val data = new Array[Byte](100000)
    (SnappyCompressor.compress(data, data.length).length < 6000) shouldBe true
    (Lz4Compressor.compress(data, data.length).length < 1000) shouldBe true
    (ZstdCompressor.compress(data, data.length).length < 100) shouldBe true
  }

  test("zstd compresses better than snappy") {
    for name <- Seq("text", "multi-block text", "binary ints", "skewed bytes") do
      val data   = inputs.find(_._1 == name).get._2
      val zstd   = ZstdCompressor.compress(data, data.length).length
      val snappy = SnappyCompressor.compress(data, data.length).length
      debug(s"${name}: ${data.length} bytes, zstd ${zstd}, snappy ${snappy}")
      (zstd < snappy) shouldBe true
  }

  test("zstd round trip over random structured inputs") {
    val r = scala.util.Random(7)
    for round <- 0 until 200 do
      val n     = r.nextInt(5000)
      val alpha = 1 + r.nextInt(255)
      val data  = new Array[Byte](n)
      var i     = 0
      while i < n do
        if i > 8 && r.nextInt(4) == 0 then
          // Copy an earlier run
          val off = 1 + r.nextInt(math.min(i, 300))
          val len = math.min(n - i, 3 + r.nextInt(40))
          var k   = 0
          while k < len do
            data(i + k) = data(i + k - off)
            k += 1
          i += len
        else
          data(i) = r.nextInt(alpha).toByte
          i += 1
      val c = ZstdCompressor.compress(data, n)
      Zstd.decompress(c, 0, c.length, n).toSeq shouldBe data.toSeq
  }

  test("zstd frames span more than the window") {
    // Over 2 MiB: a frame with a window descriptor instead of a single segment
    val data = Array.tabulate(3 * 1024 * 1024 + 17)(i => ((i / 7) % 251 + (i >>> 20)).toByte)
    val c    = ZstdCompressor.compress(data, data.length)
    Zstd.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
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
