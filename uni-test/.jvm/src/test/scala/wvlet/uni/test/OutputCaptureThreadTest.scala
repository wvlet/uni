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

class OutputCaptureThreadTest extends UniTest:

  test("capture records from threads that run no suite") {
    val logs = captureLogs {
      val t = Thread(() => CaptureNoisy().fail())
      t.start()
      t.join()
    }
    logs.warnings shouldContain "handler failed"
  }

  test("leave records of threads running another suite alone") {
    var other = CapturedLogs(Nil)
    val logs  = captureLogs {
      // Another suite, capturing on its own thread
      val t = Thread(() =>
        spi
          .TestLogCapture
          .withSuiteThread {
            other = captureLogs {
              CaptureNoisy().fail()
            }
          }
      )
      t.start()
      t.join()
    }
    logs.isEmpty shouldBe true
    other.warnings shouldContain "handler failed"
  }

end OutputCaptureThreadTest
