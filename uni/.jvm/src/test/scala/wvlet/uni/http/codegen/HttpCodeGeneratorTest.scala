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
package wvlet.uni.http.codegen

import wvlet.uni.test.UniTest

class HttpCodeGeneratorTest extends UniTest:

  test("parseConfig should parse simple spec") {
    val config = HttpCodeGenerator.parseConfig("com.example.api.UserService:rpc")
    config.apiClassName shouldBe "com.example.api.UserService"
    config.clientType shouldBe ClientType.Sync
    config.targetPackage shouldBe None
  }

  test("parseConfig should parse spec with target package") {
    val config = HttpCodeGenerator.parseConfig(
      "com.example.api.UserService:both:com.example.client"
    )
    config.apiClassName shouldBe "com.example.api.UserService"
    config.clientType shouldBe ClientType.Both
    config.targetPackage shouldBe Some("com.example.client")
  }

  test("parseConfig should parse async type") {
    val config = HttpCodeGenerator.parseConfig("com.example.api.UserService:async")
    config.clientType shouldBe ClientType.Async
  }

  test("parseConfig should reject invalid spec") {
    intercept[IllegalArgumentException] {
      HttpCodeGenerator.parseConfig("missing-client-type")
    }
  }

end HttpCodeGeneratorTest
