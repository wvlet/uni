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

/**
  * The OS CSPRNG behind [[SecureRandom]] on Scala Native (getentropy / BCryptGenRandom), including
  * the 256-byte chunk boundary of getentropy.
  */
class NativeSecureRandomTest extends UniTest:

  private val random = SecureRandom.getInstance

  test("fill byte arrays of any size") {
    for size <- Seq(0, 1, 255, 256, 257, 4096) do
      val bytes = Array.fill[Byte](size)(0)
      random.nextBytes(bytes)
      if size >= 255 then
        // An all-zero result of this length means the buffer was never written
        bytes.exists(_ != 0) shouldBe true
        // Every 256-byte chunk is filled, not just the first one. Skip short tails, which can be
        // all zero by chance.
        bytes.grouped(256).filter(_.length >= 32).forall(_.exists(_ != 0)) shouldBe true
  }

  test("consecutive calls differ") {
    val a = Array.ofDim[Byte](32)
    val b = Array.ofDim[Byte](32)
    random.nextBytes(a)
    random.nextBytes(b)
    a.sameElements(b) shouldBe false
    Seq.fill(10)(random.nextLong()).distinct.size shouldBe 10
  }

  test("generate ids") {
    ULID.isValid(ULID.newULIDString) shouldBe true
    ULID.newULIDString shouldNotBe ULID.newULIDString
    UUIDv7.newUUIDv7().version shouldBe 7
    UUIDv7.newUUIDv7() shouldNotBe UUIDv7.newUUIDv7()
    NanoId.generate().length shouldBe NanoId.DefaultSize
    NanoId.generate() shouldNotBe NanoId.generate()
  }

end NativeSecureRandomTest
