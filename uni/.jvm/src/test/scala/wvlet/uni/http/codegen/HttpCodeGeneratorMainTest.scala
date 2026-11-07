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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class HttpCodeGeneratorMainTest extends UniTest:

  test("write a client for each --client spec") {
    val dir = Files.createTempDirectory("codegen")
    HttpCodeGeneratorMain.run(
      Array(
        "--out",
        dir.toString,
        "--client",
        "wvlet.uni.http.codegen.TestStreamService:async:example.client",
        "--client",
        "wvlet.uni.http.codegen.TestHealthService:sync"
      ),
      getClass.getClassLoader
    )
    val stream = Files.readString(
      dir.resolve("example/client/TestStreamServiceClient.scala"),
      StandardCharsets.UTF_8
    )
    stream shouldContain """rpcClient.stream[User]("watchUsers", filter)"""
    Files.exists(dir.resolve("wvlet/uni/http/codegen/TestHealthServiceClient.scala")) shouldBe true
  }

  test("require --client") {
    intercept[IllegalArgumentException] {
      HttpCodeGeneratorMain.run(Array("--out", "target"), getClass.getClassLoader)
    }
  }

end HttpCodeGeneratorMainTest
