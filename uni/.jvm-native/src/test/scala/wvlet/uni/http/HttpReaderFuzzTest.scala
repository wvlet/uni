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
import wvlet.uni.test.fuzz.FuzzConfig

import java.nio.charset.StandardCharsets

/**
  * Fuzz the HTTP/1.1 request reader of the JVM and Native servers. It reads bytes from any client,
  * so it must answer every input with requests, a 4xx/5xx error, or a closed connection, never an
  * exception. And as in [[HttpReaderSpecTest]], where a read ends must never change where a request
  * ends: every read size gives the same outcomes.
  */
class HttpReaderFuzzTest extends UniTest:
  import HttpReaderSpecTest.*

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(
      if isScalaNative then
        300
      else
        2000
    )
    .withCorpusDir("uni/src/test/resources/fuzz")

  test("the HTTP reader frames requests the same way for every read size") {
    val seeds = (HttpReaderSpecCases.cases ++ HttpReaderSpecCases.smugglingCases).map(c =>
      c.split("␞", 2)(0).getBytes(StandardCharsets.ISO_8859_1)
    )
    fuzz(
      "http-reader",
      fuzzConfig
        .withSeedBytes(seeds*)
        .withDictionary(
          "\r\n",
          "\r\n\r\n",
          "GET / HTTP/1.1\r\n",
          "POST /a HTTP/1.1\r\n",
          "HTTP/1.0",
          "Host: a\r\n",
          "Content-Length: ",
          "Transfer-Encoding: chunked\r\n",
          "0\r\n\r\n",
          ";ext=1",
          "Connection: close\r\n",
          "Connection: keep-alive\r\n",
          "Expect: 100-continue\r\n",
          " ",
          "\t",
          ":"
        )
    ) { bytes =>
      val whole = outcomes(bytes, 1 << 16)
      readSizes.foreach { size =>
        val split = outcomes(bytes, size)
        if split != whole then
          fail(s"reads of ${size} bytes give ${split}, but one read gives ${whole}")
      }
    }
  }

end HttpReaderFuzzTest
