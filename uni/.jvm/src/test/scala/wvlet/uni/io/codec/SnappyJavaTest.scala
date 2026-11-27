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

  test("Snappy decodes with snappy-java when it is on the classpath") {
    SnappyJava.available shouldBe true
  }

  test("the snappy-java path leaves bytes of a longer buffer past expectedSize as they were") {
    val data = inputs.find(_._1 == "text").get._2
    val c    = SnappyCompressor.compress(data, data.length)
    val out  = Array.fill[Byte](data.length + 100)(0x5a.toByte)
    Snappy.decompressInto(c, 0, c.length, out, data.length)
    out.take(data.length).toSeq shouldBe data.toSeq
    out.drop(data.length).forall(_ == 0x5a.toByte) shouldBe true
  }

  test("the snappy-java path reads a stream inside a longer array") {
    val data   = inputs.find(_._1 == "ints").get._2
    val c      = SnappyCompressor.compress(data, data.length)
    val padded = Array.fill[Byte](9)(1) ++ c ++ Array.fill[Byte](30)(2)
    Snappy.decompress(padded, 9, c.length, data.length).toSeq shouldBe data.toSeq
  }

  test("the snappy-java path rejects corrupt streams with CodecException") {
    val data = inputs.find(_._1 == "text").get._2
    val c    = SnappyCompressor.compress(data, data.length)
    // A stated length other than the expected one
    intercept[CodecException] {
      Snappy.decompress(c, 0, c.length, data.length - 1)
    }
    // The input cut short
    intercept[CodecException] {
      Snappy.decompress(c, 0, c.length / 2, data.length)
    }
    // A copy before the start of the output: 4 bytes from offset 1 with no output yet
    val bad = Array[Byte](4, 1, 1)
    intercept[CodecException] {
      Snappy.decompress(bad, 0, bad.length, 4)
    }
    // An empty input has no length preamble
    intercept[CodecException] {
      Snappy.decompress(c, 0, 0, data.length)
    }
  }

  test("the snappy-java path checks the input range and the output size") {
    val data = "abcabcabcabc".getBytes("UTF-8")
    val c    = SnappyCompressor.compress(data, data.length)
    intercept[IndexOutOfBoundsException] {
      Snappy.decompress(c, 1, c.length, data.length)
    }
    intercept[IllegalArgumentException] {
      Snappy.decompressInto(c, 0, c.length, new Array[Byte](5), data.length)
    }
  }

end SnappyJavaTest
