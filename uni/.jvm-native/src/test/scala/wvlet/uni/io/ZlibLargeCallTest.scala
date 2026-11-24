package wvlet.uni.io

import wvlet.uni.test.UniTest

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/**
  * Single zlib calls over 64 KiB. Before Scala Native 0.5.13 (scala-native#5269), javalib on
  * Windows declared zlib's `avail_in` and `avail_out` as 16-bit, so these calls compressed only a
  * prefix or never returned. Uni still feeds zlib 8 KiB at a time; this test guards the platform.
  */
class ZlibLargeCallTest extends UniTest:
  // Pseudo-random, so the compressed form is past 64 KiB too
  private val body = Array.tabulate[Byte](300000)(i => scala.util.Random(i).nextInt().toByte)

  test("Deflater and Inflater take more than 64 KiB in one call") {
    val d = Deflater()
    d.setInput(body)
    d.finish()
    val compressed = Array.ofDim[Byte](body.length + 1024)
    val clen       = d.deflate(compressed)
    d.finished shouldBe true
    d.end()

    val inf = Inflater()
    inf.setInput(compressed, 0, clen)
    val out = Array.ofDim[Byte](body.length)
    inf.inflate(out) shouldBe body.length
    inf.finished shouldBe true
    inf.end()
    out shouldBe body
  }

  test("GZIPOutputStream writes more than 64 KiB in one call") {
    val buf = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(buf)
    gz.write(body)
    gz.close()
    val in = GZIPInputStream(java.io.ByteArrayInputStream(buf.toByteArray))
    try in.readAllBytes() shouldBe body
    finally in.close()
  }

end ZlibLargeCallTest
