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

import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxStream
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

trait TestStreamService:
  def findUser(id: Long): Rx[Option[User]]
  def countUsers(): Rx[Int]
  def watchUsers(filter: Map[String, String]): RxStream[User]

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
    source shouldContain "RPCClient.of[wvlet.uni.http.codegen.TestUserService]"
  }

  // Types under wvlet.uni. render by their short name, so User here is wvlet.uni.http.codegen.User
  test("read type arguments and RxStream") {
    val service              = ServiceScanner.scan("wvlet.uni.http.codegen.TestStreamService", cl)
    def method(name: String) = service.methods.find(_.name == name).get

    val listUsers =
      ServiceScanner
        .scan("wvlet.uni.http.codegen.TestUserService", cl)
        .methods
        .find(_.name == "listUsers")
        .get
    listUsers.returnType.render shouldBe "Seq[User]"

    val findUser = method("findUser")
    findUser.isStream shouldBe false
    findUser.returnType.render shouldBe "Rx[Option[User]]"
    findUser.valueType.render shouldBe "Option[User]"

    // Scala erases primitive type arguments in generic signatures
    method("countUsers").valueType shouldBe TypeRef.Any

    val watchUsers = method("watchUsers")
    watchUsers.isStream shouldBe true
    watchUsers.valueType.render shouldBe "User"
    watchUsers.params.head.typeName.render shouldBe "Map[String, String]"
  }

  test("generate stream calls for RxStream methods") {
    val service = ServiceScanner.scan("wvlet.uni.http.codegen.TestStreamService", cl)
    val source  = HttpCodeGenerator.generateSource(
      service,
      CodegenConfig(apiClassName = service.fullName, clientType = ClientType.Both)
    )

    source shouldContain "def watchUsers(filter: Map[String, String]): RxStream[User]"
    source shouldContain """RxStream(rpcClient.stream[User]("watchUsers", filter))"""
    source shouldContain "def findUser(id: Long): Rx[Option[User]]"
    source shouldContain "def findUser(id: Long): Option[User]"
    source shouldContain "// watchUsers returns RxStream: call it through AsyncClient"
    source shouldContain """rpcClient.call[Option[User]]("findUser", id)"""
  }

end ServiceScannerTest
