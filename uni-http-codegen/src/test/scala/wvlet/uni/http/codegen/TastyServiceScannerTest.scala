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

import java.io.File

class TastyServiceScannerTest extends UniTest:

  /**
    * Locate the .tasty file for a given class name in the test classpath
    */
  private def findTastyFile(className: String): String =
    // The test classes are compiled to the test-classes directory
    val classFile = className.replace('.', '/') + ".tasty"
    val url       = getClass.getClassLoader.getResource(classFile)
    if url == null then
      throw IllegalStateException(
        s"Could not find .tasty file for ${className}. Make sure the test classes are compiled."
      )
    // Convert URL to file path
    File(url.toURI).getAbsolutePath

  test("scan TestUserService trait") {
    val tastyPath = findTastyFile("wvlet.uni.http.codegen.TestUserService")
    val service   = TastyServiceScanner.scan(tastyPath)

    service.packageName shouldBe "wvlet.uni.http.codegen"
    service.serviceName shouldBe "TestUserService"
    service.methods.size shouldBe 4

    val getUser = service.methods.find(_.name == "getUser").get
    getUser.httpMethod shouldBe "POST"
    getUser.path shouldBe "/wvlet.uni.http.codegen.TestUserService/getUser"
    getUser.isRPC shouldBe true
    getUser.params.size shouldBe 1
    getUser.params.head.name shouldBe "id"
    getUser.returnType.shortName shouldBe "User"

    val createUser = service.methods.find(_.name == "createUser").get
    createUser.params.size shouldBe 2
    createUser.params.map(_.name) shouldBe Seq("name", "email")

    val deleteUser = service.methods.find(_.name == "deleteUser").get
    deleteUser.returnType.isUnit shouldBe true

    val listUsers = service.methods.find(_.name == "listUsers").get
    listUsers.params.size shouldBe 0
    listUsers.returnType.shortName shouldBe "Seq"
    listUsers.returnType.typeArgs.size shouldBe 1
  }

  test("scan TestHealthService trait") {
    val tastyPath = findTastyFile("wvlet.uni.http.codegen.TestHealthService")
    val service   = TastyServiceScanner.scan(tastyPath)

    service.serviceName shouldBe "TestHealthService"
    service.methods.size shouldBe 1
    service.methods.head.name shouldBe "ping"
    service.methods.head.params.size shouldBe 0
  }

  test("scan TestOptionalParamService trait") {
    val tastyPath = findTastyFile("wvlet.uni.http.codegen.TestOptionalParamService")
    val service   = TastyServiceScanner.scan(tastyPath)

    service.serviceName shouldBe "TestOptionalParamService"
    val search = service.methods.head
    search.params.size shouldBe 2
    search.params(1).name shouldBe "limit"
    search.params(1).typeName.isOption shouldBe true
  }

  test("scan and generate RPC client source") {
    val tastyPath = findTastyFile("wvlet.uni.http.codegen.TestUserService")
    val service   = TastyServiceScanner.scan(tastyPath)
    val config    = CodegenConfig(
      apiClassName = service.fullName,
      clientType = ClientType.Sync,
      targetPackage = Some("wvlet.uni.http.codegen.generated")
    )
    val source = HttpCodeGenerator.generateSource(service, config)

    source shouldContain "package wvlet.uni.http.codegen.generated"
    source shouldContain "object TestUserServiceClient:"
    source shouldContain "class SyncClient(client: HttpSyncClient):"
    source shouldContain "def getUser(id: Long): User"
    source shouldContain "def createUser(name: String, email: String): User"
    source shouldContain "def deleteUser(id: Long): Unit"
  }

end TastyServiceScannerTest
