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

  // 1: every byte in its own read. Large: the whole stream in one read.
  private val readSizes = Seq(1, 2, 3, 5, 8, 13, 64, 1 << 16)

  /** Run the reader over `input`, delivered at most `readSize` bytes per read. */
  private def outcomes(input: Array[Byte], readSize: Int): Seq[String] =
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
      // The model leaves out the line and header limits, so they never trip here
      HttpReaderLimits(
        maxInitialLineLength = 1 << 20,
        maxHeaderSize = 1 << 20,
        maxContentLength = HttpReaderSpecCases.maxRequestSize
      )
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
          out +=
            s"req ${request.method.name} ${request.uri} ${connection} ${request
                .content
                .toContentString}"
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
