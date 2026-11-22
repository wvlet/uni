package wvlet.uni.io.codec

import wvlet.uni.test.UniTest

/** The pure-Scala Snappy codec against snappy-java, the implementation parquet-java uses */
class SnappyJavaTest extends UniTest:

  private val inputs: Seq[(String, Array[Byte])] =
    val r = scala.util.Random(11)
    Seq(
      "empty"  -> Array.emptyByteArray,
      "short"  -> "abcabcabcabcabcabcabc".getBytes("UTF-8"),
      "random" -> Array.fill(100000)(r.nextInt(256).toByte),
      "zeros"  -> new Array[Byte](300000),
      "text"   -> (0 until 50000).map(i => s"key${i % 211}=${i % 7};").mkString.getBytes("UTF-8"),
      "ints"   -> Array.tabulate(400000)(i => ((i >>> 2) * 37 >>> ((i & 3) * 8)).toByte),
      "runs"   -> Array.tabulate(200000)(i => ((i / 300) % 5).toByte),
      // Matches farther back than a 2-byte offset reaches
      "far" -> {
        val block = Array.fill(70000)(r.nextInt(256).toByte)
        block ++ block ++ block
      }
    )

  for (name, data) <- inputs do
    test(s"snappy-java reads uni output: ${name}") {
      val c = SnappyCompressor.compress(data, data.length)
      org.xerial.snappy.Snappy.uncompress(c).toSeq shouldBe data.toSeq
    }

    test(s"uni reads snappy-java output: ${name}") {
      val c = org.xerial.snappy.Snappy.compress(data)
      Snappy.decompress(c, 0, c.length, data.length).toSeq shouldBe data.toSeq
    }

end SnappyJavaTest
