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
package wvlet.uni.util

import wvlet.uni.test.UniTest

class Crc32Test extends UniTest:
  private def crc(s: String): Long = Crc32.of(s.getBytes("UTF-8")) & 0xffffffffL

  test("CRC-32 matches the standard check values") {
    crc("") shouldBe 0L
    crc("123456789") shouldBe 0xcbf43926L
    crc("The quick brown fox jumps over the lazy dog") shouldBe 0x414fa339L
    crc("a") shouldBe 0xe8b7be43L
  }

  test("an offset and length select the bytes") {
    val bytes = "xx123456789yy".getBytes("UTF-8")
    (Crc32.of(bytes, 2, 9) & 0xffffffffL) shouldBe 0xcbf43926L
  }

end Crc32Test
