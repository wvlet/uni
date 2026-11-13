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

import wvlet.uni.http.{
  Http,
  HttpAsyncChannel,
  HttpAsyncClient,
  HttpChannel,
  HttpChannelFactory,
  HttpClientConfig,
  HttpHeader,
  HttpRequest,
  HttpResponse,
  HttpStatus
}
import wvlet.uni.http.rpc.RPCStatus
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx

import scala.concurrent.{ExecutionContext, Promise}
import scala.scalajs.js
import scala.scalajs.js.timers.{clearTimeout, setTimeout, SetTimeoutHandle}

/**
  * Configuration of an [[ElectronMainChannel]].
  *
  * @param channel
  *   the RPC channel name; the reverse channels are derived from it (see [[ElectronIPC]]).
  * @param timeoutMillis
  *   how long a call waits for the renderer's reply before it fails with `DEADLINE_EXCEEDED_I4`. 0
  *   waits until the renderer replies, reloads, or closes.
  */
case class ElectronMainChannelConfig(
    channel: String = ElectronIPC.DefaultChannel,
    timeoutMillis: Long = 120000
):
  def withChannel(channel: String): ElectronMainChannelConfig    = copy(channel = channel)
  def withTimeoutMillis(millis: Long): ElectronMainChannelConfig = copy(timeoutMillis = millis)
  def noTimeout: ElectronMainChannelConfig                       = copy(timeoutMillis = 0)

/**
  * Main-process HTTP channel that calls services the **renderer** serves, the reverse of
  * [[ElectronRendererChannel]]. The renderer serves routers with [[ElectronRenderer.serve]].
  *
  * Each call goes to one `webContents` as `webContents.send(reverseChannel, id, payload)`, and the
  * renderer answers on `ipcMain` with `(id, response)` (see [[ElectronIPC]]). A reply counts only
  * when it comes from that `webContents`' top-level frame and names a call still waiting. A waiting
  * call fails with `UNAVAILABLE_I2` when the `webContents` is destroyed or its main frame starts a
  * cross-document navigation (a reload), since the page that would answer is gone, and with
  * `DEADLINE_EXCEEDED_I4` after the configured timeout.
  *
  * `ipcMain` and `webContents` are passed in as values, so this code never calls
  * `require("electron")`.
  *
  * {{{
  * val client = ElectronMain.newAsyncClient(ipcMain, win.webContents)
  * RPCClient.of[UiAgentApi].callAsync[String](client, "snapshot", Seq.empty)
  * }}}
  *
  * Streaming calls are not supported in this direction. [[close]] removes the listeners and fails
  * the calls still waiting.
  */
