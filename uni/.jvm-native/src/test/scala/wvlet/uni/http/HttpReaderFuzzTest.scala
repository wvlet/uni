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
import scala.util.control.NonFatal

/**
  * Fuzz the HTTP/1.1 request reader of the JVM and Native servers. It reads bytes from any client,
  * so it must answer every input with requests, a 4xx/5xx error, or a closed connection, never an
  * exception. And as in [[HttpReaderSpecTest]], where a read ends must never change where a request
  * ends: every read size gives the same outcomes. A request the reader keeps the connection open
  * after must be framed the same way by every lenient parser of [[HttpSmuggleModel]] that accepts
  * it, so no front proxy among them can smuggle a request past the reader. And every accepted
  * request must keep the RFC 9112 rules that hold for any input (see `requestRules`).
  *
  * When the Lean model is built (`lake build http-reader-oracle` in `spec/lean`, or the path in
  * `UNI_LEAN_HTTP_ORACLE`), every input must also give the outcomes `readAll` in
  * `spec/lean/UniSpec/HttpReader.lean` gives. That check runs on the JVM only and is skipped
  * without the model, since Lean is a dev-only tool.
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

  /**
    * The Lean model as a running process, or None (logged) when it is not built or not on the JVM
    */
  private def startModel(): Option[LeanOracle] =
    if !isScalaJVM then
      None
    else
      LeanOracle.find("http-reader-oracle", "UNI_LEAN_HTTP_ORACLE") match
        case Right(exe) =>
          Some(
            LeanOracle(
              Seq(
                exe.getPath,
                HttpReaderSpecCases.maxInitialLineLength.toString,
                HttpReaderSpecCases.maxHeaderSize.toString,
                HttpReaderSpecCases.maxRequestSize.toString
              )
            )
          )
        case Left(path) =>
          info(
            s"Skipping the Lean model check: ${path} is not built (run `lake build http-reader-oracle` in spec/lean)"
          )
          None

  test("the HTTP reader frames requests the same way for every read size") {
    val model = startModel()
    // An oracle that fails is not a reader bug: stop asking it, and fail after the run, so the fuzz
    // runner never reduces or saves an input for it
    var modelError: Option[Throwable] = None
    val seeds = (HttpReaderSpecCases.cases ++ HttpReaderSpecCases.smugglingCases).map(c =>
      c.split("␞", 2)(0).getBytes(StandardCharsets.ISO_8859_1)
    )
    try
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
        smuggling(bytes).foreach(fail(_))
        requestRules(bytes).foreach(fail(_))
        val whole = outcomes(bytes, 1 << 16)
        readSizes.foreach { size =>
          val split = outcomes(bytes, size)
          if split != whole then
            fail(s"reads of ${size} bytes give ${split}, but one read gives ${whole}")
        }
        model
          .filter(_ => modelError.isEmpty)
          .flatMap { m =>
            try
              Some(m.ask(bytes).map(String(_, StandardCharsets.ISO_8859_1)))
            catch
              case NonFatal(e) =>
                modelError = Some(e)
                None
          }
          .foreach { expected =>
            val actual = outcomes(bytes, 1 << 16, latin1Body = true)
            if actual != expected then
              fail(s"the reader gives ${actual}, but the Lean model gives ${expected}")
          }
      }
    finally model.foreach(m =>
        try
          m.close()
        catch
          case NonFatal(_) =>
      )
    end try
    modelError.foreach(e => fail(s"The Lean model (http-reader-oracle) failed: ${e}"))
  }

end HttpReaderFuzzTest
