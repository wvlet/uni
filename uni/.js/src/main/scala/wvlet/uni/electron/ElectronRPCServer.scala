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

import wvlet.uni.log.LogSupport
import wvlet.uni.http.Response
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, RxRunner}

import scala.scalajs.js

/**
  * Main-process side of the Electron RPC transport.
  *
  * Registers an `ipcMain.handle(channel, ...)` listener that dispatches incoming requests through a
  * transport-neutral [[wvlet.uni.http.rpc.RPCDispatcher]] and returns a `Promise` of the response
  * payload — exactly what [[ElectronRendererChannel]] on the other end awaits.
  *
  * Electron's `ipcMain` is passed in as a value (rather than `require("electron")`-ed here) so this
  * code never couples to a particular Scala.js module/bundler setup; the thin JS main entry obtains
  * `ipcMain` and hands it over.
  *
  * {{{
  * // Scala.js main process
  * val server = ElectronRPCServer.serve(
  *   ipcMain,
  *   RPCRouter.of[CounterApi](CounterApiImpl())
  * )
  * }}}
  *
  * Every call is checked against the sender frame (see [[ElectronRPCServerConfig]]): by default
  * only top-level frames may call, so an embedded third-party iframe cannot reach the services.
  * Restrict the callers further with `withAllowedOrigins`:
  *
  * {{{
  * ElectronRPCServer.serve(
  *   ipcMain,
  *   ElectronRPCServerConfig().withAllowedOrigins("file://", "http://localhost:5173"),
  *   RPCRouter.of[CounterApi](CounterApiImpl())
  * )
  * }}}
  */
object ElectronRPCServer extends LogSupport:

  /**
    * Register an RPC handler on the default channel ([[ElectronIPC.DefaultChannel]]).
    *
    * @param ipcMain
    *   Electron's `ipcMain` object.
    * @param routers
    *   one or more service routers built via `RPCRouter.of[T](impl)`.
    */
  def serve(ipcMain: js.Dynamic, routers: RPCRouter*): Unit = serve(
    ipcMain,
    ElectronIPC.DefaultChannel,
    routers*
  )

  /**
    * Register an RPC handler on an explicit channel name.
    */
  def serve(ipcMain: js.Dynamic, channel: String, routers: RPCRouter*): Unit = serve(
    ipcMain,
    ElectronRPCServerConfig().withChannel(channel),
    routers*
  )

  /**
    * Register an RPC handler with an explicit configuration (channel, allowed senders).
    */
  def serve(ipcMain: js.Dynamic, config: ElectronRPCServerConfig, routers: RPCRouter*): Unit =
    val dispatcher = new RPCDispatcher(routers.toSeq)
    val handler: js.Function2[js.Any, js.Any, js.Promise[js.Object]] =
      (event: js.Any, payload: js.Any) =>
        if config.isAllowedSender(event.asInstanceOf[js.Dynamic]) then
          dispatch(dispatcher, payload.asInstanceOf[js.Dynamic])
        else
          warn(s"Rejected Electron RPC call from ${ElectronRPCServerConfig.senderUrl(event)}")
          val rejection =
            RPCStatus.PERMISSION_DENIED_U14.newException("Sender not allowed").toResponse
          js.Promise.resolve[js.Object](ElectronIPC.fromResponse(rejection))
    ipcMain.applyDynamic("handle")(config.channel, handler)

  /**
    * Run a single request through the dispatcher and bridge the resulting `Rx[Response]` into a JS
    * `Promise`. The dispatcher never fails its `Rx` (RPC errors are encoded as error responses),
    * but the `OnError` branch is handled defensively so the renderer always receives a well-formed
    * response payload.
    */
  private def dispatch(dispatcher: RPCDispatcher, payload: js.Dynamic): js.Promise[js.Object] =
    val request = ElectronIPC.toRequest(payload)
    new js.Promise[js.Object]((resolve, _reject) =>
      RxRunner.run(dispatcher.dispatch(request)) {
        case OnNext(v) =>
          resolve(ElectronIPC.fromResponse(v.asInstanceOf[Response]))
        case OnError(e) =>
          warn(s"Unexpected error in RPC dispatch: ${e.getMessage}", e)
          val errorResponse =
            RPCStatus.INTERNAL_ERROR_I0.newException("Internal error", e).toResponse
          resolve(ElectronIPC.fromResponse(errorResponse))
        case OnCompletion =>
        // No value emitted: nothing to resolve with (should not happen for RPC).
      }
    )

