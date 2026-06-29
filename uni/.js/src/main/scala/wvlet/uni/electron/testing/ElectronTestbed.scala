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
package wvlet.uni.electron.testing

import wvlet.uni.electron.{ElectronChannelFactory, ElectronIPC, ElectronRPCServer}
import wvlet.uni.http.{Http, HttpAsyncClient}
import wvlet.uni.http.rpc.RPCRouter

import scala.collection.mutable
import scala.scalajs.js

/**
  * In-memory Electron IPC harness for **integration-testing RPC-over-IPC services without a real
  * Electron runtime** — the third layer of Uni's testing pyramid (unit → UI → Electron → plugin).
  *
  * A testbed plays both sides of the Electron IPC boundary:
  *   - [[ipcMain]] is a fake `ipcMain` you hand to [[ElectronRPCServer.serve]] (the main process).
  *   - [[bridge]] is a fake preload bridge an [[ElectronChannelFactory]] calls (the renderer).
  *
  * Requests forwarded through [[bridge]] are routed to the handler that `serve` registered on the
  * matching channel, exactly mirroring the renderer→main hop, so the full marshalling +
  * `RPCDispatcher` path is exercised in a plain headless-browser/Node test.
  *
  * {{{
  *   val testbed = ElectronTestbed.serve(RPCRouter.of[CounterApi](CounterApiImpl()))
  *   val client  = testbed.newAsyncClient
  *   rpcClient.callAsync[CounterState](client, "increment", Seq.empty).map { st =>
  *     st.value shouldBe 1
  *   }
  * }}}
  *
  * Ships in the published `uni` JS artifact (no test-framework dependency) so Electron apps can
  * reuse it directly.
  */
class ElectronTestbed:

  private val handlers = mutable
    .Map
    .empty[String, js.Function2[js.Any, js.Any, js.Promise[js.Object]]]

  /**
    * A fake `ipcMain` to pass to [[ElectronRPCServer.serve]]. Its `handle(channel, fn)` records the
    * handler per channel so [[bridge]] can dispatch to it.
    */
  val ipcMain: js.Dynamic = js
    .Dynamic
    .literal(handle =
      (
          (channel: js.Any, fn: js.Any) =>
            handlers(channel.asInstanceOf[String]) = fn.asInstanceOf[
              js.Function2[js.Any, js.Any, js.Promise[js.Object]]
            ]
      ): js.Function2[js.Any, js.Any, Unit]
    )

  /**
    * A fake preload bridge for the given channel. Forwards `request(payload)` to the served handler
    * and returns its `Promise`, just like `ipcRenderer.invoke(channel, payload)` would.
    *
    * @param channel
    *   the IPC channel name; defaults to [[ElectronIPC.DefaultChannel]] (what `serve` uses by
    *   default).
    */
  def bridge(channel: String = ElectronIPC.DefaultChannel): js.Dynamic = js
    .Dynamic
    .literal(request =
      (
          (payload: js.Any) =>
            handlers.get(channel) match
              case Some(fn) =>
                fn(js.undefined.asInstanceOf[js.Any], payload)
              case None =>
                js.Promise
                  .reject(js.Error(s"No Electron RPC handler registered on channel '${channel}'"))
      ): js.Function1[js.Any, js.Promise[js.Object]]
    )

  /** An [[ElectronChannelFactory]] wired to this testbed's [[bridge]]. */
  def channelFactory(channel: String = ElectronIPC.DefaultChannel): ElectronChannelFactory =
    ElectronChannelFactory(bridge(channel))

  /** A ready-to-use async HTTP client whose requests are tunneled through this testbed's IPC. */
  def newAsyncClient: HttpAsyncClient =
    Http.client.withChannelFactory(channelFactory()).newAsyncClient

end ElectronTestbed

object ElectronTestbed:

  /** Create a testbed and serve the given routers on the default channel. */
  def serve(routers: RPCRouter*): ElectronTestbed =
    val testbed = ElectronTestbed()
    ElectronRPCServer.serve(testbed.ipcMain, routers*)
    testbed

  /** Create a testbed and serve the given routers on an explicit channel. */
  def serve(channel: String, routers: RPCRouter*): ElectronTestbed =
    val testbed = ElectronTestbed()
    ElectronRPCServer.serve(testbed.ipcMain, channel, routers*)
    testbed
