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
package wvlet.uni.test.spi

import wvlet.uni.test.UniTest

/**
  * Suites run on the sbt task thread, so an interrupt flag left by one test body must not abort the
  * rest of the suite
  */
class InterruptFlagTest extends UniTest:

  test("a test body leaves the interrupt flag set") {
    Thread.currentThread().interrupt()
  }

  test("the next test runs with the flag cleared") {
    Thread.currentThread().isInterrupted shouldBe false
  }

end InterruptFlagTest
