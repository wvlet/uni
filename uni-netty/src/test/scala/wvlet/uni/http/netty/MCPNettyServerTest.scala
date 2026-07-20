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

import wvlet.uni.mcp.{MCPServer, description}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

trait GreeterService:
  @description("Greet a person by name")
  def hello(name: String): String

class GreeterServiceImpl extends GreeterService:
  def hello(name: String): String = s"Hello, ${name}!"

/**
  * End-to-end test of the MCP Streamable HTTP transport on a real Netty server.
  */
class MCPNettyServerTest extends UniTest:

  private val httpClient = HttpClient.newHttpClient()

  private def postJson(url: String, body: String): HttpResponse[String] = httpClient.send(
    HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build(),
    HttpResponse.BodyHandlers.ofString()
  )

  private def mcp: MCPServer = MCPServer()
    .withName("greeter")
    .withVersion("0.0.1")
    .withTools[GreeterService](GreeterServiceImpl())

  test("serve an MCP session over HTTP") {
    NettyServer
      .withPort(0)
      .withRxHandler(mcp.httpHandler)
      .start { server =>
        val url = s"http://localhost:${server.localPort}/mcp"

        val init = postJson(
          url,
          """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        )
        init.statusCode() shouldBe 200
        init.body() shouldContain "\"protocolVersion\":\"2025-06-18\""
        init.body() shouldContain "\"name\":\"greeter\""

        val notified = postJson(url, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        notified.statusCode() shouldBe 202

        val tools = postJson(url, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        tools.statusCode() shouldBe 200
        tools.body() shouldContain "\"name\":\"hello\""
        tools.body() shouldContain "Greet a person by name"

        val call = postJson(
          url,
          """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hello","arguments":{"name":"MCP"}}}"""
        )
        call.statusCode() shouldBe 200
        call.body() shouldContain "Hello, MCP!"
      }
  }

end MCPNettyServerTest
