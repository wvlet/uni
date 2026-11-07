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

import wvlet.uni.electron.{
  ElectronChannelFactory,
  ElectronIPC,
  ElectronRPCServer,
  ElectronRPCServerConfig
}
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
  *   val rpc     = RPCClient.of[CounterApi].newAsyncClient(testbed.newAsyncClient)
  *   rpc.call[CounterState]("increment").map { st =>
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

  private val listeners = mutable.Map.empty[String, js.Function3[js.Any, js.Any, js.Any, Unit]]

  // Stream callbacks by call id, and the fake webContents' `destroyed` listeners
  private val streams            = mutable.Map.empty[Double, js.Function1[js.Dynamic, Unit]]
  private val destroyedListeners = mutable.ArrayBuffer.empty[js.Function0[Unit]]
  private var nextStreamId       = 0.0
  private var destroyed          = false

  /**
    * A fake `ipcMain` to pass to [[ElectronRPCServer.serve]]. Its `handle(channel, fn)` and
    * `on(channel, fn)` record the handler per channel so [[bridge]] can dispatch to it.
    */
  val ipcMain: js.Dynamic = js
    .Dynamic
    .literal(
      handle =
        (
            (channel: js.Any, fn: js.Any) =>
              handlers(channel.asInstanceOf[String]) = fn.asInstanceOf[
                js.Function2[js.Any, js.Any, js.Promise[js.Object]]
              ]
        ): js.Function2[js.Any, js.Any, Unit],
      on =
        (
            (channel: js.Any, fn: js.Any) =>
              listeners(channel.asInstanceOf[String]) = fn.asInstanceOf[
                js.Function3[js.Any, js.Any, js.Any, Unit]
              ]
        ): js.Function2[js.Any, js.Any, Unit]
    )

  /**
    * The renderer's fake `webContents` (`event.sender`). Like Electron, it delivers stream messages
    * asynchronously and in order.
    */
  private val webContents: js.Dynamic = js
    .Dynamic
    .literal(
      send =
        (
            (_channel: js.Any, id: js.Any, msg: js.Any) =>
              js.Promise
                .resolve[Unit](())
                .`then`[Unit] { _ =>
                  val key = id.asInstanceOf[Double]
                  streams
                    .get(key)
                    .foreach { cb =>
                      val m    = msg.asInstanceOf[js.Dynamic]
                      val kind = m.kind.asInstanceOf[String]
                      if kind == "end" || kind == "error" then
                        streams.remove(key)
                      cb(m)
                    }
                }
              ()
        ): js.Function3[js.Any, js.Any, js.Any, Unit],
      isDestroyed = (() => destroyed): js.Function0[Boolean],
      once =
        ((_name: js.Any, fn: js.Function0[Unit]) => destroyedListeners += fn): js.Function2[
          js.Any,
          js.Function0[Unit],
          Unit
        ],
      removeListener =
        ((_name: js.Any, fn: js.Function0[Unit]) => destroyedListeners -= fn): js.Function2[
          js.Any,
          js.Function0[Unit],
          Unit
        ]
    )

  /** Simulate closing the renderer window: its `webContents` is destroyed. */
  def closeRenderer(): Unit =
    destroyed = true
    val fs = destroyedListeners.toList
    destroyedListeners.clear()
    streams.clear()
    fs.foreach(_())

  /** The number of `destroyed` listeners on the fake `webContents`, for leak checks in tests. */
  def destroyedListenerCount: Int = destroyedListeners.size

  /**
    * A fake preload bridge for the given channel. Forwards `request(payload)` to the served handler
    * and returns its `Promise`, just like `ipcRenderer.invoke(channel, payload)` would.
    *
    * @param channel
    *   the IPC channel name; defaults to [[ElectronIPC.DefaultChannel]] (what `serve` uses by
    *   default).
    * @param senderUrl
    *   URL of the (top-level) frame the simulated calls come from, checked against the server's
    *   [[ElectronRPCServerConfig]].
    */
  def bridge(
      channel: String = ElectronIPC.DefaultChannel,
      senderUrl: String = ElectronTestbed.DefaultSenderUrl
  ): js.Dynamic = js
    .Dynamic
    .literal(
      request =
        (
            (payload: js.Any) =>
              handlers.get(channel) match
                case Some(fn) =>
                  fn(ElectronTestbed.ipcEvent(senderUrl), payload)
                case None =>
                  js.Promise
                    .reject(js.Error(s"No Electron RPC handler registered on channel '${channel}'"))
        ): js.Function1[js.Any, js.Promise[js.Object]],
      stream =
        (
            (payload: js.Any, onMessage: js.Function1[js.Dynamic, Unit]) =>
              listeners.get(ElectronIPC.streamChannel(channel)) match
                case Some(fn) =>
                  nextStreamId += 1
                  streams(nextStreamId) = onMessage
                  val event = ElectronTestbed.ipcEvent(senderUrl)
                  event.updateDynamic("sender")(webContents)
                  // Like Electron's IpcMainEvent.reply: answer the calling frame
                  event.updateDynamic("reply")(
                    (
                        (ch: js.Any, id: js.Any, msg: js.Any) =>
                          webContents.applyDynamic("send")(ch, id, msg)
                    ): js.Function3[js.Any, js.Any, js.Any, Unit]
                  )
                  fn(event, nextStreamId, payload)
                case None =>
                  onMessage(
                    js.Dynamic
                      .literal(
                        kind = "error",
                        message = s"No Electron RPC stream handler on channel '${channel}'"
                      )
                  )
        ): js.Function2[js.Any, js.Function1[js.Dynamic, Unit], Unit]
    )

  /** An [[ElectronChannelFactory]] wired to this testbed's [[bridge]]. */
  def channelFactory(channel: String = ElectronIPC.DefaultChannel): ElectronChannelFactory =
    ElectronChannelFactory(bridge(channel))

  /** A ready-to-use async HTTP client whose requests are tunneled through this testbed's IPC. */
  def newAsyncClient: HttpAsyncClient =
    Http.client.withChannelFactory(channelFactory()).newAsyncClient

end ElectronTestbed

object ElectronTestbed:

  /** Frame URL of the simulated renderer. */
  val DefaultSenderUrl: String = "file:///app/index.html"

  /** A fake `ipcMain.handle` event whose `senderFrame` is a top-level frame at `url`. */
  def ipcEvent(url: String, isTopLevel: Boolean = true): js.Dynamic =
    val parent: js.Any =
      if isTopLevel then
        null
      else
        js.Dynamic.literal(url = url)
    js.Dynamic.literal(senderFrame = js.Dynamic.literal(url = url, parent = parent))

  /** Create a testbed and serve the given routers with an explicit server configuration. */
  def serve(config: ElectronRPCServerConfig, routers: RPCRouter*): ElectronTestbed =
    val testbed = ElectronTestbed()
    ElectronRPCServer.serve(testbed.ipcMain, config, routers*)
    testbed

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

end ElectronTestbed
