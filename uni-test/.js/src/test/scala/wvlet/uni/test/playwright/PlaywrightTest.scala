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
package wvlet.uni.test.playwright

import wvlet.uni.test.UniTest

import scala.concurrent.ExecutionContext

class PlaywrightTest extends UniTest:

  private given ExecutionContext = wvlet.uni.test.compat.executionContext

  test("is unavailable outside the Playwright JSEnv") {
    Playwright.isAvailable shouldBe false
  }

  test("calls fail with a message naming the required JSEnv") {
    Playwright
      .screenshot("x")
      .failed
      .map { e =>
        e shouldMatch { case _: IllegalStateException =>
        }
        e.getMessage shouldContain "uni-jsenv-playwright"
      }
  }

end PlaywrightTest
