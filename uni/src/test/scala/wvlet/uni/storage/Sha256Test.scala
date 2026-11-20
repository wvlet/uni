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
package wvlet.uni.storage

import wvlet.uni.test.UniTest

class Sha256Test extends UniTest:
  test("SHA-256 matches the FIPS 180-4 examples") {
    Sha256.hex("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    Sha256.hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") shouldBe
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
  }

  test("SHA-256 of one million 'a' fed in uneven pieces") {
    val d     = Sha256()
    val chunk = Array.fill[Byte](997)('a'.toByte)
    var left  = 1000000
    while left > 0 do
      val n = math.min(left, chunk.length)
      d.update(chunk, 0, n)
      left -= n
    Sha256.toHex(d.digest()) shouldBe
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
  }

  test("byte-at-a-time updates match one-shot digests around block boundaries") {
    (0 to 130).foreach { n =>
      val msg = Array.tabulate[Byte](n)(i => (i * 31 + 7).toByte)
      val d   = Sha256()
      msg.foreach(b => d.update(Array(b)))
      Sha256.toHex(d.digest()) shouldBe Sha256.toHex(Sha256.digest(msg))
    }
  }

  test("HMAC-SHA256 matches RFC 4231") {
    Sha256.toHex(Sha256.hmac(Array.fill[Byte](20)(0x0b), "Hi There")) shouldBe
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
    Sha256.toHex(Sha256.hmac("Jefe".getBytes("UTF-8"), "what do ya want for nothing?")) shouldBe
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    // A key longer than the block size is hashed first
    Sha256.toHex(
      Sha256.hmac(
        Array.fill[Byte](131)(0xaa.toByte),
        "Test Using Larger Than Block-Size Key - Hash Key First"
      )
    ) shouldBe "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
  }

end Sha256Test
