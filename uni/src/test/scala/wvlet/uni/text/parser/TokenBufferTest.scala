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
package wvlet.uni.text.parser

import wvlet.uni.test.UniTest

class TokenBufferTest extends UniTest:

  test("append characters and materialize as String") {
    val b = TokenBuffer()
    "hello".foreach(b.append)
    b.length shouldBe 5
    b.toString shouldBe "hello"
    b.last shouldBe 'o'
  }

  test("start empty, become empty again after clear") {
    val b = TokenBuffer()
    b.isEmpty shouldBe true
    b.append('x')
    b.nonEmpty shouldBe true
    b.clear()
    b.isEmpty shouldBe true
    b.length shouldBe 0
  }

  test("grow past the initial capacity") {
    val b = TokenBuffer(initialSize = 2)
    val s = "abcdefghij"
    s.foreach(b.append)
    b.length shouldBe s.length
    b.toString shouldBe s
  }

  test("last requires a non-empty buffer with a clear message") {
    val b = TokenBuffer()
    intercept[IllegalArgumentException] {
      b.last
    }
  }

  test("lastOption returns None when the buffer is empty") {
    val b = TokenBuffer()
    b.lastOption shouldBe None
    b.append('z')
    b.lastOption shouldBe Some('z')
  }

  test("initialSize must be positive") {
    intercept[IllegalArgumentException] {
      TokenBuffer(0)
    }
  }

end TokenBufferTest
