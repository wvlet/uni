/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http

import wvlet.uni.test.UniTest

import java.io.{ByteArrayOutputStream, OutputStream}
import java.util.zip.{Deflater, DeflaterOutputStream, GZIPOutputStream}

/**
  * Request body decoding, on every platform, including the Scala Native (Windows) CI job. The
  * bodies past 64 KiB cover the size where javalib on Windows truncated zlib calls before Scala
  * Native 0.5.13 (see RequestDecompression).
  */
class RequestDecompressionTest extends UniTest:
  import RequestDecompressionTest.*

  private def decoded(encoding: String, wire: Array[Byte]): Array[Byte] =
    val request = Request
      .post("/")
      .addHeader(HttpHeader.ContentEncoding, encoding)
      .withBytesContent(wire)
    RequestDecompression.decode(request, 64 * 1024 * 1024) match
      case Right(r) =>
        r.header(HttpHeader.ContentEncoding) shouldBe None
        r.content.toContentBytes
      case Left(response) =>
        fail(s"${encoding} body of ${wire.length} bytes was rejected: ${response.status}")

  test("decode gzip and deflate bodies of several sizes") {
    for
      n                      <- Sizes
      (label, encoding, enc) <- Encoders
    do
      val b = body(n)
      // Name the case in the comparison, so a failure says which one broke
      (label, n, decoded(encoding, enc(b)).toSeq == b.toSeq) shouldBe (label, n, true)
  }

  test("decode every member of a multi-member gzip body") {
    val (a, b, c) = (body(3000), body(90000), body(0))
    decoded("gzip", gzip(a) ++ gzip(b) ++ gzip(c)).toSeq shouldBe (a ++ b ++ c).toSeq
  }

  test("ignore bytes after the last member that don't start a member") {
    val a = body(5000)
    decoded("gzip", gzip(a) ++ Array[Byte](0, 0, 0, 0)).toSeq shouldBe a.toSeq
  }

  test("check the header CRC of a gzip member that has one") {
    val a = body(5000)
    val g = gzip(a)
    // Set FHCRC and add the CRC-16 of the 10-byte header after it
    val header = g.take(10)
    header(3) = 2
    val crc = java.util.zip.CRC32()
    crc.update(header, 0, 10)
    val c                                  = crc.getValue.toInt
    def withHeaderCrc(v: Int): Array[Byte] =
      header ++ Array(v.toByte, (v >> 8).toByte) ++ g.drop(10)
    decoded("gzip", withHeaderCrc(c)).toSeq shouldBe a.toSeq
    val request = Request
      .post("/")
      .addHeader(HttpHeader.ContentEncoding, "gzip")
      .withBytesContent(withHeaderCrc(c ^ 1))
    RequestDecompression.decode(request, 64 * 1024 * 1024) shouldMatch { case Left(r) =>
      r.status shouldBe HttpStatus.BadRequest_400
    }
  }

  test("reject a gzip body with a corrupt trailer or cut short") {
    val wire    = gzip(body(20000))
    val corrupt = wire.clone()
    corrupt(corrupt.length - 8) = (corrupt(corrupt.length - 8) ^ 1).toByte
    Seq(corrupt, wire.take(wire.length - 4), wire.take(wire.length / 2)).foreach { w =>
      val request = Request
        .post("/")
        .addHeader(HttpHeader.ContentEncoding, "gzip")
        .withBytesContent(w)
      RequestDecompression.decode(request, 64 * 1024 * 1024) shouldMatch { case Left(r) =>
        r.status shouldBe HttpStatus.BadRequest_400
      }
    }
  }

  test("reject a body that decodes past the limit") {
    val request = Request
      .post("/")
      .addHeader(HttpHeader.ContentEncoding, "gzip")
      .withBytesContent(gzip(body(200000)))
    RequestDecompression.decode(request, 100000) shouldMatch { case Left(r) =>
      r.status shouldBe HttpStatus.PayloadTooLarge_413
    }
  }

end RequestDecompressionTest

object RequestDecompressionTest:

  // Empty, tiny, under one 8 KiB chunk, and two sizes past 64 KiB whose compressed form is past
  // 64 KiB too (the body is incompressible)
  val Sizes: Seq[Int] = Seq(0, 1, 5000, 70000, 300000)

  /** Pseudo-random, so the compressed body is about as large as the body */
  def body(n: Int): Array[Byte] =
    val r = scala.util.Random(n)
    Array.fill(n)(r.nextInt().toByte)

  /** (label for failures, Content-Encoding, encoder) */
  val Encoders: Seq[(String, String, Array[Byte] => Array[Byte])] = Seq(
    ("gzip", "gzip", gzip),
    ("x-gzip", "x-gzip", gzip),
    // Two members, as `cat a.gz b.gz` makes
    ("multi-member gzip", "gzip", b => gzip(b.take(b.length / 2)) ++ gzip(b.drop(b.length / 2))),
    ("zlib deflate", "deflate", b => deflate(b, raw = false)),
    ("raw deflate", "deflate", b => deflate(b, raw = true)),
    ("x-deflate", "x-deflate", b => deflate(b, raw = false))
  )

  def gzip(b: Array[Byte]): Array[Byte] = encode(GZIPOutputStream(_), b)

  def deflate(b: Array[Byte], raw: Boolean): Array[Byte] = encode(
    out => DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, raw)),
    b
  )

  /**
    * Writes in 8 KiB pieces, as the server's own encoders do (single writes over 64 KiB are covered
    * by ZlibLargeCallTest).
    */
  private def encode(open: OutputStream => DeflaterOutputStream, b: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val enc = open(out)
    var pos = 0
    while pos < b.length do
      val n = math.min(8192, b.length - pos)
      enc.write(b, pos, n)
      pos += n
    enc.close()
    out.toByteArray

end RequestDecompressionTest
