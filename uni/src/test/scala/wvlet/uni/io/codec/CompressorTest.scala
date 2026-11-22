package wvlet.uni.io.codec

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

  test("zstd round trip at every level") {
    // The codec is shared Scala code, so the JVM covers every level. Native tests run in a debug
    // build, where all 22 levels take ~14 seconds. There, check lazy matching (3) and the
    // shallowest and deepest hash-chain search (4, 9): 1-2 compress as 3, and 10-22 as 9.
    val levels =
      if isScalaNative then
        Seq(3, 4, 9)
      else
        ZstdCompressor.MinLevel to ZstdCompressor.MaxLevel
    for
      level     <- levels
      (_, data) <- inputs
    do
      val c = ZstdCompressor.compress(data, data.length, level)
      Zstd.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
  }

  test("higher zstd levels compress more") {
    if isScalaNative then
      skip("Compression ratios come from shared Scala code, which the JVM checks")
    for name <- Seq("text", "multi-block text", "binary ints") do
      val data             = inputs.find(_._1 == name).get._2
      def size(level: Int) = ZstdCompressor.compress(data, data.length, level).length
      val (l3, l6, l9)     = (size(3), size(6), size(9))
      debug(s"${name}: level 3 ${l3}, 6 ${l6}, 9 ${l9}")
      (l9 <= l3) shouldBe true
      // Neighboring levels can swap by a few percent, as zstd's do; the trend holds on larger
      // inputs
      if name == "multi-block text" then
        (l6 < l3) shouldBe true
      // Levels 10-22 compress as 9, and 1-2 as 3
      size(22) shouldBe l9
      size(1) shouldBe l3
  }

  test("reject zstd levels out of range") {
    intercept[IllegalArgumentException] {
      ZstdCompressor.compress(Array[Byte](1, 2, 3), 3, 0)
    }
    intercept[IllegalArgumentException] {
      ZstdCompressor.compress(Array[Byte](1, 2, 3), 3, 23)
    }
  }

  test("the backward bit writer round trips through the bit reader") {
    val r = scala.util.Random(3)
    for round <- 0 until 50 do
      val fields =
        Seq.fill(r.nextInt(400)) {
          val n = r.nextInt(33)
          (r.nextLong() & ((1L << n) - 1), n)
        }
      val out = ByteBuf()
      val w   = BackwardBitWriter(out, sizeHint = 1) // starts small, so the buffer grows
      fields.foreach((v, n) => w.add(v, n))
      w.close()
      val bytes = out.toByteArray
      val br    = BackwardBitReader(bytes, 0, bytes.length)
      // The last field written is the first read
      fields.reverse.foreach((v, n) => br.read(n).toLong & 0xffffffffL shouldBe v)
      br.remaining shouldBe 0L
  }

  test("zstd frames span more than the window") {
    // Over 2 MiB: a frame with a window descriptor instead of a single segment
    val data = Array.tabulate(3 * 1024 * 1024 + 17)(i => ((i / 7) % 251 + (i >>> 20)).toByte)
    val c    = ZstdCompressor.compress(data, data.length)
    Zstd.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
  }

end CompressorTest
