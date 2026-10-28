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

import wvlet.uni.http.{
  Http,
  HttpChannel,
  HttpChannelFactory,
  HttpClientConfig,
  Request,
  Response,
  RxHttpHandler
}
import wvlet.uni.json.JSON.{JSONLong, JSONObject, JSONString}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxResource, RxRunner}
import wvlet.uni.test.UniTest

/**
  * In-memory HTTP channel that routes each request to an [[RxHttpHandler]] without opening a
  * socket. This exercises the full client path — `DefaultHttpSyncClient` → channel →
  * `MCPHttpHandler` → `MCPServer` — on every platform, including Scala.js and Scala Native where
  * `Rx.await` is unavailable. The handler's Rx is driven with [[RxRunner.runOnce]], which works for
  * the immediate (in-memory) streams produced by `MCPHttpHandler`.
  */
private class InMemoryChannel(handler: RxHttpHandler) extends HttpChannel:
  override def send(request: Request, config: HttpClientConfig): Response =
    var result: Response = null
    var error: Throwable = null
    RxRunner.runOnce(handler.handle(request)) {
      case OnNext(r: Response) =>
        result = r
      case OnNext(_) =>
        throw IllegalStateException("MCP handler must produce a Response")
      case OnError(e) =>
        error = e
      case OnCompletion =>
    }
    if error != null then
      throw error
    result

  override def close(): Unit = ()

private class InMemoryChannelFactory(handler: RxHttpHandler) extends HttpChannelFactory:
  override def newChannel: HttpChannel = InMemoryChannel(handler)
  override def newAsyncChannel         =
    throw NotImplementedError("The in-memory channel supports the sync client only")

trait GreeterService:
  @description("Greet a person by name")
  def hello(name: String): String

  @description("Add two integers")
  def add(x: Int, y: Int): Int

  @description("Throw an exception")
  def explode(message: String): String

class GreeterServiceImpl extends GreeterService:
  def hello(name: String): String      = s"Hello, ${name}!"
  def add(x: Int, y: Int): Int         = x + y
  def explode(message: String): String = throw IllegalStateException(message)

