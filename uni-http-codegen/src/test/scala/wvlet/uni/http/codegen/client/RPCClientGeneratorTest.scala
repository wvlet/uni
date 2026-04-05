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
package wvlet.uni.http.codegen.client

import wvlet.uni.http.codegen.*
import wvlet.uni.test.UniTest

class RPCClientGeneratorTest extends UniTest:

  private val userType = TypeRef("com.example.model.User", "User")

  private val testService = ServiceDef(
    packageName = "com.example.api",
    serviceName = "UserService",
    methods = Seq(
      MethodDef(
        name = "getUser",
        httpMethod = "POST",
        path = "/com.example.api.UserService/getUser",
        params = Seq(ParamDef("id", TypeRef.Long)),
        returnType = userType,
        isRPC = true
      ),
      MethodDef(
        name = "createUser",
        httpMethod = "POST",
        path = "/com.example.api.UserService/createUser",
        params = Seq(ParamDef("name", TypeRef.String), ParamDef("email", TypeRef.String)),
        returnType = userType,
        isRPC = true
      ),
      MethodDef(
        name = "deleteUser",
        httpMethod = "POST",
        path = "/com.example.api.UserService/deleteUser",
        params = Seq(ParamDef("id", TypeRef.Long)),
        returnType = TypeRef.Unit,
        isRPC = true
      )
    )
  )

  test("generate sync client") {
    val config = CodegenConfig(
      apiClassName = "com.example.api.UserService",
      clientType = ClientType.Sync,
      targetPackage = Some("com.example.client")
    )
    val source = RPCClientGenerator.generate(testService, config)

    // Check package declaration
    source shouldContain "package com.example.client"

    // Check imports
    source shouldContain "import wvlet.uni.http.*"
    source shouldContain "import wvlet.uni.weaver.Weaver"

    // Check object wrapper
    source shouldContain "object UserServiceClient:"

    // Check SyncClient class
    source shouldContain "class SyncClient(client: HttpSyncClient):"

    // Check getUser method
    source shouldContain "def getUser(id: Long): com.example.model.User"
    source shouldContain "/com.example.api.UserService/getUser"
    source shouldContain "Weaver.of[com.example.model.User].fromJSONValue"

    // Check createUser method with multiple params
    source shouldContain "def createUser(name: String, email: String): com.example.model.User"

    // Check deleteUser returns Unit
    source shouldContain "def deleteUser(id: Long): Unit"
  }

  test("generate async client") {
    val config = CodegenConfig(
      apiClassName = "com.example.api.UserService",
      clientType = ClientType.Async,
      targetPackage = Some("com.example.client")
    )
    val source = RPCClientGenerator.generate(testService, config)

    // Check AsyncClient class
    source shouldContain "class AsyncClient(client: HttpAsyncClient):"

    // Check async return types
    source shouldContain "def getUser(id: Long): Rx[com.example.model.User]"
    source shouldContain "def deleteUser(id: Long): Rx[Unit]"

    // Should NOT have SyncClient
    (source.contains("class SyncClient")) shouldBe false
  }

  test("generate both sync and async clients") {
    val config = CodegenConfig(
      apiClassName = "com.example.api.UserService",
      clientType = ClientType.Both,
      targetPackage = Some("com.example.client")
    )
    val source = RPCClientGenerator.generate(testService, config)

    source shouldContain "class SyncClient(client: HttpSyncClient):"
    source shouldContain "class AsyncClient(client: HttpAsyncClient):"
  }

  test("generate client for service with no params") {
    val service = ServiceDef(
      packageName = "com.example.api",
      serviceName = "HealthService",
      methods = Seq(
        MethodDef(
          name = "ping",
          httpMethod = "POST",
          path = "/com.example.api.HealthService/ping",
          params = Seq.empty,
          returnType = TypeRef.String,
          isRPC = true
        )
      )
    )
    val config = CodegenConfig("com.example.api.HealthService", ClientType.Sync)
    val source = RPCClientGenerator.generate(service, config)

    source shouldContain "def ping: String"
    source shouldContain """{"request":{}}"""
  }

  test("use default target package from API class") {
    val config = CodegenConfig(
      apiClassName = "com.example.api.UserService",
      clientType = ClientType.Sync
    )
    val source = RPCClientGenerator.generate(testService, config)

    // Should use the API class's own package
    source shouldContain "package com.example.api"
  }

end RPCClientGeneratorTest
