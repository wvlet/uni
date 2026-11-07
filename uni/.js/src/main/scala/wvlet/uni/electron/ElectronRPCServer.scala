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
import wvlet.uni.http.{Response, ServerSentEvent}
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.rx.{Cancelable, OnCompletion, OnError, OnNext, RxRunner}

import scala.scalajs.js
import scala.util.control.NonFatal

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
    val streams                                                   = RunningStreams()
    val streamHandler: js.Function3[js.Any, js.Any, js.Any, Unit] =
      (event: js.Any, id: js.Any, payload: js.Any) =>
        val e     = event.asInstanceOf[js.Dynamic]
        val reply = StreamReply(e, config.channel, id)
        if config.isAllowedSender(e) then
          stream(dispatcher, streams, reply, payload.asInstanceOf[js.Dynamic])
        else
          warn(s"Rejected Electron RPC stream from ${ElectronRPCServerConfig.senderUrl(event)}")
          val rejection =
            RPCStatus.PERMISSION_DENIED_U14.newException("Sender not allowed").toResponse
          reply.send(ElectronIPC.streamHead(rejection))
          reply.send(ElectronIPC.streamEnd)
    ipcMain.applyDynamic("on")(ElectronIPC.streamChannel(config.channel), streamHandler)
    // Only the renderer that started a stream can cancel it: the ids are looked up per sender
    val cancelHandler: js.Function2[js.Any, js.Any, Unit] =
      (event: js.Any, id: js.Any) =>
        val e = event.asInstanceOf[js.Dynamic]
        if !js.isUndefined(e) && e != null then
          streams.cancel(e.sender, id)
    ipcMain.applyDynamic("on")(ElectronIPC.streamCancelChannel(config.channel), cancelHandler)

  end serve

  /**
    * Sends the messages of one streaming call to the frame that made it (`event.reply`, so a
    * permitted iframe gets its own replies). Sending stops once the renderer's `webContents` is
    * gone or the call is cancelled.
    */
  private class StreamReply(event: js.Dynamic, channel: String, val id: js.Any):
    private val eventChannel = ElectronIPC.streamEventChannel(channel)
    val sender: js.Dynamic   = event.sender
    var cancelled: Boolean   = false

    def isGone: Boolean =
      js.isUndefined(sender) || sender == null ||
        (js.typeOf(sender.isDestroyed) == "function" && sender.isDestroyed().asInstanceOf[Boolean])

    def send(msg: js.Object): Unit =
      if !cancelled && !isGone then
        if js.typeOf(event.reply) == "function" then
          event.applyDynamic("reply")(eventChannel, id, msg)
        else
          sender.applyDynamic("send")(eventChannel, id, msg)

  end StreamReply

  /**
    * The running streams of each renderer `webContents`, by call id. While a `webContents` has
    * streams, it carries a `destroyed` and a `did-start-navigation` listener that cancel them; the
    * listeners are removed with its last stream.
    */
  private class RunningStreams:
    private class Streams(val detach: () => Unit):
      val byId = js.Map.empty[String, () => Unit]

    // Keyed by the webContents object (identity)
    private val senders = js.Map.empty[js.Any, Streams]

    private def key(id: js.Any): String = String.valueOf(id)

    /** Register a running stream with the function that cancels it. */
    def add(reply: StreamReply, cancel: () => Unit): Unit =
      val sender  = reply.sender
      val streams = senders
        .get(sender)
        .getOrElse {
          val s = Streams(watch(sender))
          senders(sender) = s
          s
        }
      streams.byId(key(reply.id)) = cancel

    def remove(sender: js.Any, id: js.Any): Unit = senders
      .get(sender)
      .foreach { streams =>
        streams.byId.remove(key(id))
        if streams.byId.isEmpty then
          senders.remove(sender)
          streams.detach()
      }

    def cancel(sender: js.Any, id: js.Any): Unit = senders
      .get(sender)
      .flatMap(_.byId.get(key(id)))
      .foreach { f =>
        remove(sender, id)
        f()
      }

    private def cancelAll(sender: js.Any): Unit = senders
      .get(sender)
      .foreach { streams =>
        val running = streams.byId.values.toList
        streams.byId.clear()
        senders.remove(sender)
        streams.detach()
        running.foreach(_())
      }

    private def watch(sender: js.Dynamic): () => Unit =
      if js.typeOf(sender.on) != "function" || js.typeOf(sender.removeListener) != "function" then
        () => ()
      else
        val onDestroyed: js.Function0[Unit] = () => cancelAll(sender)
        // The old page can't read the rest after a reload or navigation. Electron passes a details
        // event (isMainFrame, isSameDocument); older versions pass them as positional arguments
        // (url, isInPlace, isMainFrame).
        val onNavigation: js.Function4[js.Dynamic, js.Any, js.Any, js.Any, Unit] =
          (details: js.Dynamic, _url: js.Any, isInPlace: js.Any, isMainFrame: js.Any) =>
            def flag(name: String, positional: js.Any): Boolean =
              val v =
                if !js.isUndefined(details) && details != null &&
                  !js.isUndefined(details.selectDynamic(name))
                then
                  details.selectDynamic(name)
                else
                  positional
              v.asInstanceOf[Any] == true
            if flag("isMainFrame", isMainFrame) && !flag("isSameDocument", isInPlace) then
              cancelAll(sender)
        sender.applyDynamic("on")("destroyed", onDestroyed)
        sender.applyDynamic("on")("did-start-navigation", onNavigation)
        () =>
          sender.applyDynamic("removeListener")("destroyed", onDestroyed)
          sender.applyDynamic("removeListener")("did-start-navigation", onNavigation)

  end RunningStreams

  /**
    * Run a streaming request through the dispatcher and forward its response to the renderer as
    * stream messages (see [[ElectronIPC]]). A response that is not an event stream, such as an RPC
    * error raised before the method returned, is sent as a head with its body.
    */
  private def stream(
      dispatcher: RPCDispatcher,
      streams: RunningStreams,
      reply: StreamReply,
      payload: js.Dynamic
  ): Unit =
    // The dispatch, then the event stream it answered with
    var dispatching: Cancelable = Cancelable.empty
    var streaming: Cancelable   = Cancelable.empty
    var finished                = false
    def finish(): Unit          =
      if !finished then
        finished = true
        streams.remove(reply.sender, reply.id)

    def fail(e: Throwable): Unit =
      warn(s"Unexpected error in RPC stream: ${e.getMessage}", e)
      finish()
      reply.send(ElectronIPC.streamError("Internal error"))

    def end(): Unit =
      finish()
      reply.send(ElectronIPC.streamEnd)

    if !reply.isGone then
      streams.add(
        reply,
        { () =>
          // A renderer that survives an uncommitted navigation still waits for this call
          reply.send(ElectronIPC.streamError("Cancelled"))
          finished = true
          reply.cancelled = true
          dispatching.cancel
          streaming.cancel
        }
      )

    def forward(response: Response): Unit =
      reply.send(ElectronIPC.streamHead(response))
      if response.isEventStream then
        streaming =
          RxRunner.run(response.events) {
            case OnNext(event) =>
              reply.send(ElectronIPC.streamChunk(event.asInstanceOf[ServerSentEvent].toContent))
            case OnError(e) =>
              fail(e)
            case OnCompletion =>
              end()
          }
      else
        end()

    // A malformed payload must still end the renderer's call, not leave it waiting
    try
      dispatching =
        RxRunner.run(dispatcher.dispatch(ElectronIPC.toRequest(payload))) {
          case OnNext(v) =>
            if !finished then
              forward(v.asInstanceOf[Response])
          case OnError(e) =>
            fail(e)
          case OnCompletion =>
        }
    catch
      case NonFatal(e) =>
        fail(e)

  end stream

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
            RPCStatus.INTERNAL_ERROR_I0.newException("Internal error", e).noStackTrace.toResponse
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
      ) && senderFilter.forall(f => ElectronRPCServerConfig.safeCheck(f, frame))

end ElectronRPCServerConfig

object ElectronRPCServerConfig extends LogSupport:
  private def isNullish(v: js.Any): Boolean = js.isUndefined(v) || v == null

  /** Run a user filter, treating a throw as a rejection so the renderer still gets a reply. */
  private def safeCheck(filter: js.Dynamic => Boolean, frame: js.Dynamic): Boolean =
    try
      filter(frame)
    catch
      case NonFatal(e) =>
        warn(s"Electron RPC sender filter failed: ${e.getMessage}", e)
        false

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
