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
package wvlet.uni.mcp

import wvlet.uni.http.{HttpMethod, HttpStatus, Request, Response}
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONObject, JSONString}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

class MCPHttpHandlerTest extends UniTest:

  private def newServer: MCPServer = MCPServer()
    .withName("calc")
    .withVersion("1.2.3")
    .withTools[CalcService](CalcServiceImpl())

  private def post(body: String): Request = Request(HttpMethod.POST, "/mcp").withJsonContent(body)

  private val initializeMessage =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""

  private val toolCallMessage =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add","arguments":{"x":1,"y":2}}}"""

  test("answer a POSTed request with application/json") {
    newServer
      .httpHandler
      .handle(post(initializeMessage))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
        val json = JSON.parse(response.contentAsString.get).asInstanceOf[JSONObject]
        json.get("result").get.asInstanceOf[JSONObject].get("protocolVersion") shouldBe
          Some(JSONString("2025-06-18"))
      }
  }

  test("round-trip a tools/call over HTTP") {
    newServer
      .httpHandler
      .handle(post(toolCallMessage))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
        response.contentAsString.get shouldContain "\"text\":\"3\""
      }
  }

  test("answer notifications with 202 Accepted") {
    newServer
      .httpHandler
      .handle(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
      .map { response =>
        response.status shouldBe HttpStatus.Accepted_202
      }
  }

  test("reject non-POST methods with 405") {
    newServer
      .httpHandler
      .handle(Request(HttpMethod.GET, "/mcp"))
      .map { response =>
        response.status shouldBe HttpStatus.MethodNotAllowed_405
      }
  }

  test("allow localhost origins") {
    newServer
      .httpHandler
      .handle(post(initializeMessage).addHeader("Origin", "http://localhost:3000"))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
      }
  }

  test("allow IPv6 loopback origins") {
    newServer
      .httpHandler
      .handle(post(initializeMessage).addHeader("Origin", "http://[::1]:8080"))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
      }
  }

  test("reject non-local origins with 403") {
    newServer
      .httpHandler
      .handle(post(initializeMessage).addHeader("Origin", "https://evil.example.com"))
      .map { response =>
        response.status shouldBe HttpStatus.Forbidden_403
      }
  }

  test("reject IPv6-literal origin-bypass attempts with 403") {
    val handler = newServer.httpHandler
    Rx.zip(
        handler.handle(post(initializeMessage).addHeader("Origin", "http://[::1].evil.com")),
        handler.handle(post(initializeMessage).addHeader("Origin", "http://[::1]evil.com:80")),
        handler.handle(post(initializeMessage).addHeader("Origin", "http://localhost.evil.com"))
      )
      .map { (bracketDot, bracketPlain, subdomain) =>
        bracketDot.status shouldBe HttpStatus.Forbidden_403
        bracketPlain.status shouldBe HttpStatus.Forbidden_403
        subdomain.status shouldBe HttpStatus.Forbidden_403
      }
  }

  test("allow origins registered via withAllowedOrigins") {
    newServer
      .withAllowedOrigins("https://app.example.com")
      .httpHandler
      .handle(post(initializeMessage).addHeader("Origin", "https://app.example.com"))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
      }
  }

  test("reject unsupported MCP-Protocol-Version headers with 400") {
    newServer
      .httpHandler
      .handle(post(initializeMessage).addHeader("MCP-Protocol-Version", "1999-01-01"))
      .map { response =>
        response.status shouldBe HttpStatus.BadRequest_400
      }
  }

  test("accept supported MCP-Protocol-Version headers") {
    newServer
      .httpHandler
      .handle(post(toolCallMessage).addHeader("MCP-Protocol-Version", "2025-06-18"))
      .map { response =>
        response.status shouldBe HttpStatus.Ok_200
      }
  }

end MCPHttpHandlerTest
