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
import wvlet.uni.log.ShortStackTrace
import wvlet.uni.test.spi.TestConfig

class ShortStackTraceTest extends UniTest:

  private def frame(cls: String, line: Int) = StackTraceElement(cls, "run", "Src.scala", line)

  private def withFrames(e: Throwable, frames: Seq[StackTraceElement]): e.type =
    e.setStackTrace(frames.toArray)
    e

  private val userFrames = (1 to 20).map(i => frame(s"app.Service${i}", i))
  private val sbtFrames  = (1 to 10).map(i => frame("sbt.TestRunner", i))

  private val short = ShortStackTrace()
  private val path  = "/tmp/trace.txt"

  private def atLines(s: String) = s.linesIterator.count(_.trim.startsWith("at "))

  test("shows the first frames and counts the omitted lines") {
    val e   = withFrames(IllegalStateException("boom"), userFrames)
    val out = short.withMaxFrames(3).shorten(e, path)
    debug(out)
    val lines = out.linesIterator.toSeq
    lines.head shouldBe "java.lang.IllegalStateException: boom"
    atLines(out) shouldBe 3
    lines.last shouldBe s"\t... 17 more lines: ${ShortStackTrace.fileLink(path)}"
  }

  test("skips test framework frames") {
    val e   = withFrames(RuntimeException("x"), sbtFrames.take(3) ++ userFrames ++ sbtFrames)
    val out = short.shorten(e, path)
    out shouldNotContain "sbt.TestRunner"
    out shouldContain "app.Service1.run"
  }

  test("keeps framework frames when no other frame exists") {
    val poolFrames = (1 to 10).map(i => frame("java.util.concurrent.ThreadPoolExecutor", i))
    val e          = withFrames(RuntimeException("x"), poolFrames)
    val out        = short.withMaxFrames(2).shorten(e, path)
    atLines(out) shouldBe 2
    out shouldContain "java.util.concurrent.ThreadPoolExecutor"
  }

  test("shows the first frames of each cause") {
    val root  = withFrames(IllegalArgumentException("root"), userFrames)
    val cause = withFrames(RuntimeException("middle", root), userFrames)
    val e     = withFrames(RuntimeException("top", cause), userFrames)
    val out   = short.withMaxFrames(4).withMaxCauseFrames(1).shorten(e, path)
    debug(out)
    out shouldContain "Caused by: java.lang.RuntimeException: middle"
    out shouldContain "Caused by: java.lang.IllegalArgumentException: root"
    atLines(out) shouldBe 6
  }

  test("prints the full trace when it cannot be saved") {
    val e = withFrames(RuntimeException("x"), userFrames)
    short.withTraceDir("/dev/null/uni").format(e) shouldBe LogFormatter.formatStacktrace(e)
  }

  test("prints a short trace in full") {
    val e = withFrames(RuntimeException("x"), userFrames.take(2))
    short.format(e) shouldBe LogFormatter.formatStacktrace(e)
  }

  test("prints full traces when sbt output is not a terminal") {
    TestConfig().stackTraceStyle(ansiCodesSupported = false) shouldBe None
  }

  test("shortens traces for a terminal outside CI") {
    val style = TestConfig().stackTraceStyle(ansiCodesSupported = true)
    if TestConfig.isCI(sys.env) then
      style shouldBe None
    else
      style.map(_.traceDir) shouldBe Some(TestConfig.stackTraceDir)
  }

end ShortStackTraceTest
