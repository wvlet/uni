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
package wvlet.uni.electron

import wvlet.uni.electron.testing.ElectronTestbed
import wvlet.uni.http.{Http, HttpException}
import wvlet.uni.http.rpc.{RPCClient, RPCRouter, RPCStatus}
import wvlet.uni.rx.Rx
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

import scala.scalajs.js

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def addAsync(a: Int, b: Int): Rx[Int]

class GreetingServiceImpl extends GreetingService:
  override def hello(name: String): Greeting     = Greeting(s"Hello, ${name}!")
  override def addAsync(a: Int, b: Int): Rx[Int] = Rx.single(a + b)

/**
  * Exercises the Electron IPC transport end to end without a real Electron runtime: a fake
  * `ipcMain` captures the handler registered by [[ElectronRPCServer]], and a fake bridge forwards
  * renderer payloads straight into that handler — simulating the renderer↔main IPC hop.
  */
class ElectronRPCTest extends UniTest:

  /**
    * Wire a server router to a renderer-facing bridge through the in-memory [[ElectronTestbed]]
    * harness. Returns the bridge the renderer channel would call.
    */
  private def fakeBridge(routers: RPCRouter*): js.Dynamic = ElectronTestbed.serve(routers*).bridge()

  private def rpcClient: RPCClient = RPCClient.build(
    Surface.of[GreetingService],
    Surface.methodsOf[GreetingService]
  )

  test("round-trips a synchronous RPC method over IPC") {
    val bridge = fakeBridge(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    val client = Http.client.withChannelFactory(ElectronChannelFactory(bridge)).newAsyncClient
    rpcClient
      .callAsync[Greeting](client, "hello", Seq("world"))
      .map { g =>
        g.message shouldBe "Hello, world!"
      }
  }

  test("round-trips an Rx-returning RPC method over IPC") {
    val bridge = fakeBridge(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    val client = Http.client.withChannelFactory(ElectronChannelFactory(bridge)).newAsyncClient
    rpcClient
      .callAsync[Int](client, "addAsync", Seq(2, 3))
      .map { sum =>
        sum shouldBe 5
      }
  }

  test("ElectronRenderer.install wires the default channel factory") {
    val bridge = fakeBridge(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    ElectronRenderer.install(bridge)
    try
      val client = Http.client.newAsyncClient
      rpcClient
        .callAsync[Greeting](client, "hello", Seq("Uni"))
        .map { g =>
          g.message shouldBe "Hello, Uni!"
        }
    finally
      // Restore the default JS channel factory for subsequent tests.
      Http.setDefaultChannelFactory(wvlet.uni.http.JSHttpChannelFactory)
  }

  test("ElectronIPC marshals a request payload round-trip") {
    import wvlet.uni.http.{HttpMethod, Request}
    val req      = Request.post("/svc/method").withJsonContent("""{"request":{"x":1}}""")
    val payload  = ElectronIPC.toPayload(req).asInstanceOf[js.Dynamic]
    val restored = ElectronIPC.toRequest(payload)
    restored.method shouldBe HttpMethod.POST
    restored.path shouldBe "/svc/method"
    restored.content.asString shouldBe Some("""{"request":{"x":1}}""")
  }

  test("ElectronIPC marshals a response payload round-trip") {
    import wvlet.uni.http.{HttpHeader, Response}
    val resp     = Response.ok.addHeader(HttpHeader.XRPCStatus, "0").withJsonContent("""{"v":42}""")
    val payload  = ElectronIPC.fromResponse(resp).asInstanceOf[js.Dynamic]
    val restored = ElectronIPC.toResponse(payload)
    restored.status.code shouldBe 200
    restored.header(HttpHeader.XRPCStatus) shouldBe Some("0")
    restored.contentAsString shouldBe Some("""{"v":42}""")
  }

  test("accept only top-level sender frames by default") {
    val config = ElectronRPCServerConfig()
    config.isAllowedSender(ElectronTestbed.ipcEvent("https://any.example/")) shouldBe true
    config.isAllowedSender(
      ElectronTestbed.ipcEvent("https://ads.example/", isTopLevel = false)
    ) shouldBe false
    config.isAllowedSender(js.Dynamic.literal()) shouldBe false
    config.isAllowedSender(js.Dynamic.literal(senderFrame = null)) shouldBe false
    config.isAllowedSender(js.undefined.asInstanceOf[js.Dynamic]) shouldBe false
    config
      .withAllowSubFrames(true)
      .isAllowedSender(
        ElectronTestbed.ipcEvent("https://ads.example/", isTopLevel = false)
      ) shouldBe true
  }

  test("match allowed origins at a path boundary") {
    val config = ElectronRPCServerConfig().withAllowedOrigins("file://", "http://localhost:5173")
    config.isAllowedSender(ElectronTestbed.ipcEvent("file:///app/index.html")) shouldBe true
    config.isAllowedSender(ElectronTestbed.ipcEvent("http://localhost:5173")) shouldBe true
    config.isAllowedSender(ElectronTestbed.ipcEvent("http://localhost:5173/#/home")) shouldBe true
    config.isAllowedSender(ElectronTestbed.ipcEvent("http://localhost:51730/")) shouldBe false
    config.isAllowedSender(ElectronTestbed.ipcEvent("https://evil.example/")) shouldBe false
  }

  test("apply a custom sender filter") {
    val config = ElectronRPCServerConfig().withSenderFilter(_.url.toString.endsWith("/main.html"))
    config.isAllowedSender(ElectronTestbed.ipcEvent("file:///app/main.html")) shouldBe true
    config.isAllowedSender(ElectronTestbed.ipcEvent("file:///app/other.html")) shouldBe false
    config
      .noSenderFilter
      .isAllowedSender(ElectronTestbed.ipcEvent("file:///app/other.html")) shouldBe true
  }

  test("reject calls from a disallowed sender with PERMISSION_DENIED") {
    val testbed = ElectronTestbed.serve(
      ElectronRPCServerConfig().withAllowedOrigins("app://trusted"),
      RPCRouter.of[GreetingService](GreetingServiceImpl())
    )
    val client =
      Http
        .client
        .withChannelFactory(
          ElectronChannelFactory(testbed.bridge(senderUrl = "https://evil.example/"))
        )
        .newAsyncClient
    rpcClient
      .callAsync[Greeting](client, "hello", Seq("world"))
      .map(_ => "unexpected success")
      .recover { case e: HttpException =>
        e.getMessage
      }
      .map { result =>
        result shouldContain RPCStatus.PERMISSION_DENIED_U14.name
      }
  }

end ElectronRPCTest
