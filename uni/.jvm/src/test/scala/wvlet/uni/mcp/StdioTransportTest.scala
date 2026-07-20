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
import wvlet.uni.json.JSON.{JSONObject, JSONString}
import wvlet.uni.test.UniTest

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.StandardCharsets

trait EchoService:
  def echo(message: String): String

class StdioTransportTest extends UniTest:

  test("serve a scripted stdio session end-to-end") {
    val session =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
        |{"jsonrpc":"2.0","method":"notifications/initialized"}
        |{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}
        |""".stripMargin

    val originalIn  = System.in
    val originalOut = System.out
    val captured    = ByteArrayOutputStream()
    try
      System.setIn(ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)))
      System.setOut(PrintStream(captured, true, StandardCharsets.UTF_8))
      MCPServer().withName("echo-server").withTools[EchoService](msg => msg).serveStdio()
    finally
      System.setIn(originalIn)
      System.setOut(originalOut)

    val lines = captured.toString(StandardCharsets.UTF_8).linesIterator.toSeq
    // initialize response + tools/call response; the notification produces no output
    lines.size shouldBe 2

    val initResponse = JSON.parse(lines(0)).asInstanceOf[JSONObject]
    initResponse.get("result").get.asInstanceOf[JSONObject].get("protocolVersion") shouldBe
      Some(JSONString("2025-06-18"))

    val callResponse = JSON.parse(lines(1)).asInstanceOf[JSONObject]
    val content      = callResponse.get("result").get.asInstanceOf[JSONObject].get("content").get
    content.toJSON shouldContain "\"text\":\"hi\""
  }

end StdioTransportTest
