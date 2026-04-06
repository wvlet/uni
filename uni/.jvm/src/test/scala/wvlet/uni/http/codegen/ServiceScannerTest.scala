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

// Test service traits
case class User(id: Long, name: String, email: String)

trait TestUserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User
  def deleteUser(id: Long): Unit
  def listUsers(): Seq[User]

trait TestHealthService:
  def ping(): String

class ServiceScannerTest extends UniTest:

  private val cl = getClass.getClassLoader

  test("scan TestUserService trait") {
    val service = ServiceScanner.scan("wvlet.uni.http.codegen.TestUserService", cl)
    service.packageName shouldBe "wvlet.uni.http.codegen"
    service.serviceName shouldBe "TestUserService"
    service.methods.size shouldBe 4

    val getUser = service.methods.find(_.name == "getUser").get
    getUser.httpMethod shouldBe "POST"
    getUser.isRPC shouldBe true
    getUser.params.size shouldBe 1
    getUser.params.head.name shouldBe "id"

    val createUser = service.methods.find(_.name == "createUser").get
    createUser.params.size shouldBe 2

    val deleteUser = service.methods.find(_.name == "deleteUser").get
    deleteUser.returnType.isUnit shouldBe true
  }

  test("scan TestHealthService trait") {
    val service = ServiceScanner.scan("wvlet.uni.http.codegen.TestHealthService", cl)
    service.serviceName shouldBe "TestHealthService"
    service.methods.size shouldBe 1
    service.methods.head.name shouldBe "ping"
  }

  test("scan and generate RPC client source") {
    val service = ServiceScanner.scan("wvlet.uni.http.codegen.TestUserService", cl)
    val config  = CodegenConfig(
      apiClassName = service.fullName,
      clientType = ClientType.Sync,
      targetPackage = Some("wvlet.uni.http.codegen.generated")
    )
    val source = HttpCodeGenerator.generateSource(service, config)

    source shouldContain "package wvlet.uni.http.codegen.generated"
    source shouldContain "object TestUserServiceClient:"
    source shouldContain "class SyncClient(client: HttpSyncClient):"
    source shouldContain "def getUser(id: Long): User"
    source shouldContain "def deleteUser(id: Long): Unit"
    source shouldContain "RPCClient.build"
    source shouldContain "Surface.methodsOf"
  }

end ServiceScannerTest
