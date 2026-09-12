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
package wvlet.uni.http.netty

import wvlet.uni.json.JSON.{JSONObject, JSONString}
import wvlet.uni.mcp.{MCPClient, MCPContent, MCPServer, description}
import wvlet.uni.test.UniTest

trait NettyGreeterService:
  @description("Greet a person by name")
  def hello(name: String): String

class NettyGreeterServiceImpl extends NettyGreeterService:
  def hello(name: String): String = s"Hello, ${name}!"

/**
  * End-to-end test of [[MCPClient]] against a real MCP server mounted on a NettyServer (the
  * practice of connecting a client to a real HTTP socket).
  */
class MCPClientNettyServerTest extends UniTest:

  test("call an MCP server mounted on NettyServer") {
    val mcp = MCPServer()
      .withName("greeter")
      .withVersion("0.0.1")
      .withTools[NettyGreeterService](NettyGreeterServiceImpl())

    NettyServer
      .withPort(0)
      .withRxHandler(mcp.httpHandler)
      .startAndAwait { server =>
        for
          client <- MCPClient.connect(s"http://localhost:${server.localPort}/mcp")
          tools  <- client.listTools()
          result <- client.callTool("hello", JSONObject(Seq("name" -> JSONString("MCP"))))
        yield
          tools.map(_.name) shouldContain "hello"
          result.isError shouldBe false
          result.content shouldBe Seq(MCPContent.Text("Hello, MCP!"))
          client.close()
      }
  }

end MCPClientNettyServerTest
