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
package wvlet.uni.http

import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}
import wvlet.uni.surface.{secret, Surface}
import wvlet.uni.test.UniTest
import wvlet.uni.util.ULID

object HttpAccessLogRPCArgsTest:
  case class Message(
      role: String,
      @secret
      text: String
  )

  case class ChatRequest(
      model: String,
      maxTokens: Int,
      @secret
      messages: Seq[Message],
      history: Seq[Message] = Seq.empty,
      last: Option[Message] = None,
      byName: Map[String, Message] = Map.empty
  )

  sealed trait Block
  case class TextBlock(
      @secret
      text: String
  ) extends Block

  enum Color:
    case Red

  enum Credential:
    case Token(
        @secret
        value: String
    )

  trait ChatApi:
    def chat(req: ChatRequest): String
    def login(
        user: String,
        @secret
        password: String
    ): String

    def fail(model: String): String

  class ChatApiImpl extends ChatApi:
    def chat(req: ChatRequest): String = "ok"
    def login(
        user: String,
        @secret
        password: String
    ): String = "ok"

    def fail(model: String): String = throw IllegalStateException("boom")

  trait Values:
    def values(
        id: ULID,
        block: Block,
        color: Color,
        credential: Credential,
        arr: Array[Message],
        request: Request
    ): Unit

end HttpAccessLogRPCArgsTest

class HttpAccessLogRPCArgsTest extends UniTest:
  import HttpAccessLogRPCArgsTest.*

  private val router     = RPCRouter.of[ChatApi](ChatApiImpl())
  private val dispatcher = RPCDispatcher(router)
  private val service    = router.serviceName

  private def call(
      method: String,
      json: String,
      config: HttpAccessLogConfig = HttpAccessLogConfig()
  ): Map[String, Any] =
    val w      = HttpAccessLogWriter.inMemory()
    val filter = HttpAccessLogFilter(w, config)
    val req    = Request.post(s"/${service}/${method}").withJsonContent(s"""{"request":${json}}""")
    filter(req, RxHttpHandler(dispatcher.dispatch)).run(_ => ())
    w.entries.size shouldBe 1
    w.entries.head

  private def rpcArgs(entry: Map[String, Any]): Map[String, Any] = entry("rpc_args").asInstanceOf[
    Map[String, Any]
  ]

  test("log RPC method arguments without @secret values") {
    val e = call("login", """{"user":"leo","password":"p@ss"}""")
    e("rpc_interface") shouldBe service
    e("rpc_class") shouldBe classOf[ChatApiImpl].getName
    e("rpc_method") shouldBe "login"
    rpcArgs(e) shouldBe Map("user" -> "leo")
    HttpAccessLogWriter.toJson(e).contains("p@ss") shouldBe false
  }

  test("drop @secret fields inside case classes, Seq, Option, and Map") {
    val e = call(
      "chat",
      """{"req":{"model":"m1","maxTokens":10,
        |"messages":[{"role":"user","text":"hidden1"}],
        |"history":[{"role":"user","text":"hidden2"}],
        |"last":{"role":"assistant","text":"hidden3"},
        |"byName":{"a":{"role":"user","text":"hidden4"}}}}""".stripMargin
    )
    rpcArgs(e) shouldBe
      Map(
        "req" ->
          Map(
            "model"     -> "m1",
            "maxTokens" -> 10,
            "history"   -> Seq(Map("role" -> "user")),
            "last"      -> Some(Map("role" -> "assistant")),
            "byName"    -> Map("a" -> Map("role" -> "user"))
          )
      )
    HttpAccessLogWriter.toJson(e).contains("hidden") shouldBe false
  }

  test("log the arguments when the method throws") {
    val e = call("fail", """{"model":"m1"}""")
    e("rpc_method") shouldBe "fail"
    rpcArgs(e) shouldBe Map("model" -> "m1")
  }

  test("log the method without arguments when decoding fails") {
    val e = call("login", """[1]""")
    e("rpc_method") shouldBe "login"
    e.contains("rpc_args") shouldBe false
  }

  test("noRpcArgs keeps the method fields but leaves out the arguments") {
    val e = call("login", """{"user":"leo","password":"p"}""", HttpAccessLogConfig().noRpcArgs())
    e("rpc_method") shouldBe "login"
    e.contains("rpc_args") shouldBe false
  }

  test("log values that can't be inspected as their class name") {
    val method = Surface.methodsOf[Values].find(_.name == "values").get
    val id     = ULID.newULID
    val entry  = RPCArgsLog.entries(
      HttpAccessLogFilter.RPCCall(
        "api",
        "impl",
        method,
        Seq(
          id,
          TextBlock("hidden1"),
          Color.Red,
          Credential.Token("hidden2"),
          Array(Message("user", "hidden3")),
          Request.get("/")
        )
      ),
      withArgs = true
    )
    val args = entry("rpc_args").asInstanceOf[Map[String, Any]]
    args("id") shouldBe id
    args("block") shouldBe classOf[TextBlock].getName
    args("color") shouldBe Color.Red
    args("credential") shouldBe Credential.Token("").getClass.getName
    args("arr") shouldBe Seq(Map("role" -> "user"))
    args.contains("request") shouldBe false
    HttpAccessLogWriter.toJson(entry).contains("hidden") shouldBe false
  }

end HttpAccessLogRPCArgsTest
