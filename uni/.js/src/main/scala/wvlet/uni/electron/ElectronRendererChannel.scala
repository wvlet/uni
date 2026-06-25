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
  HttpRequest,
  HttpResponse
}
import wvlet.uni.rx.Rx

import scala.concurrent.ExecutionContext
import scala.scalajs.js

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
    throw NotImplementedError(
      "Streaming responses are not supported over Electron IPC. Use a regular RPC call."
    )

  override def close(): Unit = ()

end ElectronRendererChannel

/**
  * [[HttpChannelFactory]] producing [[ElectronRendererChannel]]s. There is no synchronous channel
  * in a renderer, so `newChannel` throws — use `Http.client.newAsyncClient`.
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
  def defaultBridge: js.Dynamic =
    val bridge = org
      .scalajs
      .dom
      .window
      .asInstanceOf[js.Dynamic]
      .selectDynamic(ElectronIPC.DefaultBridgeName)
    if js.isUndefined(bridge) || bridge == null then
      throw IllegalStateException(
        s"Electron IPC bridge 'window.${ElectronIPC.DefaultBridgeName}' is not available. " +
          "Ensure the preload script exposes it via contextBridge.exposeInMainWorld."
      )
    bridge

  /**
    * Install the Electron IPC channel factory as the default Uni HTTP channel factory.
    *
    * @param bridge
    *   the preload-exposed bridge object. Defaults to `window.uniRPC` ([[defaultBridge]]).
    */
  def install(bridge: js.Dynamic = defaultBridge): Unit = Http.setDefaultChannelFactory(
    ElectronChannelFactory(bridge)
  )

end ElectronRenderer
