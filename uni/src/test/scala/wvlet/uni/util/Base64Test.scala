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

class Base64Test extends UniTest:

  private val alphabet = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ Seq('+', '/')).toSet

  test("encode RFC 4648 test vectors") {
    val vectors = Seq(
      ""       -> "",
      "f"      -> "Zg==",
      "fo"     -> "Zm8=",
      "foo"    -> "Zm9v",
      "foob"   -> "Zm9vYg==",
      "fooba"  -> "Zm9vYmE=",
      "foobar" -> "Zm9vYmFy"
    )
    vectors.foreach { (plain, encoded) =>
      Base64.encodeToString(plain) shouldBe encoded
      Base64.decodeToString(encoded) shouldBe plain
    }
  }

  test("decode(encode(bytes)) restores the bytes") {
    forAll { (bytes: Array[Byte]) =>
      Base64.decode(Base64.encode(bytes)).toSeq shouldBe bytes.toSeq
    }
  }

  test("decodeToString(encodeToString(s)) restores the string") {
    forAll { (s: String) =>
      Base64.decodeToString(Base64.encodeToString(s)) shouldBe s
    }
  }

  test("encoded output is padded to a multiple of 4 and uses only the Base64 alphabet") {
    forAll { (bytes: Array[Byte]) =>
      val encoded = Base64.encode(bytes)
      encoded.length shouldBe ((bytes.length + 2) / 3) * 4
      val body = encoded.reverse.dropWhile(_ == '=').reverse
      (encoded.length - body.length <= 2) shouldBe true
      body.forall(alphabet.contains) shouldBe true
    }
  }

  test("decode ignores whitespace") {
    forAll { (bytes: Array[Byte]) =>
      val encoded = Base64.encode(bytes).grouped(8).mkString("\n")
      Base64.decode(encoded).toSeq shouldBe bytes.toSeq
    }
  }

end Base64Test
