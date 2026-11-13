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
  ElectronMain,
  ElectronMainChannelConfig,
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
  * The reverse direction works too: pass [[bridge]] to `ElectronRenderer.serve(bridge, routers*)`,
  * and call the renderer from the main side with [[newMainClient]].
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

  private val listeners = mutable.Map.empty[String, mutable.ArrayBuffer[js.Function]]

  // The handler each reverse channel's renderer registered with `bridge.serve`, or None before
  private val reverseHandlers = mutable.Map.empty[String, Option[js.Function1[js.Any, js.Any]]]

  // Stream callbacks by call id, and the fake webContents' event listeners by event name
  private val streams          = mutable.Map.empty[Double, js.Function1[js.Dynamic, Unit]]
  private val contentsListener = mutable.Map.empty[String, mutable.ArrayBuffer[js.Function]]
  private var nextStreamId     = 0.0
  private var destroyed        = false

  private def addContentsListener(name: js.Any, fn: js.Function): Unit =
    contentsListener.getOrElseUpdate(String.valueOf(name), mutable.ArrayBuffer.empty) += fn

  private def removeContentsListener(name: js.Any, fn: js.Function): Unit = contentsListener
    .get(String.valueOf(name))
    .foreach(_ -= fn)

  private def emit(name: String, args: js.Any*): Unit =
    val fs = contentsListener.get(name).map(_.toList).getOrElse(Nil)
    fs.foreach(_.call(webContents, args*))

  private def send(channel: String, args: js.Any*): Unit = listeners
    .get(channel)
    .map(_.toList)
    .getOrElse(Nil)
    .foreach(_.call(js.undefined, args*))

  /** Like the preload: run a main-process call through the renderer's handler and reply. */
  private def answerReverse(channel: String, id: js.Any, payload: js.Any): Unit =
    val replyChannel                  = channel.stripSuffix("-reverse") + "-reverse-reply"
    def reply(response: js.Any): Unit =
      if !destroyed then
        send(replyChannel, senderEvent(ElectronTestbed.DefaultSenderUrl), id, response)
    reverseHandlers.get(channel).flatten match
      case Some(handler) =>
        js.Promise.resolve[js.Any](handler(payload)).`then`[Unit]((r: js.Any) => reply(r))
      case None =>
        // Not served yet: answer with UNAVAILABLE_I2 instead of leaving the call waiting
        js.Promise
          .resolve[Unit](())
          .`then`[Unit] { _ =>
            reply(js.Dynamic.literal(status = 503, headers = js.Dynamic.literal(), body = ""))
          }

  /** Deliver a `webContents.send` message: a main-process call, or a stream message. */
  private def deliver(channel: String, id: js.Any, msg: js.Any): Unit =
    if reverseHandlers.contains(channel) then
      answerReverse(channel, id, msg)
    else
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
              listeners.getOrElseUpdate(channel.asInstanceOf[String], mutable.ArrayBuffer.empty) +=
                fn.asInstanceOf[js.Function]
        ): js.Function2[js.Any, js.Any, Unit],
      removeListener =
        (
            (channel: js.Any, fn: js.Any) =>
              listeners.get(channel.asInstanceOf[String]).foreach(_ -= fn.asInstanceOf[js.Function])
        ): js.Function2[js.Any, js.Any, Unit]
    )

  /** The number of `ipcMain.on` listeners on `channel`, for leak checks in tests. */
  def ipcListenerCount(channel: String): Int = listeners.get(channel).map(_.size).getOrElse(0)

  /**
    * The renderer's fake `webContents` (`event.sender`), to pass to `ElectronMain.newAsyncClient`.
    * Like Electron, it delivers stream messages and main-process calls asynchronously and in order.
    */
  val webContents: js.Dynamic = js
    .Dynamic
    .literal(
      send =
        (
            (channel: js.Any, id: js.Any, msg: js.Any) => deliver(String.valueOf(channel), id, msg)
        ): js.Function3[js.Any, js.Any, js.Any, Unit],
      isDestroyed = (() => destroyed): js.Function0[Boolean],
      on = (addContentsListener: js.Function2[js.Any, js.Function, Unit]),
      once = (addContentsListener: js.Function2[js.Any, js.Function, Unit]),
      removeListener = (removeContentsListener: js.Function2[js.Any, js.Function, Unit])
    )

  /** Simulate closing the renderer window: its `webContents` is destroyed. */
  def closeRenderer(): Unit =
    destroyed = true
    streams.clear()
    emit("destroyed")
    contentsListener.clear()

  /**
    * Simulate reloading the renderer page: the `webContents` stays, its main frame starts a
    * cross-document navigation (`did-start-navigation`), and the old page's stream callbacks are
    * gone.
    */
  def reloadRenderer(): Unit =
    streams.clear()
    // The new page has not served anything yet
    reverseHandlers.keys.toList.foreach(k => reverseHandlers(k) = None)
    navigate(isSameDocument = false)

  /**
    * Simulate an in-page navigation of the main frame (a hash change or `history.pushState`): the
    * page and its streams stay.
    */
  def navigateInPage(): Unit = navigate(isSameDocument = true)

  private def navigate(isSameDocument: Boolean): Unit =
    val url     = ElectronTestbed.DefaultSenderUrl
    val details = js.Dynamic.literal(url = url, isMainFrame = true, isSameDocument = isSameDocument)
    // Electron passes a details event, followed by the deprecated positional arguments
    emit("did-start-navigation", details, url, isSameDocument, true)

  /** The number of `destroyed` listeners on the fake `webContents`, for leak checks in tests. */
  def destroyedListenerCount: Int = listenerCount("destroyed")

  /** The number of listeners for `event` on the fake `webContents`, for leak checks in tests. */
  def listenerCount(event: String): Int = contentsListener.get(event).map(_.size).getOrElse(0)

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
  ): js.Dynamic =
    val reverse = ElectronIPC.reverseChannel(channel)
    // Like the preload, listen for main-process calls from the start
    reverseHandlers.getOrElseUpdate(reverse, None)
    js.Dynamic
      .literal(
        serve =
          (
              (handler: js.Function1[js.Any, js.Any]) => reverseHandlers(reverse) = Some(handler)
          ): js.Function1[js.Function1[js.Any, js.Any], Unit],
        request =
          (
              (payload: js.Any) =>
                handlers.get(channel) match
                  case Some(fn) =>
                    fn(ElectronTestbed.ipcEvent(senderUrl), payload)
                  case None =>
                    js.Promise
                      .reject(
                        js.Error(s"No Electron RPC handler registered on channel '${channel}'")
                      )
          ): js.Function1[js.Any, js.Promise[js.Object]],
        stream =
          (
              (payload: js.Any, onMessage: js.Function1[js.Dynamic, Unit]) =>
                listeners.get(ElectronIPC.streamChannel(channel)).flatMap(_.headOption) match
                  case Some(fn) =>
                    nextStreamId += 1
                    val id = nextStreamId
                    streams(id) = onMessage
                    fn.call(js.undefined, senderEvent(senderUrl), id, payload)
                    // Like the preload: drop the callback and tell the main process to stop
                    val cancel: js.Function0[Unit] =
                      () =>
                        if streams.remove(id).isDefined then
                          send(ElectronIPC.streamCancelChannel(channel), senderEvent(senderUrl), id)
                    cancel
                  case None =>
                    onMessage(
                      js.Dynamic
                        .literal(
                          kind = "error",
                          message = s"No Electron RPC stream handler on channel '${channel}'"
                        )
                    )
                    js.undefined
          ): js.Function2[js.Any, js.Function1[js.Dynamic, Unit], js.Any]
      )

  end bridge

  /** An `ipcMain.on` event from the renderer's fake `webContents`. */
  private def senderEvent(senderUrl: String): js.Dynamic =
    val event = ElectronTestbed.ipcEvent(senderUrl)
    event.updateDynamic("sender")(webContents)
    // Like Electron's IpcMainEvent.reply: answer the calling frame
    event.updateDynamic("reply")(
      (
          (ch: js.Any, id: js.Any, msg: js.Any) => webContents.applyDynamic("send")(ch, id, msg)
      ): js.Function3[js.Any, js.Any, js.Any, Unit]
    )
    event

  /** An [[ElectronChannelFactory]] wired to this testbed's [[bridge]]. */
  def channelFactory(channel: String = ElectronIPC.DefaultChannel): ElectronChannelFactory =
    ElectronChannelFactory(bridge(channel))

  /** A ready-to-use async HTTP client whose requests are tunneled through this testbed's IPC. */
  def newAsyncClient: HttpAsyncClient =
    Http.client.withChannelFactory(channelFactory()).newAsyncClient

  /**
    * A main-process client that calls the services the renderer serves with
    * `ElectronRenderer.serve(bridge(...), routers*)`, over the reverse channel.
    */
  def newMainClient(
      config: ElectronMainChannelConfig = ElectronMainChannelConfig()
  ): HttpAsyncClient = ElectronMain.newAsyncClient(ipcMain, webContents, config)

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
