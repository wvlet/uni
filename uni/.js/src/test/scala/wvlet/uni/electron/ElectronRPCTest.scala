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
import wvlet.uni.http.rpc.{RPCClient, RPCException, RPCRouter, RPCStatus}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import wvlet.uni.rx.{Cancelable, OnCompletion, OnError, OnNext, RxRunner}

case class Greeting(message: String)

trait GreetingService:
  def hello(name: String): Greeting
  def addAsync(a: Int, b: Int): Rx[Int]
  def countTo(n: Int): Rx[Int]
  def failAfter(n: Int): Rx[Int]
  def ticks(): Rx[Int]

class GreetingServiceImpl extends GreetingService:
  override def hello(name: String): Greeting     = Greeting(s"Hello, ${name}!")
  override def addAsync(a: Int, b: Int): Rx[Int] = Rx.single(a + b)
  override def countTo(n: Int): Rx[Int]          = Rx.fromSeq(1 to n)
  override def failAfter(n: Int): Rx[Int]        = Rx
    .fromSeq(1 to n)
    .concat(Rx.exception(RPCStatus.INVALID_ARGUMENT_U2.newException("too far")))

  /** A stream that never ends by itself; `sent` counts the values it produced. */
  val tick                      = Rx.variable(0)
  var sent                      = 0
  override def ticks(): Rx[Int] = tick.filter(_ > 0).tap(_ => sent += 1)

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

  private def rpcClient: RPCClient = RPCClient.of[GreetingService]

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

  test("ElectronRenderer.installIfAvailable is a no-op without window.uniRPC") {
    // Plain Node.js: no `window` at all
    ElectronRenderer.installIfAvailable() shouldBe false
  }

  test("ElectronRenderer.installIfAvailable installs the bridge from window.uniRPC") {
    val bridge     = fakeBridge(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    val globalThis = js.Dynamic.global.globalThis
    globalThis.updateDynamic("window")(js.Dynamic.literal(uniRPC = bridge))
    try
      ElectronRenderer.installIfAvailable() shouldBe true
      rpcClient
        .callAsync[Greeting](Http.client.newAsyncClient, "hello", Seq("IPC"))
        .map { g =>
          g.message shouldBe "Hello, IPC!"
        }
    finally
      js.special.delete(globalThis, "window")
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

  /** Every value of `rx`, and its failure, once it ends. */
  private def collect[A](rx: Rx[A]): Future[(Seq[A], Option[Throwable])] =
    val values = Seq.newBuilder[A]
    val done   = Promise[(Seq[A], Option[Throwable])]()
    RxRunner.run(rx) {
      case OnNext(v) =>
        values += v.asInstanceOf[A]
      case OnError(e) =>
        done.trySuccess((values.result(), Some(e)))
      case OnCompletion =>
        done.trySuccess((values.result(), None))
    }
    done.future

  /** Let the testbed deliver the stream messages queued so far. */
  private def settle(): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(0)(p.success(()))
    p.future

  private def streamClient(
      testbed: ElectronTestbed,
      senderUrl: String = ElectronTestbed.DefaultSenderUrl
  ) = rpcClient.newAsyncClient(
    Http
      .client
      .withChannelFactory(ElectronChannelFactory(testbed.bridge(senderUrl = senderUrl)))
      .newAsyncClient
  )

  test("streams every value of an Rx over IPC, in order") {
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    collect(streamClient(testbed).stream[Int]("countTo", 5)).map { result =>
      result shouldBe (Seq(1, 2, 3, 4, 5), None)
      testbed.destroyedListenerCount shouldBe 0
      testbed.listenerCount("did-start-navigation") shouldBe 0
    }
  }

  test("a failure while streaming over IPC ends with the server's RPCException") {
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    collect(streamClient(testbed).stream[Int]("failAfter", 2)).map { (values, failure) =>
      values shouldBe Seq(1, 2)
      failure shouldMatch { case Some(e: RPCException) =>
        e.status shouldBe RPCStatus.INVALID_ARGUMENT_U2
      }
    }
  }

  test("reject streams from a disallowed sender with PERMISSION_DENIED") {
    val testbed = ElectronTestbed.serve(
      ElectronRPCServerConfig().withAllowedOrigins("app://trusted"),
      RPCRouter.of[GreetingService](GreetingServiceImpl())
    )
    collect(streamClient(testbed, "https://evil.example/").stream[Int]("countTo", 3)).map {
      (values, failure) =>
        values shouldBe Seq.empty
        failure shouldMatch { case Some(e: HttpException) =>
          e.getMessage shouldContain RPCStatus.PERMISSION_DENIED_U14.name
        }
    }
  }

  test("closing the renderer cancels the server stream") {
    val impl    = GreetingServiceImpl()
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](impl))
    val seen    = Seq.newBuilder[Int]
    RxRunner.run(streamClient(testbed).stream[Int]("ticks")) {
      case OnNext(v) =>
        seen += v.asInstanceOf[Int]
      case _ =>
    }
    for
      _ <- settle()
      _ = impl.tick := 1
      _ <- settle()
      _ = testbed.destroyedListenerCount shouldBe 1
      _ = testbed.closeRenderer()
      _ = impl.tick := 2
      _ <- settle()
    yield
      seen.result() shouldBe Seq(1)
      impl.sent shouldBe 1
  }

  /** Start `ticks`, deliver one value, and return what the renderer saw. */
  private def startTicks(
      impl: GreetingServiceImpl,
      client: wvlet.uni.http.rpc.RPCAsyncClient
  ): Future[(scala.collection.mutable.Builder[Int, Seq[Int]], Cancelable)] =
    val seen         = Seq.newBuilder[Int]
    val subscription =
      RxRunner.run(client.stream[Int]("ticks")) {
        case OnNext(v) =>
          seen += v.asInstanceOf[Int]
        case _ =>
      }
    for
      _ <- settle()
      _ = impl.tick := 1
      _ <- settle()
    yield (seen, subscription)

  test("cancelling the renderer Rx cancels the server stream") {
    val impl    = GreetingServiceImpl()
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](impl))
    for
      (seen, subscription) <- startTicks(impl, streamClient(testbed))
      _ = testbed.destroyedListenerCount shouldBe 1
      _ = subscription.cancel
      _ <- settle()
      _ = impl.tick := 2
      _ <- settle()
    yield
      seen.result() shouldBe Seq(1)
      impl.sent shouldBe 1
      testbed.destroyedListenerCount shouldBe 0
      testbed.listenerCount("did-start-navigation") shouldBe 0
  }

  test("take(n) on a stream cancels the server stream after the nth value") {
    val impl    = GreetingServiceImpl()
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](impl))
    val result  = collect(streamClient(testbed).stream[Int]("ticks").take(1))
    for
      _ <- settle()
      _ = impl.tick := 1
      (values, failure) <- result
      _                 <- settle()
      _ = impl.tick := 2
      _ <- settle()
    yield
      (values, failure) shouldBe (Seq(1), None)
      impl.sent shouldBe 1
  }

  test("reloading the renderer cancels the old page's server streams") {
    val impl    = GreetingServiceImpl()
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](impl))
    for
      (seen, _) <- startTicks(impl, streamClient(testbed))
      _ = testbed.listenerCount("did-start-navigation") shouldBe 1
      _ = testbed.reloadRenderer()
      _ = impl.tick := 2
      _ <- settle()
    yield
      seen.result() shouldBe Seq(1)
      impl.sent shouldBe 1
      testbed.destroyedListenerCount shouldBe 0
      testbed.listenerCount("did-start-navigation") shouldBe 0
  }

  test("an in-page navigation keeps the server stream running") {
    val impl    = GreetingServiceImpl()
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](impl))
    for
      (seen, subscription) <- startTicks(impl, streamClient(testbed))
      _ = testbed.navigateInPage()
      _ = impl.tick := 2
      _ <- settle()
    yield
      seen.result() shouldBe Seq(1, 2)
      impl.sent shouldBe 2
      subscription.cancel
  }

  test("a preload bridge whose stream returns no cancel function still streams") {
    val testbed = ElectronTestbed.serve(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    val inner   = testbed.bridge()
    // An older preload: stream(payload, onMessage) returns undefined
    val bridge = js
      .Dynamic
      .literal(
        request = inner.request,
        stream =
          (
              (payload: js.Any, onMessage: js.Any) =>
                inner.applyDynamic("stream")(payload, onMessage)
                ()
          ): js.Function2[js.Any, js.Any, Unit]
      )
    val client = rpcClient.newAsyncClient(
      Http.client.withChannelFactory(ElectronChannelFactory(bridge)).newAsyncClient
    )
    collect(client.stream[Int]("countTo", 3).take(2)).map { result =>
      result shouldBe (Seq(1, 2), None)
    }
  }

  test("a malformed stream payload ends the call with an error instead of hanging") {
    val testbed  = ElectronTestbed.serve(RPCRouter.of[GreetingService](GreetingServiceImpl()))
    val messages = Seq.newBuilder[String]
    val done     = Promise[Seq[String]]()
    val onMessage: js.Function1[js.Dynamic, Unit] =
      msg =>
        val kind = msg.kind.asInstanceOf[String]
        messages += kind
        if kind == "end" || kind == "error" then
          done.trySuccess(messages.result())
    // No payload object at all: reading the request throws
    testbed.bridge().applyDynamic("stream")(null, onMessage)
    done.future.map(kinds => kinds.last shouldBe "error")
  }

  test("streaming fails clearly when the preload bridge has no stream function") {
    val bridge = js
      .Dynamic
      .literal(request =
        ((_: js.Any) => js.Promise.resolve[js.Object](js.Object())): js.Function1[
          js.Any,
          js.Promise[js.Object]
        ]
      )
    val client = rpcClient.newAsyncClient(
      Http.client.withChannelFactory(ElectronChannelFactory(bridge)).newAsyncClient
    )
    collect(client.stream[Int]("countTo", 1)).map { (_, failure) =>
      failure shouldMatch { case Some(e: NotImplementedError) =>
        e.getMessage shouldContain "stream"
      }
    }
  }

end ElectronRPCTest
