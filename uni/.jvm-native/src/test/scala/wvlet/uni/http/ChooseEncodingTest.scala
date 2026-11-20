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
package wvlet.uni.http

import wvlet.uni.test.UniTest

class ChooseEncodingTest extends UniTest:
  private def choose(values: String*): Option[String] = ResponseCompression.chooseEncoding(values)

  test("pick gzip or deflate by q-value") {
    choose("gzip") shouldBe Some("gzip")
    choose("deflate") shouldBe Some("deflate")
    choose("gzip, deflate, br") shouldBe Some("gzip")
    choose("gzip;q=0.5, deflate;q=0.8") shouldBe Some("deflate")
    choose("gzip ; q=0.9 , deflate ; q=0.9") shouldBe Some("gzip")
    choose("x-gzip") shouldBe Some("gzip")
    choose("gzip;level=1;q=0.7", "deflate;q=0.6") shouldBe Some("gzip")
  }

  test("q=0, invalid q-values, and unknown codings disable an encoding") {
    choose("gzip;q=0") shouldBe None
    choose("gzip;q=abc") shouldBe None
    choose("br") shouldBe None
    choose("identity") shouldBe None
    choose("gzip;q=0, deflate;q=0") shouldBe None
    choose() shouldBe None
  }

  test("* applies to the codings not listed") {
    choose("*") shouldBe Some("gzip")
    choose("gzip;q=0, *") shouldBe Some("deflate")
    choose("*;q=0") shouldBe None
  }

end ChooseEncodingTest
