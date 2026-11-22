package wvlet.uni.parquet.codec

import wvlet.uni.test.UniTest

/** Edge cases of the Snappy compressor's match search and tag encoding */
class SnappyCompressorTest extends UniTest:

  private def roundTrip(data: Array[Byte]): Array[Byte] =
    val c = SnappyCompressor.compress(data, data.length)
    Snappy.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
    c

  test("every length up to 64 bytes") {
    val r = scala.util.Random(1)
    for n <- 0 to 64 do
      roundTrip(Array.fill(n)(r.nextInt(3).toByte))
      roundTrip(new Array[Byte](n))
  }

  test("compresses only the first len bytes of a larger buffer") {
    val data = Array.tabulate(1000)(i => (i % 10).toByte)
    val c    = SnappyCompressor.compress(data, 500)
    Snappy.decompress(c, 0, c.length, 500).toSeq shouldBe data.take(500).toSeq
  }

  test("matches of each length around the 64-byte copy limit") {
    val r = scala.util.Random(2)
    for len <- (4 to 140) ++ Seq(191, 192, 193, 4096, 70000) do
      val head = Array.fill(40)(r.nextInt(256).toByte)
      // The head repeats once (a match of `len` bytes), then random bytes stop it
      val data =
        head ++ Array.tabulate(len)(i => head(i % head.length)) ++
          Array.fill(30)(r.nextInt(256).toByte)
      roundTrip(data)
  }

  test("back-to-back short copies, up to the end of the input") {
    // Words of 4 to 14 bytes from a small vocabulary: most copies start right where the last
    // one ended, and some run into the input's last bytes
    val r     = scala.util.Random(4)
    val vocab = Seq.fill(12)(Array.fill(4 + r.nextInt(11))(r.nextInt(256).toByte))
    for n <- (16 to 80) ++ Seq(1000, 5000) do
      val data = Iterator.continually(vocab(r.nextInt(vocab.length))).flatten.take(n).toArray
      roundTrip(data)
  }

  test("offsets at and past the 2-byte limit") {
    val r = scala.util.Random(3)
    for gap <- Seq(2047, 2048, 65000, 65535 - 16, 65535, 65536, 70000) do
      val word = Array.fill(16)(r.nextInt(256).toByte)
      val data =
        word ++ Array.fill(gap - 16)(r.nextInt(256).toByte) ++ word ++
          Array.fill(20)(r.nextInt(256).toByte)
      roundTrip(data)
  }

  test("random structured inputs") {
    val r = scala.util.Random(7)
    for round <- 0 until 300 do
      val n     = r.nextInt(20000)
      val alpha = 1 + r.nextInt(255)
      val data  = new Array[Byte](n)
      var i     = 0
      while i < n do
        if i > 8 && r.nextInt(4) == 0 then
          // Copy an earlier run, sometimes overlapping itself
          val off = 1 + r.nextInt(math.min(i, 3000))
          val len = math.min(n - i, 1 + r.nextInt(200))
          var k   = 0
          while k < len do
            data(i + k) = data(i + k - off)
            k += 1
          i += len
        else
          data(i) = r.nextInt(alpha).toByte
          i += 1
      roundTrip(data)
  }

  test("incompressible input stays within the worst-case size") {
    val r    = scala.util.Random(4)
    val data = Array.fill(300000)(r.nextInt(256).toByte)
    val c    = roundTrip(data)
    (c.length <= 32 + data.length + data.length / 6) shouldBe true
  }

  test("long runs shrink to about 3 bytes per 64") {
    val c = roundTrip(new Array[Byte](1 << 20))
    (c.length < (1 << 20) / 64 * 3 + 100) shouldBe true
  }

end SnappyCompressorTest
