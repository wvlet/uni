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

class HttpClientIRTest extends UniTest:

  test("TypeRef should render simple types") {
    TypeRef.Int.render shouldBe "Int"
    TypeRef.String.render shouldBe "String"
    TypeRef.Unit.render shouldBe "Unit"
  }

  test("TypeRef should render generic types") {
    val seqOfString = TypeRef("scala.collection.immutable.Seq", "Seq", Seq(TypeRef.String))
    seqOfString.render shouldBe "Seq[String]"
  }

  test("TypeRef should render nested generics") {
    val mapType = TypeRef(
      "scala.collection.immutable.Map",
      "Map",
      Seq(TypeRef.String, TypeRef.Long)
    )
    mapType.render shouldBe "Map[String, Long]"
  }

  test("TypeRef.isUnit should detect Unit type") {
    TypeRef.Unit.isUnit shouldBe true
    TypeRef.String.isUnit shouldBe false
  }

  test("TypeRef.isOption should detect Option type") {
    val optType = TypeRef("scala.Option", "Option", Seq(TypeRef.String))
    optType.isOption shouldBe true
    TypeRef.String.isOption shouldBe false
  }

  test("CodegenConfig should parse target package") {
    val config = CodegenConfig("com.example.api.UserService")
    config.apiSimpleName shouldBe "UserService"
    config.resolvedTargetPackage shouldBe "com.example.api"
  }

  test("CodegenConfig should use explicit target package") {
    val config = CodegenConfig("com.example.api.UserService").withTargetPackage(
      "com.example.client"
    )
    config.resolvedTargetPackage shouldBe "com.example.client"
  }

  test("CodegenConfig should handle top-level class names") {
    val config = CodegenConfig("UserService")
    config.apiSimpleName shouldBe "UserService"
    config.resolvedTargetPackage shouldBe ""
  }

  test("ClientType should parse from string") {
    ClientType.fromString("sync") shouldBe ClientType.Sync
    ClientType.fromString("async") shouldBe ClientType.Async
    ClientType.fromString("both") shouldBe ClientType.Both
    ClientType.fromString("rpc") shouldBe ClientType.Sync
  }

  test("ClientType should reject unknown types") {
    intercept[IllegalArgumentException] {
      ClientType.fromString("unknown")
    }
  }

  test("ServiceDef.fullName should combine package and name") {
    val svc = ServiceDef("com.example.api", "UserService", Seq.empty)
    svc.fullName shouldBe "com.example.api.UserService"
  }

  test("ServiceDef.fullName should handle empty package") {
    val svc = ServiceDef("", "UserService", Seq.empty)
    svc.fullName shouldBe "UserService"
  }

end HttpClientIRTest
