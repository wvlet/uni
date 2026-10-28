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

import wvlet.uni.log.{LogLevel, LogSupport}

// Top-level, since Scala.js names the logger of a nested class after its outer class
class CaptureNoisy extends LogSupport:
  def fail(): Unit = warn("handler failed", IllegalStateException("boom"))

class CaptureOther extends LogSupport:
  def report(): Unit = error("other failed")

class OutputCaptureTest extends UniTest:

  test("capture log records of the current thread") {
    val logs = captureLogs {
      CaptureNoisy().fail()
      CaptureOther().report()
    }
    logs.records.map(_.level) shouldBe Seq(LogLevel.WARN, LogLevel.ERROR)
    logs.warnings shouldContain "handler failed"
    logs.warnings shouldContain "boom"
    logs.errors shouldBe "other failed"
    logs.messages shouldContain "other failed"
  }

  test("scope the capture to a logger") {
    val logs = captureLogs[CaptureNoisy] {
      CaptureNoisy().fail()
    }
    logs.warnings shouldContain "handler failed"
    // A record outside the scope goes to the enclosing capture, or prints as usual without one
    val outer = captureLogs {
      val none = captureLogs[CaptureOther] {
        CaptureNoisy().fail()
      }
      none.isEmpty shouldBe true
    }
    outer.warnings shouldContain "handler failed"
  }

  test("nested captures take records of their own block") {
    val outer = captureLogs {
      CaptureOther().report()
      val inner = captureLogs {
        CaptureNoisy().fail()
      }
      inner.warnings shouldContain "handler failed"
    }
    outer.errors shouldBe "other failed"
    outer.warnings shouldBe ""
  }

  test("hand records of a failed inner capture to the outer capture") {
    val outer = captureLogs {
      intercept[IllegalStateException] {
        captureLogs {
          CaptureNoisy().fail()
          throw IllegalStateException("inner failure")
        }
      }
    }
    outer.warnings shouldContain "handler failed"
  }

  test("reject an asynchronous block") {
    intercept[IllegalArgumentException] {
      captureLogs {
        wvlet.uni.rx.Rx.single(1)
      }
    }
  }

  test("capture stdout and stderr") {
    val out = captureStdout {
      println("hello stdout")
    }
    out shouldBe "hello stdout\n"
    val err = captureStderr {
      Console.err.println("hello stderr")
    }
    err shouldBe "hello stderr\n"
  }

  test("pass through an exception from the block") {
    val e = intercept[IllegalStateException] {
      captureStdout {
        throw IllegalStateException("fail")
      }
    }
    e.getMessage shouldBe "fail"
  }

end OutputCaptureTest
