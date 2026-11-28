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

import java.nio.charset.StandardCharsets

/**
  * Replays the request-framing cases generated from the Lean model in
  * `spec/lean/UniSpec/HttpReader.lean`. Each byte stream reaches the reader in reads of several
  * sizes, and every split must give the outcomes the model gives for the whole stream: where a read
  * ends never changes where a request ends. The smuggling cases come from
  * `spec/lean/UniSpec/HttpSmuggle.lean`: inputs a lenient front proxy could frame differently,
  * which the reader must reject or close the connection after.
  */
class HttpReaderSpecTest extends UniTest:
  import HttpReaderSpecTest.*

  // The first 40 bytes, with CR, LF, and HTAB escaped so each test name stays on one line
  private def label(input: String): String = input
    .take(40)
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t")

  private def replay(name: String, cases: Seq[String]): Unit = cases
    .zipWithIndex
    .foreach { (c, i) =>
      val Array(input, expected) = c.split("␞", 2)
      test(s"${name} ${i}: ${label(input)}") {
        val bytes = input.getBytes(StandardCharsets.ISO_8859_1)
        readSizes.foreach { size =>
          outcomes(bytes, size) shouldBe expected.split("␟").toSeq
        }
      }
    }

  replay("case", HttpReaderSpecCases.cases)
  // Inputs a lenient front proxy could frame differently, which the reader must reject or close after
  replay("smuggling case", HttpReaderSpecCases.smugglingCases)

end HttpReaderSpecTest

object HttpReaderSpecTest:
  // 1: every byte in its own read. Large: the whole stream in one read.
  val readSizes = Seq(1, 2, 3, 5, 8, 13, 64, 1 << 16)

  /** The limits the model's outcomes assume */
  val specLimits: HttpReaderLimits = HttpReaderLimits(
    maxInitialLineLength = HttpReaderSpecCases.maxInitialLineLength,
    maxHeaderSize = HttpReaderSpecCases.maxHeaderSize,
    maxContentLength = HttpReaderSpecCases.maxRequestSize
  )

  /**
    * Run the reader over `input`, delivered at most `readSize` bytes per read. A body prints as
    * UTF-8 text, or with `latin1Body`, one character per byte as the Lean model reads it.
    */
  def outcomes(input: Array[Byte], readSize: Int, latin1Body: Boolean = false): Seq[String] =
    var pos    = 0
    val reader = HttpConnectionReader(
      (buf, off, len, _) =>
        val n = math.min(math.min(len, readSize), input.length - pos)
        if n <= 0 then
          -1
        else
          System.arraycopy(input, pos, buf, off, n)
          pos += n
          n
      ,
      specLimits
    )
    val out  = Seq.newBuilder[String]
    var done = false
    while !done do
      reader.readRequest() match
        case ReadResult.Req(request, keepAlive) =>
          val connection =
            if keepAlive then
              "keep-alive"
            else
              "close"
          val body =
            if latin1Body then
              String(request.content.toContentBytes, StandardCharsets.ISO_8859_1)
            else
              request.content.toContentString
          out += s"req ${request.method.name} ${request.uri} ${connection} ${body}"
          if !keepAlive then
            done = true
        case ReadResult.Error(status, message) =>
          out += s"bad ${status.code} ${message}"
          done = true
        case ReadResult.Closed =>
          out += "closed"
          done = true
    out.result()

  end outcomes

  /**
    * Where the reader ends the head at the front of `input` (with the CR and LF before it) and how
    * it frames the body, when it accepts the request and keeps the connection open after it, as
    * `readerFraming` in `HttpSmuggle.lean`. Bytes arrive one per read and every body is streamed,
    * so the reader returns right after the head: the bytes it pulled are the head, and the stream's
    * length (-1 when chunked) is the framing.
    *
    * Unlike the Lean version, it does not read the body, so it also gives a framing when the body
    * is cut short or malformed. Framing depends on the head only, so a correct reader still agrees
    * with every member there; such inputs are just outside what `no_smuggling` proves.
    */
  def readerFraming(input: Array[Byte]): Option[(Int, HttpSmuggleModel.Framing)] =
    var pos    = 0
    val reader = HttpConnectionReader(
      (buf, off, _, _) =>
        if pos >= input.length then
          -1
        else
          buf(off) = input(pos)
          pos += 1
          1
      ,
      // A streamed body gets the same size limit as a buffered one
      specLimits.copy(maxStreamingRequestSize = HttpReaderSpecCases.maxRequestSize)
    )
    reader.readRequest(_ => true) match
      case ReadResult.Req(request, true) =>
        val framing =
          request.content match
            case s: HttpContent.StreamContent if s.length < 0 =>
              HttpSmuggleModel.Framing.Chunked
            case s: HttpContent.StreamContent =>
              HttpSmuggleModel.Framing.Length(s.length)
            case other =>
              // Only a request without a body is read whole when every body is streamed
              HttpSmuggleModel.Framing.Length(other.length)
        Some((pos, framing))
      case _ =>
        None

  end readerFraming

  /**
    * A failure message when a request the reader accepts from `input` breaks a rule of RFC 9112
    * that holds for any input: a `close` connection option ends the connection (§9.6), a request
    * has at most one Host field (§3.2), and the target holds no control bytes (§3.2, RFC 3986).
    */
  def requestRules(input: Array[Byte]): Option[String] =
    var pos    = 0
    val reader = HttpConnectionReader(
      (buf, off, len, _) =>
        val n = math.min(len, input.length - pos)
        if n <= 0 then
          -1
        else
          System.arraycopy(input, pos, buf, off, n)
          pos += n
          n
      ,
      specLimits
    )
    var broken: Option[String] = None
    var done                   = false
    while broken.isEmpty && !done do
      reader.readRequest() match
        case ReadResult.Req(request, keepAlive) =>
          val options = request
            .headers
            .getAll(HttpHeader.Connection)
            .flatMap(_.split(','))
            .map(_.trim.toLowerCase)
          broken =
            if keepAlive && options.contains("close") then
              Some(s"kept the connection open after Connection: close in ${request}")
            else if request.headers.getAll(HttpHeader.Host).sizeIs > 1 then
              Some(s"accepted two Host fields in ${request}")
            else if request.uri.exists(c => c < ' ' || c == '\u007f') then
              Some(s"accepted a control byte in the target of ${request}")
            else
              None
          done = !keepAlive
        case _ =>
          done = true
    broken

  end requestRules

  /**
    * A failure message when a lenient parser of the `HttpSmuggle.lean` family frames the first
    * request of `input` differently from the reader, which keeps the connection open after it
    */
  def smuggling(input: Array[Byte]): Option[String] = readerFraming(input).flatMap { r =>
    val s = String(input, StandardCharsets.ISO_8859_1)
    HttpSmuggleModel
      .disagreeing(r, s)
      .headOption
      .map { (o, m) =>
        s"the reader keeps the connection open with head length and framing ${r}, but ${o} reads ${m}"
      }
  }

end HttpReaderSpecTest
