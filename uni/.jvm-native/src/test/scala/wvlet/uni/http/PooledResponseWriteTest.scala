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
import java.util.zip.{GZIPInputStream, GZIPOutputStream, InflaterInputStream}

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

  private def gunzip(b: Array[Byte]): Array[Byte] = GZIPInputStream(ByteArrayInputStream(b))
    .readAllBytes()

  private def inflate(b: Array[Byte]): Array[Byte] = InflaterInputStream(ByteArrayInputStream(b))
    .readAllBytes()

  private def compressed(acceptEncoding: String, b: Array[Byte]): Response =
    val request = Request.get("/").addHeader(HttpHeader.AcceptEncoding, acceptEncoding)
    ResponseCompression.encode(request, Response.ok.withBytesContent(b))

  test("pooled gzip and deflate round-trip, across reuse and buffer growth") {
    // 300 KB grows the pooled output buffer past what it keeps; later calls must start clean
    Seq(2000, 300000, 1500, 70000, 1024).foreach { n =>
      val b = body(n)
      val g = compressed("gzip", b)
      g.header(HttpHeader.ContentEncoding) shouldBe Some("gzip")
      gunzip(g.content.toContentBytes).toSeq shouldBe b.toSeq
      val d = compressed("deflate", b)
      d.header(HttpHeader.ContentEncoding) shouldBe Some("deflate")
      inflate(d.content.toContentBytes).toSeq shouldBe b.toSeq
    }
  }

  test("pooled gzip gives GZIPOutputStream's bytes, apart from the OS byte") {
    val b   = body(10000)
    val out = ByteArrayOutputStream()
    val enc = GZIPOutputStream(out)
    enc.write(b)
    enc.close()
    // Byte 9 names the OS, which differs by platform (255 on the JVM, 0 in Scala Native's javalib)
    def withoutOs(a: Array[Byte]) = a.toSeq.patch(9, Nil, 1)
    withoutOs(compressed("gzip", b).content.toContentBytes) shouldBe withoutOs(out.toByteArray)
  }

end PooledResponseWriteTest
