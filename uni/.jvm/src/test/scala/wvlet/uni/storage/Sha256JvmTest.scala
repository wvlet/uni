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

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Compares the pure-Scala digests with the JDK's on varied inputs */
class Sha256JvmTest extends UniTest:
  private val rand = scala.util.Random(42)

  test("SHA-256 matches MessageDigest") {
    (Seq(0, 1, 55, 56, 63, 64, 65, 119, 120, 127, 128, 1000, 65537)).foreach { n =>
      val msg = Array.ofDim[Byte](n)
      rand.nextBytes(msg)
      Sha256.digest(msg).toSeq shouldBe MessageDigest.getInstance("SHA-256").digest(msg).toSeq
    }
  }

  test("HMAC-SHA256 matches javax.crypto.Mac") {
    Seq(1, 32, 64, 65, 200).foreach { keyLen =>
      val key = Array.ofDim[Byte](keyLen)
      val msg = Array.ofDim[Byte](keyLen * 3 + 5)
      rand.nextBytes(key)
      rand.nextBytes(msg)
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      Sha256.hmac(key, msg).toSeq shouldBe mac.doFinal(msg).toSeq
    }
  }
