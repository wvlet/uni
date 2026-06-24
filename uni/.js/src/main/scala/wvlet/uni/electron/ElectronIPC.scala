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

  /** Marshal an outgoing [[Request]] into an IPC payload object (renderer side). */
  def toPayload(request: Request): js.Object = js
    .Dynamic
    .literal(
      method = request.method.name,
      uri = request.uri,
      headers = headersToJS(request.wireHeaders),
      body = request.content.asString.getOrElse("")
    )

  /** Reconstruct a [[Request]] from an IPC payload object (main side). */
  def toRequest(payload: js.Dynamic): Request =
    val method = HttpMethod.of(asString(payload.method)).getOrElse(HttpMethod.POST)
    val uri    = asString(payload.uri)
    val body   = asString(payload.body)
    val base   = Request(method, uri).withHeaders(jsToHeaders(payload.headers))
    if body.isEmpty then
      base
    else
      base.withJsonContent(body)

  /** Marshal a [[Response]] into an IPC payload object (main side). */
  def fromResponse(response: Response): js.Object = js
    .Dynamic
    .literal(
      status = response.status.code,
      headers = headersToJS(response.headers),
      body = response.contentAsString.getOrElse("")
    )

  /** Reconstruct a [[Response]] from an IPC payload object (renderer side). */
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
