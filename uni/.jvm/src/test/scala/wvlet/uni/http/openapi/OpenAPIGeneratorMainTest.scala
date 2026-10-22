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
package wvlet.uni.http.openapi

import wvlet.uni.http.HttpMethod
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object GeneratorMainApi:
  class PingApi:
    @Endpoint(HttpMethod.GET, "/ping")
    def ping(): String = "pong"

  val router: Router = Router.of[PingApi]

class OpenAPIGeneratorMainTest extends UniTest:

  test("write YAML for an object member that returns a Router") {
    val dir = Files.createTempDirectory("openapi")
    val out = dir.resolve("doc/openapi.yaml")
    OpenAPIGeneratorMain.main(
      Array(
        "--router",
        "wvlet.uni.http.openapi.GeneratorMainApi.router",
        "--title",
        "Ping",
        "--version",
        "2.0",
        "--out",
        out.toString
      )
    )
    val yaml = Files.readString(out, StandardCharsets.UTF_8)
    yaml shouldContain "title: Ping"
    yaml shouldContain "version: '2.0'"
    yaml shouldContain "/ping:"
  }

  test("fail on a router spec that does not resolve") {
    intercept[IllegalArgumentException] {
      OpenAPIGeneratorMain.loadRouter("wvlet.uni.http.openapi.NoSuchApi.router")
    }
  }

end OpenAPIGeneratorMainTest