class MCPClientTest extends UniTest:

  private def newServer: MCPServer = MCP
    .server
    .withName("greeter")
    .withVersion("0.0.1")
    .withTools[GreeterService](GreeterServiceImpl())

  private def clientConfig(server: MCPServer = newServer): MCPClientConfig = MCP
    .client
    .withHttpClientConfig(
      Http.client.withChannelFactory(InMemoryChannelFactory(server.httpHandler))
    )

  private def connect(server: MCPServer = newServer): Rx[MCPClient] = clientConfig(server).connect(
    "http://test/mcp"
  )

  /** A client before the handshake, to drive `initialize`/`notifications/initialized` directly. */
  private def rawClient(server: MCPServer = newServer): MCPClient =
    val config = clientConfig(server)
    MCPClient(config, config.newHttpClient, "http://test/mcp")

  /**
    * Run `body` with a connected MCP client. `close()` is guaranteed to run even when `body` fails,
    * so a failed assertion cannot leak the HTTP client.
    */
  private def withMCPClient[U](body: MCPClient => Rx[U]): Rx[U] = RxResource
    .fromAutoCloseable(connect())
    .use(body)

  /** Like [[withMCPClient]] but with a not-yet-handshaken client. */
  private def withRawClient[U](body: MCPClient => Rx[U]): Rx[U] = RxResource
    .make(Rx.single(rawClient()))(c => Rx.single(c.close()))
    .use(body)

  test("connect performs the MCP handshake and exposes server info") {
    withMCPClient { client =>
      for tools <- client.listTools()
      yield
        // A usable session after connect: the handshake (initialize + initialized) completed
        tools.map(_.name).toSet shouldBe Set("add", "explode", "hello")
    }
  }

  test("MCP.client sends the configured client info and protocol version") {
    val server   = newServer
    val requests = List.newBuilder[Request]
    val handler  = RxHttpHandler { request =>
      requests += request
      server.httpHandler.handle(request)
    }
    RxResource
      .fromAutoCloseable(
        MCP
          .client
          .withName("my-agent")
          .withVersion("1.2.3")
          .withProtocolVersion("2025-03-26")
          .withHttpClientConfig(Http.client.withChannelFactory(InMemoryChannelFactory(handler)))
          .connect("http://test/mcp")
      )
      .use { client =>
        client
          .ping()
          .map { _ =>
            val sent = requests.result()
            val init = sent.head.content.asString.getOrElse("")
            init shouldContain "\"clientInfo\":{\"name\":\"my-agent\",\"version\":\"1.2.3\"}"
            init shouldContain "\"protocolVersion\":\"2025-03-26\""
            // initialize negotiates the version; later requests carry the agreed one
            sent.map(_.header(MCPHttpHandler.ProtocolVersionHeader)) shouldBe
              List(None, Some("2025-03-26"), Some("2025-03-26"))
          }
      }
  }

  test("an unsupported requested protocol version falls back to the server's version") {
    RxResource
      .fromAutoCloseable(
        clientConfig().withProtocolVersion("2024-11-05").connect("http://test/mcp")
      )
      .use(_.ping())
  }

  test("initialize returns the server info from the handshake") {
    withRawClient { client =>
      for info <- client.initialize()
      yield
        info.name shouldBe "greeter"
        info.version shouldBe "0.0.1"
        info.protocolVersion shouldBe MCPServer.LatestProtocolVersion
        info.capabilities shouldContain "tools"
    }
  }

  test("list tools returned by the server") {
    withMCPClient { client =>
      for tools <- client.listTools()
      yield
        tools.map(_.name).toSet shouldBe Set("add", "explode", "hello")
        tools.find(_.name == "hello").flatMap(_.description) shouldBe Some("Greet a person by name")
        tools.find(_.name == "add").flatMap(_.inputSchema.get("properties")).isDefined shouldBe true
    }
  }

  test("call a tool and get its result") {
    withMCPClient { client =>
      for result <- client.callTool("hello", JSONObject(Seq("name" -> JSONString("MCP"))))
      yield
        result.isError shouldBe false
        result.content shouldBe Seq(MCPContent.Text("Hello, MCP!"))
    }
  }

  test("call a tool with numeric arguments") {
    withMCPClient { client =>
      for result <- client.callTool("add", JSONObject(Seq("x" -> JSONLong(1), "y" -> JSONLong(2))))
      yield
        result.isError shouldBe false
        result.content shouldBe Seq(MCPContent.Text("3"))
    }
  }

  test("tool execution failures are isError results, not exceptions") {
    withMCPClient { client =>
      for result <- client.callTool("explode", JSONObject(Seq("message" -> JSONString("boom"))))
      yield
        result.isError shouldBe true
        result.content shouldMatch { case Seq(MCPContent.Text(text)) =>
          text shouldContain "boom"
        }
    }
  }

  test("invalid arguments are reported as JSON-RPC errors") {
    withMCPClient { client =>
      for error <- client
          .callTool("add", JSONObject(Seq.empty))
          .recover { case e: MCPClientException =>
            e
          }
      yield error shouldMatch { case e: MCPClientException =>
        e.code shouldBe JsonRpc.InvalidParams
      }
    }
  }

  test("calling an unknown tool is a JSON-RPC error") {
    withMCPClient { client =>
      for error <- client
          .callTool("no_such_tool", JSONObject(Seq.empty))
          .recover { case e: MCPClientException =>
            e
          }
      yield error shouldMatch { case e: MCPClientException =>
        e.code shouldBe JsonRpc.InvalidParams
      }
    }
  }

  test("ping keeps the session alive") {
    withMCPClient { client =>
      client.ping()
    }
  }

  test("notifications/initialized is answered with 202 and no body") {
    withRawClient { client =>
      client.notifyInitialized()
    }
  }

end MCPClientTest
