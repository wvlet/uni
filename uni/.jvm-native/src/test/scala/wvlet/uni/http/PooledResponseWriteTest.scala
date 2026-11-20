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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{GZIPOutputStream, InflaterInputStream}

class PooledResponseWriteTest extends UniTest:

  /** Collects what the writer sends */
  private class Capture extends SocketConnection:
    val out = ByteArrayOutputStream()
    override def read(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int = -1
    override def write(data: Array[Byte], off: Int, len: Int): Boolean               =
      out.write(data, off, len)
      true

    override def interrupt(): Unit = ()
    override def close(): Unit     = ()

  private def body(n: Int): Array[Byte] = Array.tabulate(n)(i => ('a' + i % 26).toByte)

  test("write sends what serialize gives, for small, large, HEAD, and empty responses") {
    val responses = Seq(
      Response.ok.withTextContent("hello"),
      Response.ok.withBytesContent(body(5000)).addHeader("X-A", "1"),
      Response.ok.withBytesContent(body(200000)),
      Response.notFound,
      Response(HttpStatus.NoContent_204)
    )
    for
      r         <- responses
      keepAlive <- Seq(true, false)
      include   <- Seq(true, false)
    do
      val c = Capture()
      HttpResponseWriter.write(c, r, keepAlive, include) shouldBe true
      c.out.toByteArray.toSeq shouldBe HttpResponseWriter.serialize(r, keepAlive, include).toSeq
  }

  /**
    * Decode a gzip stream: inflate its raw deflate data and check the trailer against the
    * pure-Scala CRC-32. Scala Native's CRC32 is unreliable on Windows (see Crc32), so neither it
    * nor `GZIPInputStream` checks the trailer here.
    */
  private def gunzip(b: Array[Byte]): (Array[Byte], Boolean) =
    b(0) shouldBe 0x1f.toByte
    b(1) shouldBe 0x8b.toByte
    val inflater = java.util.zip.Inflater(true)
    inflater.setInput(b, 10, b.length - 18)
    val out = ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    while !inflater.finished() do
      val n = inflater.inflate(buf)
      out.write(buf, 0, n)
    inflater.end()
    val data                 = out.toByteArray
    def intLE(at: Int): Long =
      (b(at) & 0xffL) |
        ((b(at + 1) & 0xffL) << 8) |
        ((b(at + 2) & 0xffL) << 16) |
        ((b(at + 3) & 0xffL) << 24)
    val crc = java.util.zip.CRC32()
    crc.update(data, 0, data.length)
    val trailerOk =
      (crc.getValue & 0xffffffffL) == intLE(b.length - 8) &&
        intLE(b.length - 4) ==
        (data.length & 0xffffffffL)
    (data, trailerOk)

  private def inflate(b: Array[Byte]): Array[Byte] = InflaterInputStream(ByteArrayInputStream(b))
    .readAllBytes()

  private def gzipStream(b: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val enc = GZIPOutputStream(out)
    enc.write(b)
    enc.close()
    out.toByteArray

  // Byte 9 names the OS, which differs by platform (255 on the JVM, 0 in Scala Native's javalib)
  private def withoutOs(a: Array[Byte]): Seq[Byte] = a.toSeq.patch(9, Nil, 1)

  private def compressed(acceptEncoding: String, b: Array[Byte]): Response =
    val request = Request.get("/").addHeader(HttpHeader.AcceptEncoding, acceptEncoding)
    ResponseCompression.encode(request, Response.ok.withBytesContent(b))

  test("pooled gzip and deflate round-trip, across reuse and buffer growth") {
    // 300 KB grows the pooled output buffer past what it keeps; later calls must start clean
    // Each check names the body size and the step, so a failure says which one broke
    Seq(2000, 300000, 1500, 70000, 1024).foreach { n =>
      val b = body(n)
      val g = compressed("gzip", b)
      g.header(HttpHeader.ContentEncoding) shouldBe Some("gzip")
      val gz = g.content.toContentBytes
      (
        n,
        "same bytes as GZIPOutputStream",
        withoutOs(gz).sameElements(withoutOs(gzipStream(b)))
      ) shouldBe (n, "same bytes as GZIPOutputStream", true)
      val (data, trailerOk) = gunzip(gz)
      (n, "decodes to the body", data.sameElements(b)) shouldBe (n, "decodes to the body", true)
      (n, "trailer matches", trailerOk) shouldBe (n, "trailer matches", true)
      val d = compressed("deflate", b)
      d.header(HttpHeader.ContentEncoding) shouldBe Some("deflate")
      inflate(d.content.toContentBytes).toSeq shouldBe b.toSeq
    }
  }

  test("pooled gzip gives GZIPOutputStream's bytes, apart from the OS byte") {
    val b = body(10000)
    withoutOs(compressed("gzip", b).content.toContentBytes) shouldBe withoutOs(gzipStream(b))
  }

end PooledResponseWriteTest
