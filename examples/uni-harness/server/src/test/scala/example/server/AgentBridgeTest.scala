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
package example.server

import wvlet.uni.agent.UiAgentApi
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.electron.testing.ElectronTestbed
import wvlet.uni.http.{HttpMethod, HttpStatus, Request}
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

/** A renderer-side `UiAgentApi` with one fixed screen, standing in for uni-ui's `UiAgent`. */
class FixedScreen extends UiAgentApi:
  override def snapshot(): Rx[String] = Rx.single("""[{"ref":"button:send","kind":"button"}]""")
  override def query(kind: Option[String], text: Option[String], ref: Option[String]): Rx[String] =
    Rx.single(s"""{"kind":"${kind.getOrElse("")}"}""")

  override def act(ref: String, action: String, value: Option[String]): Rx[String] = Rx.single(
    s"""{"status":"applied","ref":"${ref}","action":"${action}"}"""
  )

  override def changes(since: Long): Rx[String] = Rx.single(s"""{"changes":[],"cursor":${since}}""")

class AgentBridgeTest extends UniTest:

  private val token = AgentBridge.newToken()

  /** The bridge, forwarding over the reverse IPC channel to a renderer serving [[FixedScreen]]. */
  private def bridge() =
    val testbed = ElectronTestbed()
    ElectronRenderer.serve(testbed.bridge(), RPCRouter.of[UiAgentApi](FixedScreen()))
    val client = testbed.newMainClient()
    AgentBridge.handler(AgentBridge.Forwarder(() => Some(client)), token)

  private def post(body: String, auth: Option[String]): Request =
    val r = Request(HttpMethod.POST, AgentBridge.Path).withJsonContent(body)
    auth.fold(r)(a => r.addHeader("Authorization", a))

  private val toolsList = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

  test("newToken makes 32 random bytes as hex") {
    token shouldMatch {
      case t: String if t.matches("[0-9a-f]{64}") =>
    }
    AgentBridge.newToken() shouldNotBe token
  }

  test("list the UiAgentApi tools for a request with the token") {
    bridge()
      .handle(post(toolsList, Some(s"Bearer ${token}")))
      .map { r =>
        r.status shouldBe HttpStatus.Ok_200
        val body = r.contentAsString.getOrElse("")
        Seq("snapshot", "query", "act", "changes").foreach { name =>
          body shouldContain s""""name":"${name}""""
        }
      }
  }

  test("reject a request without a token") {
    bridge()
      .handle(post(toolsList, None))
      .map { r =>
        r.status shouldBe HttpStatus.Unauthorized_401
        r.contentAsString.getOrElse("") shouldNotContain "snapshot"
      }
  }

  test("reject a request with a wrong token") {
    val wrong = AgentBridge.newToken()
    Rx.zip(
        bridge().handle(post(toolsList, Some(s"Bearer ${wrong}"))),
        bridge().handle(post(toolsList, Some(token))),
        bridge().handle(post(toolsList, Some(s"Bearer ${token}x")))
      )
      .map { (a, b, c) =>
        a.status shouldBe HttpStatus.Unauthorized_401
        b.status shouldBe HttpStatus.Unauthorized_401
        c.status shouldBe HttpStatus.Unauthorized_401
      }
  }

  test("answer only on the MCP path") {
    bridge()
      .handle(
        Request(HttpMethod.POST, "/other")
          .withJsonContent(toolsList)
          .addHeader("Authorization", s"Bearer ${token}")
      )
      .map { r =>
        r.status shouldBe HttpStatus.NotFound_404
      }
  }

  test("a tool call reaches the renderer over the reverse IPC channel") {
    val call =
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"act",""" +
        """"arguments":{"ref":"button:send","action":"press"}}}"""
    bridge()
      .handle(post(call, Some(s"Bearer ${token}")))
      .map { r =>
        r.status shouldBe HttpStatus.Ok_200
        val body = r.contentAsString.getOrElse("")
        body shouldContain "applied"
        body shouldContain "button:send"
        body shouldContain "\"isError\":false"
      }
  }

end AgentBridgeTest
