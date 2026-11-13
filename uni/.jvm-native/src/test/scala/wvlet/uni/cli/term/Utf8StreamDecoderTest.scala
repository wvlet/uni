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
package wvlet.uni.cli.term

import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets

class Utf8StreamDecoderTest extends UniTest:
  private val text  = "aé日本🎉z"
  private val bytes = text.getBytes(StandardCharsets.UTF_8)

  private def decodeInChunks(size: Int): String =
    val d   = Utf8StreamDecoder()
    val out = StringBuilder()
    bytes
      .grouped(size)
      .foreach { chunk =>
        out.append(d.decode(chunk, chunk.length))
      }
    out.append(d.flush())
    out.toString

  test("decode characters split at every byte position") {
    (1 to bytes.length).foreach { size =>
      decodeInChunks(size) shouldBe text
    }
  }

  test("hold back an incomplete character until the rest arrives") {
    val d     = Utf8StreamDecoder()
    val emoji = "🎉".getBytes(StandardCharsets.UTF_8)
    d.decode(emoji.take(3), 3) shouldBe ""
    d.decode(emoji.drop(3), 1) shouldBe "🎉"
  }

  test("only the first length bytes of the buffer are read") {
    val d = Utf8StreamDecoder()
    d.decode("abc".getBytes(StandardCharsets.UTF_8), 2) shouldBe "ab"
  }

  test("malformed input and a truncated end become replacement characters") {
    val d = Utf8StreamDecoder()
    d.decode(Array(0xff.toByte, 'a'.toByte), 2) shouldBe "�a"
    d.decode(Array(0xe3.toByte), 1) shouldBe ""
    d.flush() shouldBe "�"
  }

end Utf8StreamDecoderTest
