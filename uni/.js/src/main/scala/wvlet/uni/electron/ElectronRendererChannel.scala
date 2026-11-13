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
  HttpChannel,
  HttpChannelFactory,
  HttpClientConfig,
  HttpContent,
  HttpErrorCode,
  HttpException,
  HttpRequest,
  HttpResponse
}
import wvlet.uni.http.Response
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.control.NonFatal

/**
  * Renderer-side HTTP channel that tunnels RPC calls over Electron IPC.
  *
  * The renderer process (Chromium) cannot reach Node or the main process directly. Instead it calls
  * a small function exposed by the preload script through `contextBridge`, e.g.
  *
  * {{{
  * // preload.cjs
  * const { contextBridge, ipcRenderer } = require("electron")
  * contextBridge.exposeInMainWorld("uniRPC", {
  *   request: (payload) => ipcRenderer.invoke("uni-rpc", payload)
  * })
  * }}}
  *
  * Streaming calls also need `stream(payload, onMessage)`, which returns a function that cancels
  * the call; [[ElectronIPC]] describes the messages, and the Electron guide shows a preload that
  * implements it.
  *
  * `bridge.request(payload)` must return a `Promise` resolving to the response payload (see
  * [[ElectronIPC]]). Only async operations are supported — there is no synchronous IPC path in a
  * sandboxed renderer.
  *
  * @param bridge
  *   the contextBridge-exposed object holding a `request(payload): Promise` function.
  */
class ElectronRendererChannel(bridge: js.Dynamic) extends HttpAsyncChannel:
  import js.Thenable.Implicits.*
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] =
    val payload = ElectronIPC.toPayload(request)
    val promise = bridge.applyDynamic("request")(payload).asInstanceOf[js.Promise[js.Dynamic]]
    val future  = promise.toFuture.map(ElectronIPC.toResponse)
    Rx.future(future)

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    sendStreaming(request, config, _ => ())

  /**
    * Stream a response over the bridge's optional `stream(payload, onMessage)` function (see
    * [[ElectronIPC]] for the messages). Each subscription of the returned Rx makes the call.
    * Cancelling the Rx calls the function `stream` returned, which tells the main process to stop
    * the server stream. A bridge whose `stream` returns nothing still works, but then the server
    * stream runs until it ends, the page navigates, or the window closes.
    */
  override def sendStreaming(
      request: HttpRequest,
      config: HttpClientConfig,
      onResponse: HttpResponse => Unit
  ): Rx[Array[Byte]] =
    if js.typeOf(bridge.stream) != "function" then
      Rx.exception(
        NotImplementedError(
          "Streaming over Electron IPC needs a `stream` function on the preload bridge: " +
            "stream: (payload, onMessage) => ... (see wvlet.uni.electron.ElectronIPC)"
        )
      )
    else
      val payload = ElectronIPC.toPayload(request)
      // Each subscription makes its own call, so cancelling one doesn't stop another's
      Rx.single(())
        .flatMap { _ =>
          val source  = Rx.variable[Option[Array[Byte]]](None)
          var started = false

          def onMessage(msg: js.Dynamic): Unit =
            try
              msg.kind.asInstanceOf[String] match
                case "head" =>
                  val head = ElectronIPC.toResponse(msg)
                  onResponse(head.withContent(HttpContent.Empty))
                  if !head.isSuccessful then
                    source.setException(HttpException.fromResponse(head))
                case "chunk" =>
                  source.set(Some(msg.data.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)))
                case "end" =>
                  source.stop()
                case "error" =>
                  source.setException(
                    HttpException(String.valueOf(msg.message), HttpErrorCode.Unknown)
                  )
                case _ =>
            catch
              case e: Throwable =>
                source.setException(e)

          // What bridge.stream returned: a function that cancels the call, or undefined (older bridges)
          var cancelCall: js.Any = js.undefined

          source
            .tap { chunk =>
              // RxVar emits its current value (None) right after registering a subscriber, so the
              // call starts only once someone listens and no chunk is lost
              if chunk.isEmpty && !started then
                started = true
                val callback: js.Function1[js.Dynamic, Unit] = onMessage
                cancelCall = bridge.applyDynamic("stream")(payload, callback)
            }
            .onCancel {
              if js.typeOf(cancelCall) == "function" then
                cancelCall.asInstanceOf[js.Function0[Any]]()
            }
            .filter(_.isDefined)
            .map(_.get)
        }

  end sendStreaming

  override def close(): Unit = ()

end ElectronRendererChannel

/**
  * [[wvlet.uni.http.HttpChannelFactory]] producing [[ElectronRendererChannel]]s. There is no
  * synchronous channel in a renderer, so `newChannel` throws — use `Http.client.newAsyncClient`.
  */
