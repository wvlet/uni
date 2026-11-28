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

class HttpSmuggleModelTest extends UniTest:
  import HttpSmuggleModel.*
  import HttpReaderSpecTest.*

  private val strict = Opts(false, false, false, false, false, false)

  private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.ISO_8859_1)

  test("no member frames a Lean-generated input differently from the reader") {
    (HttpReaderSpecCases.cases ++ HttpReaderSpecCases.smugglingCases).foreach { c =>
      val input = c.split("␞", 2)(0)
      smuggling(bytes(input)) shouldBe None
      requestRules(bytes(input)) shouldBe None
    }
  }

  test("the reader's head length counts the line breaks before the request line") {
    val get = "GET / HTTP/1.1\r\nHost: a\r\n\r\n"
    readerFraming(bytes(get)) shouldBe Some((get.length, Framing.Length(0)))
    readerFraming(bytes(s"\r\n${get}")) shouldBe Some((get.length + 2, Framing.Length(0)))
    val post = "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\n"
    readerFraming(bytes(s"${post}hello")) shouldBe Some((post.length, Framing.Length(5)))
    val chunked = "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
    readerFraming(bytes(s"${chunked}0\r\n\r\n")) shouldBe Some((chunked.length, Framing.Chunked))
    readerFraming(bytes("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n")) shouldBe None
  }

  test("members differ where lenient parsers do") {
    val bareLf = "GET / HTTP/1.1\nHost: a\n\n"
    interp(strict, bareLf) shouldBe None
    interp(strict.copy(lfLines = true), bareLf) shouldBe Some((bareLf.length, Framing.Length(0)))
    val twoLengths = "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"
    interp(strict, twoLengths).map(_._2) shouldBe Some(Framing.Length(1))
    interp(strict.copy(dupLast = true), twoLengths).map(_._2) shouldBe Some(Framing.Length(2))
    val spaced = "POST / HTTP/1.1\r\nTransfer-Encoding : chunked\r\n\r\n"
    interp(strict, spaced).map(_._2) shouldBe Some(Framing.Length(0))
    interp(strict.copy(stripColonWs = true), spaced).map(_._2) shouldBe Some(Framing.Chunked)
  }

  test("reports a member that frames a kept-alive request differently") {
    val input = "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"
    // As a reader that lets the first of two lengths win would see it
    val found = disagreeing((input.length - 2, Framing.Length(1)), input)
    found.map(_._1.dupLast).distinct shouldBe Seq(true)
  }

end HttpSmuggleModelTest
