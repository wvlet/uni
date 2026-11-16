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
package wvlet.uni.test

import wvlet.uni.log.LogFormatter
import wvlet.uni.log.LogLevel
import wvlet.uni.log.LogRecord
import wvlet.uni.log.LogSource
import wvlet.uni.log.ShortStackTrace

import java.nio.file.Files
import java.nio.file.Paths

class ShortStackTraceFileTest extends UniTest:

  test("saves the full trace and links to it") {
    val dir = Files.createTempDirectory("uni-stacktrace-test")
    val e   = RuntimeException("boom")
    e.setStackTrace(
      (1 to 20).map(i => StackTraceElement(s"app.C${i}", "run", "C.scala", i)).toArray
    )
    val out  = ShortStackTrace().withTraceDir(dir.toString).format(e)
    val last = out.linesIterator.toSeq.last
    debug(out)
    val path = last.substring(last.indexOf("\u001b\\") + 2, last.lastIndexOf("\u001b]8;;"))
    last shouldContain s"\u001b]8;;file://${path}\u001b\\"
    Files.readString(Paths.get(path)) shouldBe LogFormatter.formatStacktrace(e)
  }

  test("log formatters shorten traces only inside withStyle") {
    val dir = Files.createTempDirectory("uni-stacktrace-test")
    val e   = RuntimeException("x")
    e.setStackTrace(
      (1 to 20).map(i => StackTraceElement(s"app.C${i}", "run", "C.scala", i)).toArray
    )
    val record    = LogRecord(LogLevel.ERROR, LogSource("Src.scala", 1, 1), "failed", e)
    val atLines   = (s: String) => s.linesIterator.count(_.trim.startsWith("at "))
    val full      = LogFormatter.BareFormatter.formatLog(record)
    val shortened =
      ShortStackTrace.withStyle(Some(ShortStackTrace().withTraceDir(dir.toString)))(
        LogFormatter.BareFormatter.formatLog(record)
      )
    atLines(full) shouldBe 20
    atLines(shortened) shouldBe 5
    // The style ends with the block
    LogFormatter.BareFormatter.formatLog(record) shouldBe full
  }

end ShortStackTraceFileTest
