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
import wvlet.uni.test.check.Gen

class URLEncoderTest extends UniTest:

  private def isUnreserved(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.' || c == '~'

  test("encode examples") {
    URLEncoder.encode("") shouldBe ""
    URLEncoder.encode("abc-_.~XYZ019") shouldBe "abc-_.~XYZ019"
    URLEncoder.encode("a b&c=d/e") shouldBe "a%20b%26c%3Dd%2Fe"
    URLEncoder.encode("日本") shouldBe "%E6%97%A5%E6%9C%AC"
  }

  test("decode treats '+' as a space") {
    URLEncoder.decode("a+b") shouldBe "a b"
  }

  test("decode(encode(s)) restores the string") {
    forAll { (s: String) =>
      URLEncoder.decode(URLEncoder.encode(s)) shouldBe s
    }
  }

  test("encoded output contains only unreserved characters and %XX escapes") {
    forAll { (s: String) =>
      val encoded = URLEncoder.encode(s)
      var i       = 0
      while i < encoded.length do
        val c = encoded.charAt(i)
        if c == '%' then
          encoded.substring(i + 1, i + 3).matches("[0-9A-F]{2}") shouldBe true
          i += 3
        else
          isUnreserved(c) shouldBe true
          i += 1
    }
  }

  test("strings of unreserved characters are left unchanged") {
    val unreservedChar = Gen.oneOf(
      (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ Seq('-', '_', '.', '~'))
    )
    forAll(Gen.stringOf(unreservedChar)) { s =>
      URLEncoder.encode(s) shouldBe s
    }
  }

end URLEncoderTest
