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

import wvlet.uni.log.Platform
import wvlet.uni.test.UniTest

import java.io.{ByteArrayOutputStream, OutputStream}
import java.util.zip.{Deflater, DeflaterOutputStream, GZIPOutputStream}

/**
  * Request body decoding, on every platform. The Scala Native (Windows) CI job runs it too:
  * javalib's zlib binding there truncates any single call over 64 KiB (see RequestDecompression),
  * so the larger bodies check that decoding keeps each call under that bound.
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
      n               <- Sizes
      (encoding, enc) <- Encoders
    do
      val b = body(n)
      // Name the case in the comparison, so a failure says which one broke
      (encoding, n, decoded(encoding, enc(b)).toSeq == b.toSeq) shouldBe (encoding, n, true)
  }

  test("decode a gzip body with several members, where the platform supports it") {
    val (a, b) = (body(3000), body(90000))
    val wire   = gzip(a) ++ gzip(b)
    val out    = decoded("gzip", wire)
    if Platform.isScalaNative then
      // javalib's GZIPInputStream stops after the first member
      out.toSeq shouldBe a.toSeq
    else
      out.toSeq shouldBe (a ++ b).toSeq
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

  val Encoders: Seq[(String, Array[Byte] => Array[Byte])] = Seq(
    "gzip"    -> gzip,
    "x-gzip"  -> gzip,
    "deflate" -> (b => deflate(b, raw = false)),
    "deflate" -> (b => deflate(b, raw = true))
  )

  def gzip(b: Array[Byte]): Array[Byte] = encode(GZIPOutputStream(_), b)

  def deflate(b: Array[Byte], raw: Boolean): Array[Byte] = encode(
    out => DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, raw)),
    b
  )

  /**
    * Writes in 8 KiB pieces: on Windows Native, a single `write` over 64 KiB never returns, for the
    * same javalib bug RequestDecompression avoids.
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