class ElectronMainChannel(
    ipcMain: js.Dynamic,
    webContents: js.Dynamic,
    config: ElectronMainChannelConfig = ElectronMainChannelConfig()
) extends HttpAsyncChannel
    with LogSupport:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private class Pending(val promise: Promise[HttpResponse], var timer: Option[SetTimeoutHandle])

  private val pending = js.Map.empty[String, Pending]
  // Ids stay unique across channels on the same webContents, which share the reply channel
  private val idPrefix = js.Math.random().toString.drop(2)
  private var nextId   = 0
  private var closed   = false

  private val replyChannel   = ElectronIPC.reverseReplyChannel(config.channel)
  private val requestChannel = ElectronIPC.reverseChannel(config.channel)

  private val onReply: js.Function3[js.Any, js.Any, js.Any, Unit] =
    (event: js.Any, id: js.Any, payload: js.Any) =>
      val e = event.asInstanceOf[js.Dynamic]
      if isFromTarget(e) then
        val key = String.valueOf(id)
        pending
          .get(key)
          .foreach { p =>
            complete(
              key,
              try
                val r = ElectronIPC.toResponse(payload.asInstanceOf[js.Dynamic])
                // A preload's own error reply (such as 503 before the renderer serves) carries no
                // RPC status; give it one so RPC clients report a clear error
                if r.isSuccessful || r.header(HttpHeader.XRPCStatus).isDefined then
                  r
                else if r.status == HttpStatus.ServiceUnavailable_503 then
                  error(RPCStatus.UNAVAILABLE_I2, "The renderer does not serve this channel yet")
                else
                  error(RPCStatus.fromHttpStatus(r.status), r.status.reason)
              catch
                case e: Throwable =>
                  error(RPCStatus.INTERNAL_ERROR_I0, s"Malformed reply: ${e.getMessage}")
            )
          }
      else if !isNullish(e) && (e.sender: js.Any) == (webContents: js.Any) then
        // Only this window's subframes are suspicious; other windows answer their own channels
        warn(s"Ignored a reverse RPC reply from ${ElectronRPCServerConfig.senderUrl(event)}")

  private val onDestroyed: js.Function0[Unit] = () => failAll("The renderer was closed")

  // Electron passes a details event (isMainFrame, isSameDocument); older versions pass positional
  // arguments (url, isInPlace, isMainFrame)
  private val onNavigation: js.Function4[js.Dynamic, js.Any, js.Any, js.Any, Unit] =
    (details: js.Dynamic, _url: js.Any, isInPlace: js.Any, isMainFrame: js.Any) =>
      def flag(name: String, positional: js.Any): Boolean =
        val v =
          if !isNullish(details) && !js.isUndefined(details.selectDynamic(name)) then
            details.selectDynamic(name)
          else
            positional
        v.asInstanceOf[Any] == true
      if flag("isMainFrame", isMainFrame) && !flag("isSameDocument", isInPlace) then
        failAll("The renderer page reloaded")

  ipcMain.applyDynamic("on")(replyChannel, onReply)
  if hasFunction(webContents, "on") then
    webContents.applyDynamic("on")("destroyed", onDestroyed)
    webContents.applyDynamic("on")("did-start-navigation", onNavigation)

  private def isNullish(v: js.Any): Boolean = js.isUndefined(v) || v == null

  private def hasFunction(obj: js.Dynamic, name: String): Boolean =
    !isNullish(obj) && js.typeOf(obj.selectDynamic(name)) == "function"

  private def isGone: Boolean =
    closed || isNullish(webContents) ||
      (hasFunction(webContents, "isDestroyed") && webContents.isDestroyed().asInstanceOf[Boolean])

  private def isFromTarget(event: js.Dynamic): Boolean =
    !isNullish(event) &&
      (event.sender: js.Any) ==
      (webContents: js.Any) && {
        val frame = event.senderFrame
        // Electron always sets senderFrame; a missing one means a caller we cannot check
        !isNullish(frame) && isNullish(frame.parent)
      }

  private def error(status: RPCStatus, message: String): HttpResponse =
    status.newException(message).noStackTrace.toResponse

  private def complete(id: String, response: HttpResponse): Unit = pending
    .get(id)
    .foreach { p =>
      pending.remove(id)
      p.timer.foreach(clearTimeout)
      p.promise.trySuccess(response)
    }

  private def failAll(message: String): Unit = pending
    .keys
    .toList
    .foreach(id => complete(id, error(RPCStatus.UNAVAILABLE_I2, message)))

  override def send(request: HttpRequest, clientConfig: HttpClientConfig): Rx[HttpResponse] =
    if isGone then
      Rx.single(error(RPCStatus.UNAVAILABLE_I2, "The renderer is not available"))
    else
      nextId += 1
      val id = s"${idPrefix}-${nextId}"
      val p  = Pending(Promise[HttpResponse](), None)
      pending(id) = p
      if config.timeoutMillis > 0 then
        p.timer = Some(
          setTimeout(config.timeoutMillis.toDouble) {
            complete(
              id,
              error(
                RPCStatus.DEADLINE_EXCEEDED_I4,
                s"The renderer did not reply within ${config.timeoutMillis} ms"
              )
            )
          }
        )
      try
        webContents.applyDynamic("send")(requestChannel, id, ElectronIPC.toPayload(request))
      catch
        case e: Throwable =>
          complete(id, error(RPCStatus.UNAVAILABLE_I2, s"Failed to reach the renderer: ${e}"))
      Rx.future(p.promise.future)

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] = Rx
    .exception(
      NotImplementedError("Streaming from the main process to a renderer is not supported")
    )

  override def close(): Unit =
    if !closed then
      failAll("The channel was closed")
      closed = true
      ipcMain.applyDynamic("removeListener")(replyChannel, onReply)
      if hasFunction(webContents, "removeListener") then
        webContents.applyDynamic("removeListener")("destroyed", onDestroyed)
        webContents.applyDynamic("removeListener")("did-start-navigation", onNavigation)

end ElectronMainChannel

/**
  * Entry point for calling a renderer's services from the Electron main process (see
  * [[ElectronMainChannel]]).
  */
object ElectronMain:

  /**
    * An async HTTP client whose requests go to the renderer of `webContents` over Electron IPC. It
    * does not retry: a call that fails because the page reloaded should not run again on the new
    * page.
    */
  def newAsyncClient(
      ipcMain: js.Dynamic,
      webContents: js.Dynamic,
      config: ElectronMainChannelConfig = ElectronMainChannelConfig()
  ): HttpAsyncClient =
    val channel = ElectronMainChannel(ipcMain, webContents, config)
    val factory =
      new HttpChannelFactory:
        override def newChannel: HttpChannel =
          throw NotImplementedError("Use ElectronMain.newAsyncClient for main→renderer calls")
        override def newAsyncChannel: HttpAsyncChannel = channel
    Http.client.withChannelFactory(factory).noRetry.newAsyncClient

end ElectronMain
