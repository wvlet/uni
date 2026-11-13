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

import wvlet.uni.http.{HttpContent, HttpMethod, HttpMultiMap, HttpStatus, Request, Response}

import scala.scalajs.js

/**
  * Wire contract for tunneling Uni RPC over Electron's `ipcRenderer.invoke` / `ipcMain.handle`.
  *
  * Both sides exchange plain JavaScript objects (structured-clone safe — no typed arrays, no class
  * instances) so they survive the renderer↔main boundary unchanged:
  *
  *   - Request payload: `{ method: string, uri: string, headers: {k: v}, body: string }`
  *   - Response payload: `{ status: number, headers: {k: v}, body: string }`
  *
  * The body is always a UTF-8 string (RPC payloads are JSON), so binary marshaling is unnecessary.
  *
  * Streaming calls ([[wvlet.uni.http.rpc.RPCAsyncClient.stream]]) use a second pair of channels,
  * because `invoke` resolves only once:
  *
  *   - The renderer sends `ipcRenderer.send(streamChannel(channel), id, requestPayload)`, where
  *     `id` is unique among the calls of that renderer, also across page reloads (the
  *     `webContents`, and a stream the old page started, survive a reload).
  *   - The main process answers on `streamEventChannel(channel)` with `(id, message)`, in order:
  *     one `{ kind: "head", status, headers, body }`, then `{ kind: "chunk", data: string }` for
  *     each part of the SSE body, then `{ kind: "end" }`. An unexpected failure ends the stream
  *     with `{ kind: "error", message }` instead of `end`.
  *   - To stop a call early, the renderer sends `ipcRenderer.send(streamCancelChannel(channel),
  *     id)`. The main process then cancels the method's `Rx` and sends nothing more for that id. It
  *     also cancels every stream of a `webContents` when its main frame starts a cross-document
  *     navigation (including a reload), and when the `webContents` is destroyed.
  *
  * The main process can also call services the renderer serves ([[ElectronMainChannel]] and
  * [[ElectronRenderer.serve]]), with the same payloads in the other direction:
  *
  *   - The main process sends `webContents.send(reverseChannel(channel), id, requestPayload)`.
  *   - The preload hands the payload to the handler the renderer registered with
  *     `bridge.serve(handler)`, and sends the response payload back with
  *     `ipcRenderer.send(reverseReplyChannel(channel), id, responsePayload)`. Before the renderer
  *     calls `serve`, the preload answers at once with an `UNAVAILABLE_I2` RPC error.
  */
object ElectronIPC:

  /**
    * Default IPC channel name shared by [[ElectronRPCServer]] (main) and the renderer bridge. A
    * single channel multiplexes every service because the RPC path already carries the service
    * name.
    */
  val DefaultChannel: String = "uni-rpc"

  /**
    * Default global name the preload bridge is expected to expose on `window`
    * (`contextBridge.exposeInMainWorld("uniRPC", { request })`).
    */
  val DefaultBridgeName: String = "uniRPC"

  /** The channel streaming calls are sent on, for the RPC channel `channel`. */
  def streamChannel(channel: String): String = s"${channel}-stream"

  /** The channel the main process sends stream messages on, for the RPC channel `channel`. */
  def streamEventChannel(channel: String): String = s"${channel}-stream-event"

  /** The channel a renderer sends `(id)` on to cancel one of its streaming calls. */
  def streamCancelChannel(channel: String): String = s"${channel}-stream-cancel"

  /** The channel the main process sends `(id, requestPayload)` on to call the renderer. */
  def reverseChannel(channel: String): String = s"${channel}-reverse"

  /** The channel a renderer answers a main-process call on, with `(id, responsePayload)`. */
  def reverseReplyChannel(channel: String): String = s"${channel}-reverse-reply"

  /** A stream message (main side): `{ kind, ... }`. */
  private[electron] def streamHead(response: Response): js.Object =
    val msg = fromResponse(response).asInstanceOf[js.Dynamic]
    msg.updateDynamic("kind")("head")
    msg.asInstanceOf[js.Object]

  private[electron] def streamChunk(data: String): js.Object = js
    .Dynamic
    .literal(kind = "chunk", data = data)

  private[electron] val streamEnd: js.Object = js.Dynamic.literal(kind = "end")

  private[electron] def streamError(message: String): js.Object = js
    .Dynamic
    .literal(kind = "error", message = message)

  /** Marshal an outgoing [[wvlet.uni.http.Request]] into an IPC payload object (renderer side). */
  def toPayload(request: Request): js.Object = js
    .Dynamic
    .literal(
      method = request.method.name,
      uri = request.uri,
      headers = headersToJS(request.wireHeaders),
      body = request.content.asString.getOrElse("")
    )

  /** Reconstruct a [[wvlet.uni.http.Request]] from an IPC payload object (main side). */
  def toRequest(payload: js.Dynamic): Request =
    val method = HttpMethod.of(asString(payload.method)).getOrElse(HttpMethod.POST)
    val uri    = asString(payload.uri)
    val body   = asString(payload.body)
    val base   = Request(method, uri).withHeaders(jsToHeaders(payload.headers))
    if body.isEmpty then
      base
    else
      base.withJsonContent(body)

  /** Marshal a [[wvlet.uni.http.Response]] into an IPC payload object (main side). */
  def fromResponse(response: Response): js.Object = js
    .Dynamic
    .literal(
      status = response.status.code,
      headers = headersToJS(response.headers),
      body = response.contentAsString.getOrElse("")
    )

  /** Reconstruct a [[wvlet.uni.http.Response]] from an IPC payload object (renderer side). */
  def toResponse(payload: js.Dynamic): Response =
    val status  = HttpStatus.ofCode(asInt(payload.status))
    val headers = jsToHeaders(payload.headers)
    val body    = asString(payload.body)
    val content =
      if body.isEmpty then
        HttpContent.Empty
      else
        HttpContent.text(body)
    Response(status, headers, content)

  private def headersToJS(headers: HttpMultiMap): js.Object =
    val dict = js.Dictionary.empty[String]
    headers
      .entries
      .foreach { case (k, v) =>
        dict(k) = v
      }
    dict.asInstanceOf[js.Object]

  private def jsToHeaders(headers: js.Any): HttpMultiMap =
    if js.isUndefined(headers) || headers == null then
      HttpMultiMap.empty
    else
      val builder = HttpMultiMap.newBuilder
      val dict    = headers.asInstanceOf[js.Dictionary[Any]]
      dict.foreach { case (k, v) =>
        builder.add(k, String.valueOf(v))
      }
      builder.result()

  private def asString(v: js.Any): String =
    if js.isUndefined(v) || v == null then
      ""
    else
      v.toString

  private def asInt(v: js.Any): Int =
    if js.isUndefined(v) || v == null then
      0
    else
      v.asInstanceOf[js.Any].asInstanceOf[Double].toInt

end ElectronIPC
