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
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.rpc.{RPCClient, RPCRouter, RPCStatus}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.collection.mutable
import scala.scalajs.js

/** A service the renderer serves to the main process. */
trait PageService:
  def title(): String
  def echo(text: String): Rx[String]
  def never(): Rx[String]

class PageServiceImpl extends PageService:
  override def title(): String                = "Uni Harness"
  override def echo(text: String): Rx[String] = Rx.single(s"echo: ${text}")
  // Never answers, so the call waits until the page goes away or times out
  override def never(): Rx[String] = Rx
    .variable(Option.empty[String])
    .filter(_.isDefined)
    .map(_.get)

/**
  * Main→renderer calls over the reverse channel ([[ElectronMainChannel]] and
  * [[ElectronRenderer.serve]]), through the in-memory [[ElectronTestbed]].
  */
class ElectronReverseRPCTest extends UniTest:

  private val rpc = RPCClient.of[PageService]

  private def served(): (ElectronTestbed, HttpAsyncClient) =
    val testbed = ElectronTestbed()
    ElectronRenderer.serve(testbed.bridge(), RPCRouter.of[PageService](PageServiceImpl()))
    (testbed, testbed.newMainClient())

  /** The error message of a call that must fail; it names the RPC status. */
  private def failureOf(rx: Rx[String]): Rx[String] = rx
    .map(v => s"unexpected success: ${v}")
    .recover { case e: Exception =>
      e.getMessage
    }

  test("the main process calls a method the renderer serves") {
    val (_, client) = served()
    rpc
      .callAsync[String](client, "title", Seq.empty)
      .map { t =>
        t shouldBe "Uni Harness"
      }
  }

  test("the main process calls an Rx-returning renderer method with arguments") {
    val (_, client) = served()
    rpc
      .callAsync[String](client, "echo", Seq("hi"))
      .map { r =>
        r shouldBe "echo: hi"
      }
  }

  test("a renderer that has not served yet answers UNAVAILABLE at once") {
    val testbed = ElectronTestbed()
    testbed.bridge()
    failureOf(rpc.callAsync[String](testbed.newMainClient(), "title", Seq.empty)).map { s =>
      s shouldContain RPCStatus.UNAVAILABLE_I2.name
    }
  }

  test("closing the renderer fails a waiting call with UNAVAILABLE") {
    val (testbed, client) = served()
    val result            = failureOf(rpc.callAsync[String](client, "never", Seq.empty))
    js.timers.setTimeout(0)(testbed.closeRenderer())
    result.map { s =>
      s shouldContain RPCStatus.UNAVAILABLE_I2.name
    }
  }

  test("reloading the renderer fails a waiting call, and the new page must serve again") {
    val (testbed, client) = served()
    val result            = failureOf(rpc.callAsync[String](client, "never", Seq.empty))
    js.timers.setTimeout(0)(testbed.reloadRenderer())
    result
      .flatMap { s =>
        s shouldContain RPCStatus.UNAVAILABLE_I2.name
        failureOf(rpc.callAsync[String](client, "title", Seq.empty))
      }
      .map { s =>
        s shouldContain RPCStatus.UNAVAILABLE_I2.name
      }
  }

  test("a call the renderer does not answer in time fails with DEADLINE_EXCEEDED") {
    val testbed = ElectronTestbed()
    ElectronRenderer.serve(testbed.bridge(), RPCRouter.of[PageService](PageServiceImpl()))
    val client = testbed.newMainClient(ElectronMainChannelConfig().withTimeoutMillis(10))
    failureOf(rpc.callAsync[String](client, "never", Seq.empty)).map { s =>
      s shouldContain RPCStatus.DEADLINE_EXCEEDED_I4.name
    }
  }

  test("closing the client removes its reply listener") {
    val (testbed, client) = served()
    val replyChannel      = ElectronIPC.reverseReplyChannel(ElectronIPC.DefaultChannel)
    testbed.ipcListenerCount(replyChannel) shouldBe 1
    client.close()
    testbed.ipcListenerCount(replyChannel) shouldBe 0
  }

  test("ElectronRenderer.serve fails clearly when the bridge has no serve function") {
    intercept[IllegalStateException] {
      ElectronRenderer.serve(js.Dynamic.literal(), RPCRouter.of[PageService](PageServiceImpl()))
    }
  }

  test("ignore replies from another webContents or from a subframe") {
    // Fakes that let the test send replies itself
    val replyListeners = mutable.ArrayBuffer.empty[js.Function3[js.Any, js.Any, js.Any, Unit]]
    var sentId: js.Any = js.undefined
    val ipcMain        = js
      .Dynamic
      .literal(
        on =
          (
              (_ch: js.Any, fn: js.Function3[js.Any, js.Any, js.Any, Unit]) => replyListeners += fn
          ): js.Function2[js.Any, js.Function3[js.Any, js.Any, js.Any, Unit], Unit],
        removeListener = ((_ch: js.Any, _fn: js.Any) => ()): js.Function2[js.Any, js.Any, Unit]
      )
    val target = js
      .Dynamic
      .literal(send =
        ((_ch: js.Any, id: js.Any, _p: js.Any) => sentId = id): js.Function3[
          js.Any,
          js.Any,
          js.Any,
          Unit
        ]
      )
    val client = ElectronMain.newAsyncClient(
      ipcMain,
      target,
      ElectronMainChannelConfig().withTimeoutMillis(50)
    )
    val result = failureOf(rpc.callAsync[String](client, "title", Seq.empty))
    val ok = js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(), body = "\"forged\"")
    def reply(sender: js.Any, isTopLevel: Boolean): Unit =
      val event = ElectronTestbed.ipcEvent("file:///app/index.html", isTopLevel)
      event.updateDynamic("sender")(sender)
      replyListeners.foreach(_(event, sentId, ok))
    reply(js.Dynamic.literal(), isTopLevel = true)
    reply(target, isTopLevel = false)
    result.map { s =>
      // Neither reply counted, so the call timed out
      s shouldContain RPCStatus.DEADLINE_EXCEEDED_I4.name
    }
  }

end ElectronReverseRPCTest