end ElectronRPCServer

/**
  * Configuration of [[ElectronRPCServer]].
  *
  * Electron delivers `ipcMain.handle` calls from every frame of every renderer, including embedded
  * iframes and pages the window navigated to, so each call is checked against its
  * `event.senderFrame`:
  *   - A call whose frame is gone (destroyed or navigated away) is rejected.
  *   - Unless `allowSubFrames` is set, only top-level frames may call.
  *   - When `allowedOrigins` is non-empty, the frame URL must start with one of them, at a path
  *     boundary: `"http://localhost:5173"` matches `http://localhost:5173/index.html` but not
  *     `http://localhost:51730/`. `"file://"` matches every local file.
  *   - `senderFilter` adds an application-specific check of the frame (`WebFrameMain`).
  *
  * A rejected call resolves with a `PERMISSION_DENIED_U14` RPC error.
  */
case class ElectronRPCServerConfig(
    channel: String = ElectronIPC.DefaultChannel,
    allowedOrigins: Seq[String] = Nil,
    allowSubFrames: Boolean = false,
    senderFilter: Option[js.Dynamic => Boolean] = None
):
  def withChannel(channel: String): ElectronRPCServerConfig = copy(channel = channel)

  /** Accept calls only from frames whose URL starts with one of these origins or URL prefixes. */
  def withAllowedOrigins(origins: String*): ElectronRPCServerConfig = copy(allowedOrigins =
    origins.toSeq
  )

  /** Also accept calls from embedded frames (iframes), not only top-level frames. */
  def withAllowSubFrames(enabled: Boolean): ElectronRPCServerConfig = copy(allowSubFrames = enabled)

  /** An additional check of the sender frame (`event.senderFrame`, a `WebFrameMain`). */
  def withSenderFilter(filter: js.Dynamic => Boolean): ElectronRPCServerConfig = copy(senderFilter =
    Some(filter)
  )

  def noSenderFilter: ElectronRPCServerConfig = copy(senderFilter = None)

  /** Whether the IPC `event` comes from a frame allowed to call the services. */
  def isAllowedSender(event: js.Dynamic): Boolean =
    if ElectronRPCServerConfig.isNullish(event) then
      false
    else
      val frame = event.senderFrame
      !ElectronRPCServerConfig.isNullish(frame) &&
      (allowSubFrames || ElectronRPCServerConfig.isNullish(frame.parent)) && (
        allowedOrigins.isEmpty || {
          val url = ElectronRPCServerConfig.frameUrl(frame)
          allowedOrigins.exists(o => ElectronRPCServerConfig.matchesOrigin(url, o))
        }
      ) && senderFilter.forall(_(frame))

end ElectronRPCServerConfig

object ElectronRPCServerConfig:
  private def isNullish(v: js.Any): Boolean = js.isUndefined(v) || v == null

  private def frameUrl(frame: js.Dynamic): String =
    val url = frame.url
    if isNullish(url) then
      ""
    else
      url.toString

  private[electron] def senderUrl(event: js.Any): String =
    val e = event.asInstanceOf[js.Dynamic]
    if isNullish(e) || isNullish(e.senderFrame) then
      "(unknown frame)"
    else
      frameUrl(e.senderFrame)

  private[electron] def matchesOrigin(url: String, origin: String): Boolean =
    val prefix = origin.stripSuffix("/")
    url.startsWith(prefix) && (
      url.length == prefix.length || {
        val next = url.charAt(prefix.length)
        next == '/' || next == '?' || next == '#'
      }
    )

end ElectronRPCServerConfig