class ElectronChannelFactory(bridge: js.Dynamic) extends HttpChannelFactory:
  override def newChannel: HttpChannel =
    throw NotImplementedError(
      "Synchronous HTTP is not supported in an Electron renderer. Use Http.client.newAsyncClient."
    )

  override def newAsyncChannel: HttpAsyncChannel = ElectronRendererChannel(bridge)

end ElectronChannelFactory

/**
  * Entry point for wiring a renderer's Uni HTTP client onto Electron IPC.
  *
  * After [[install]], every `Http.client.newAsyncClient` (and any generated RPC `AsyncClient`)
  * sends its requests through the preload bridge to the main process instead of over the network.
  */
object ElectronRenderer:

  /**
    * The bridge object exposed by the preload script (defaults to `window.uniRPC`). Throws a
    * descriptive error if it is missing — the usual cause is a preload script that didn't run or
    * used a different global name.
    */
  def defaultBridge: js.Dynamic = availableBridge.getOrElse {
    throw IllegalStateException(
      s"Electron IPC bridge 'window.${ElectronIPC.DefaultBridgeName}' is not available. " +
        "Ensure the preload script exposes it via contextBridge.exposeInMainWorld."
    )
  }

  // `window` is absent outside a browser (Node.js), where there is no bridge either
  private def availableBridge: Option[js.Dynamic] =
    if js.typeOf(js.Dynamic.global.window) == "undefined" then
      None
    else
      val bridge = js.Dynamic.global.window.selectDynamic(ElectronIPC.DefaultBridgeName)
      Option.when(!js.isUndefined(bridge) && bridge != null)(bridge)

  /**
    * Install the Electron IPC channel factory as the default Uni HTTP channel factory.
    *
    * @param bridge
    *   the preload-exposed bridge object. Defaults to `window.uniRPC` ([[defaultBridge]]).
    */
  def install(bridge: js.Dynamic = defaultBridge): Unit = Http.setDefaultChannelFactory(
    ElectronChannelFactory(bridge)
  )

  /**
    * Install the Electron IPC channel factory if the preload bridge (`window.uniRPC`) is present,
    * and report whether it did. Use it in a renderer shared by an Electron app and a browser app:
    * in a browser the bridge is absent, and the HTTP client keeps its default fetch channel.
    */
  def installIfAvailable(): Boolean =
    val bridge = availableBridge
    bridge.foreach(install)
    bridge.isDefined

  /**
    * Serve routers to the main process, which calls them with [[ElectronMainChannel]]. Hands a
    * handler to the preload bridge's `serve(handler)` function; the handler takes a request payload
    * and returns a `Promise` of the response payload (see [[ElectronIPC]]).
    *
    * {{{
    * // preload: answer main-process calls with the renderer's handler
    * let handler = null
    * ipcRenderer.on("uni-rpc-reverse", async (_event, id, payload) => {
    *   const response = handler
    *     ? await handler(payload)
    *     : { status: 503, headers: {}, body: "" } // UNAVAILABLE_I2
    *   ipcRenderer.send("uni-rpc-reverse-reply", id, response)
    * })
    * contextBridge.exposeInMainWorld("uniRPC", { ..., serve: (h) => { handler = h } })
    * }}}
    *
    * @param bridge
    *   the preload-exposed bridge object; it needs a `serve` function.
    */
  def serve(bridge: js.Dynamic, routers: RPCRouter*): Unit =
    if js.typeOf(bridge.serve) != "function" then
      throw IllegalStateException(
        "Serving the main process over Electron IPC needs a `serve` function on the preload " +
          "bridge: serve: (handler) => ... (see wvlet.uni.electron.ElectronIPC)"
      )
    bridge.applyDynamic("serve")(handler(routers*))

  /** Serve routers to the main process through the default bridge (`window.uniRPC`). */
  def serve(routers: RPCRouter*): Unit = serve(defaultBridge, routers*)

  /**
    * The function a preload calls with a main-process request payload: it dispatches the request
    * and returns a `Promise` of the response payload. Every failure becomes an RPC error response,
    * so the main process always gets a reply.
    */
  def handler(routers: RPCRouter*): js.Function1[js.Any, js.Promise[js.Object]] =
    val dispatcher = RPCDispatcher(routers*)
    (payload: js.Any) =>
      js.Promise[js.Object] { (resolve, _) =>
        def fail(e: Throwable): Unit = resolve(
          ElectronIPC.fromResponse(
            RPCStatus.INTERNAL_ERROR_I0.newException("Internal error", e).noStackTrace.toResponse
          )
        )
        try
          RxRunner.run(
            dispatcher.dispatch(ElectronIPC.toRequest(payload.asInstanceOf[js.Dynamic]))
          ) {
            case OnNext(v) =>
              resolve(ElectronIPC.fromResponse(v.asInstanceOf[Response]))
            case OnError(e) =>
              fail(e)
            case OnCompletion =>
          }
        catch
          case NonFatal(e) =>
            fail(e)
      }

end ElectronRenderer
