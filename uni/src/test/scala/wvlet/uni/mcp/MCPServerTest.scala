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

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{
  JSONArray,
  JSONBoolean,
  JSONLong,
  JSONNull,
  JSONObject,
  JSONString,
  JSONValue
}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class City(name: String, population: Long) derives Weaver

trait CalcService:
  @description("Add two integers")
  def add(
      @description("left operand")
      x: Int,
      y: Int
  ): Int

  def greet(name: String = "world"): String
  def findCity(name: Option[String]): Option[City]
  def mulAsync(x: Int, y: Int): Rx[Int]
  def explode(message: String): String

class CalcServiceImpl extends CalcService:
  def add(x: Int, y: Int): Int                     = x + y
  def greet(name: String): String                  = s"Hello, ${name}!"
  def findCity(name: Option[String]): Option[City] = name.map(n => City(n, 1000))
  def mulAsync(x: Int, y: Int): Rx[Int]            = Rx.single(x * y)
  def explode(message: String): String             = throw IllegalStateException(message)

trait OtherService:
  def add(a: Int, b: Int): Int

class OtherServiceImpl extends OtherService:
  def add(a: Int, b: Int): Int = a + b

class MCPServerTest extends UniTest:

  private def newServer: MCPServer = MCPServer()
    .withName("calc")
    .withVersion("1.2.3")
    .withTools[CalcService](CalcServiceImpl())

  private def request(id: JSONValue, method: String, params: (String, JSONValue)*): String =
    JSONObject(
      Seq("jsonrpc" -> JSONString("2.0"), "id" -> id, "method" -> JSONString(method)) ++
        Option.when(params.nonEmpty)("params" -> JSONObject(params)).toSeq
    ).toJSON

  private def toolCall(id: Long, tool: String, arguments: (String, JSONValue)*): String = request(
    JSONLong(id),
    "tools/call",
    "name"      -> JSONString(tool),
    "arguments" -> JSONObject(arguments)
  )

  private def parseResponse(rx: Rx[Option[String]]): Rx[JSONObject] = rx.map { opt =>
    val line = opt.getOrElse(fail("Expected a response line"))
    // Responses must be single-line for the stdio framing
    line.contains("\n") shouldBe false
    JSON.parse(line).asInstanceOf[JSONObject]
  }

  private def resultOf(response: JSONObject): JSONObject = response
    .get("result")
    .getOrElse(fail("Missing 'result'"))
    .asInstanceOf[JSONObject]

  private def errorOf(response: JSONObject): JSONObject = response
    .get("error")
    .getOrElse(fail("Missing 'error'"))
    .asInstanceOf[JSONObject]

  private def errorCode(response: JSONObject): Long = errorOf(response)
    .get("code")
    .collect { case JSONLong(v) =>
      v
    }
    .getOrElse(fail("Missing error code"))

  private def firstContentText(result: JSONObject): String = result
    .get("content")
    .collect { case a: JSONArray =>
      a(0).asInstanceOf[JSONObject]
    }
    .flatMap(_.get("text"))
    .collect { case JSONString(v) =>
      v
    }
    .getOrElse(fail("Missing content text"))

  test("negotiate protocol version on initialize") {
    parseResponse(
      newServer.handleMessage(
        request(
          JSONLong(1),
          "initialize",
          "protocolVersion" -> JSONString("2025-06-18"),
          "capabilities"    -> JSONObject(Seq.empty)
        )
      )
    ).map { response =>
      response.get("id") shouldBe Some(JSONLong(1))
      val result = resultOf(response)
      result.get("protocolVersion") shouldBe Some(JSONString("2025-06-18"))
      val serverInfo = result.get("serverInfo").get.asInstanceOf[JSONObject]
      serverInfo.get("name") shouldBe Some(JSONString("calc"))
      serverInfo.get("version") shouldBe Some(JSONString("1.2.3"))
    }
  }

  test("fall back to the latest protocol version for unknown client versions") {
    parseResponse(
      newServer.handleMessage(
        request(JSONLong(1), "initialize", "protocolVersion" -> JSONString("1999-01-01"))
      )
    ).map { response =>
      resultOf(response).get("protocolVersion") shouldBe
        Some(JSONString(MCPServer.LatestProtocolVersion))
    }
  }

  test("list derived tools with schemas and descriptions") {
    parseResponse(newServer.handleMessage(request(JSONLong(2), "tools/list"))).map { response =>
      val tools  = resultOf(response).get("tools").get.asInstanceOf[JSONArray]
      val byName =
        tools
          .v
          .map(_.asInstanceOf[JSONObject])
          .map(t =>
            t.get("name")
              .collect { case JSONString(n) =>
                n
              }
              .get -> t
          )
          .toMap

      byName.keySet shouldBe Set("add", "greet", "findCity", "mulAsync", "explode")

      val add = byName("add")
      add.get("description") shouldBe Some(JSONString("Add two integers"))
      val schema     = add.get("inputSchema").get.asInstanceOf[JSONObject]
      val properties = schema.get("properties").get.asInstanceOf[JSONObject]
      properties.get("x").get.asInstanceOf[JSONObject].get("type") shouldBe
        Some(JSONString("integer"))
      properties.get("x").get.asInstanceOf[JSONObject].get("description") shouldBe
        Some(JSONString("left operand"))
      schema.get("required").get.asInstanceOf[JSONArray].v.toSet shouldBe
        Set(JSONString("x"), JSONString("y"))

      // Defaulted and Option parameters are not required
      val greetSchema = byName("greet").get("inputSchema").get.asInstanceOf[JSONObject]
      greetSchema.get("required") shouldBe None
      val findCitySchema = byName("findCity").get("inputSchema").get.asInstanceOf[JSONObject]
      findCitySchema.get("required") shouldBe None
    }
  }

  test("call a synchronous tool") {
    parseResponse(
      newServer.handleMessage(toolCall(3, "add", "x" -> JSONLong(1), "y" -> JSONLong(2)))
    ).map { response =>
      response.get("id") shouldBe Some(JSONLong(3))
      val result = resultOf(response)
      result.get("isError") shouldBe Some(JSONBoolean(false))
      firstContentText(result) shouldBe "3"
    }
  }

  test("call an Rx-returning tool") {
    parseResponse(
      newServer.handleMessage(toolCall(4, "mulAsync", "x" -> JSONLong(2), "y" -> JSONLong(3)))
    ).map { response =>
      firstContentText(resultOf(response)) shouldBe "6"
    }
  }

  test("apply default parameter values") {
    parseResponse(newServer.handleMessage(toolCall(5, "greet"))).map { response =>
      firstContentText(resultOf(response)) shouldBe "\"Hello, world!\""
    }
  }

  test("return a structured result as JSON text") {
    parseResponse(newServer.handleMessage(toolCall(6, "findCity", "name" -> JSONString("Tokyo"))))
      .map { response =>
        val text = firstContentText(resultOf(response))
        JSON.parse(text).asInstanceOf[JSONObject].get("name") shouldBe Some(JSONString("Tokyo"))
      }
  }

  test("missing required arguments are protocol errors (-32602)") {
    parseResponse(newServer.handleMessage(toolCall(7, "add", "x" -> JSONLong(1)))).map { response =>
      errorCode(response) shouldBe JsonRpc.InvalidParams.toLong
    }
  }

  test("unknown tools are protocol errors (-32602)") {
    parseResponse(newServer.handleMessage(toolCall(8, "nonExistentTool"))).map { response =>
      errorCode(response) shouldBe JsonRpc.InvalidParams.toLong
    }
  }

  test("tool execution failures are isError results, not protocol errors") {
    parseResponse(newServer.handleMessage(toolCall(9, "explode", "message" -> JSONString("boom"))))
      .map { response =>
        val result = resultOf(response)
        result.get("isError") shouldBe Some(JSONBoolean(true))
        firstContentText(result) shouldContain "boom"
      }
  }

  test("notifications produce no response") {
    newServer
      .handleMessage(
        JSONObject(
          Seq("jsonrpc" -> JSONString("2.0"), "method" -> JSONString("notifications/initialized"))
        ).toJSON
      )
      .map { response =>
        response shouldBe None
      }
  }

  test("respond to ping with an empty result") {
    parseResponse(newServer.handleMessage(request(JSONLong(10), "ping"))).map { response =>
      resultOf(response).v shouldBe Seq.empty
    }
  }

  test("invalid JSON is a parse error (-32700) with null id") {
    parseResponse(newServer.handleMessage("{not json")).map { response =>
      errorCode(response) shouldBe JsonRpc.ParseError.toLong
      response.get("id") shouldBe Some(JSONNull())
    }
  }

  test("unknown request methods are -32601") {
    parseResponse(newServer.handleMessage(request(JSONLong(11), "resources/list"))).map {
      response =>
        errorCode(response) shouldBe JsonRpc.MethodNotFound.toLong
    }
  }

  test("echo string request ids verbatim") {
    parseResponse(newServer.handleMessage(request(JSONString("req-1"), "ping"))).map { response =>
      response.get("id") shouldBe Some(JSONString("req-1"))
    }
  }

  test("reject tool name collisions across services at registration") {
    intercept[IllegalArgumentException] {
      newServer.withTools[OtherService](OtherServiceImpl())
    }
  }

end MCPServerTest
