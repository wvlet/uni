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
package wvlet.uni.http

import wvlet.uni.rx.Rx

/**
  * A client for opening WebSocket connections. Reuses the same [[WebSocketHandler]] /
  * [[WebSocketContext]] abstraction as the server side, so handler code is symmetric.
  */
trait WebSocketClient:

  /**
    * The longest a [[connect]] may take, from the TCP connect through the upgrade response, in
    * milliseconds (0 means no limit). Past it `connect` fails with an [[HttpException]] whose code
    * is [[HttpErrorCode.ConnectionTimeout]], and the half-open connection is closed. The built-in
    * clients default to [[WebSocketClient.DefaultConnectTimeoutMillis]]; a custom client that
    * doesn't override [[withConnectTimeoutMillis]] reports 0.
    */
  def connectTimeoutMillis: Long = 0

  /**
    * A client bounding each connect by `millis` (see [[connectTimeoutMillis]]). A custom client
    * that doesn't support a timeout returns itself unchanged.
    */
  def withConnectTimeoutMillis(millis: Long): WebSocketClient = this

  /** A client whose connects have no time limit. */
  def noConnectTimeout(): WebSocketClient = withConnectTimeoutMillis(0)

  /**
    * Connect to the `ws://` or `wss://` URL of `request`, sending its headers (e.g.
    * `Authorization`) with the upgrade. The `handler` receives the connection's lifecycle callbacks
    * (`onOpen`/`onTextMessage`/`onBinaryMessage`/`onClose`/`onError`). The returned `Rx` emits the
    * open [[WebSocketContext]] — used to `send`/`close` — once the handshake completes, or fails
    * with the handshake error. (`handler.onOpen` fires for the same event.)
    *
    * Headers the handshake sets itself (`Host`, `Upgrade`, `Connection`, `Sec-WebSocket-*`) are
    * rejected with an `IllegalArgumentException`. Browsers can't send headers on a WebSocket
    * upgrade, so there a request with headers fails; pass a token in the URL query instead.
    *
    * `pingIntervalMillis` enables a client-side ping/pong heartbeat (0 disables it): the client
    * pings an idle connection and closes it if the peer stops responding. Not supported on the JS
    * backend (the browser/Node `WebSocket` API cannot send protocol pings), where it is ignored.
    */
  def connect(
      request: Request,
      handler: WebSocketHandler,
      pingIntervalMillis: Int
  ): Rx[WebSocketContext]

  /** Connect with the client heartbeat disabled. */
  final def connect(request: Request, handler: WebSocketHandler): Rx[WebSocketContext] = connect(
    request,
    handler,
    0
  )

  /** Connect to a `ws://` or `wss://` URL without extra headers. */
  final def connect(
      uri: String,
      handler: WebSocketHandler,
      pingIntervalMillis: Int
  ): Rx[WebSocketContext] = connect(Request.get(uri), handler, pingIntervalMillis)

  /** Connect to a `ws://` or `wss://` URL with the client heartbeat disabled. */
  final def connect(uri: String, handler: WebSocketHandler): Rx[WebSocketContext] = connect(
    Request.get(uri),
    handler,
    0
  )

end WebSocketClient

object WebSocketClient:

  /**
    * Default for [[WebSocketClient.connectTimeoutMillis]]: 30 s, as for
    * [[HttpClientConfig.connectTimeoutMillis]].
    */
  final val DefaultConnectTimeoutMillis: Long = 30000

  /** Headers the WebSocket handshake sets itself, in lower case. */
  private val handshakeHeaders = Set("host", "upgrade", "connection")

  /** The request's headers, after rejecting those the handshake sets itself. */
  private[http] def upgradeHeaders(request: Request): Seq[(String, String)] =
    val headers = request.headers.entries
    headers.foreach { (name, _) =>
      val lower = name.toLowerCase
      if handshakeHeaders.contains(lower) || lower.startsWith("sec-websocket-") then
        throw IllegalArgumentException(
          s"The WebSocket handshake sets the ${name} header itself; remove it from the request"
        )
    }
    headers
