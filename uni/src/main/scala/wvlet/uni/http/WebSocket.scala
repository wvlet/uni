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

/**
  * A handle to a single, established WebSocket connection. `send`/`close` are safe to call from any
  * thread. Implementations are provided per backend (e.g. Netty on the JVM).
  */
trait WebSocketContext:

  /**
    * The original HTTP upgrade request, including any attributes set by filters during the
    * handshake.
    */
  def request: Request

  /**
    * Send a text message to the client.
    */
  def send(text: String): Unit

  /**
    * Send a binary message to the client.
    */
  def send(data: Array[Byte]): Unit

  /**
    * Close the connection with a normal-closure (1000) status.
    */
  def close(): Unit

  /**
    * Close the connection with the given status code and reason.
    */
  def close(statusCode: Int, reason: String): Unit

end WebSocketContext

/**
  * Callbacks for a WebSocket connection. All methods have no-op defaults, so a handler overrides
  * only what it needs. A fresh handler instance is created per connection (via
  * [[WebSocketRoute.handlerFactory]]), so it may hold per-connection mutable state.
  */
trait WebSocketHandler:

  /**
    * Called once, after the handshake completes and before any message is delivered.
    */
  def onOpen(ctx: WebSocketContext): Unit = {}

  /**
    * Called for each complete text message from the client.
    */
  def onTextMessage(ctx: WebSocketContext, message: String): Unit = {}

  /**
    * Called for each complete binary message from the client.
    */
  def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = {}

  /**
    * Called once when the connection closes, whether initiated by the client or the server.
    * Delivered exactly once.
    */
  def onClose(ctx: WebSocketContext): Unit = {}

  /**
    * Called when a callback or the connection raises an error.
    */
  def onError(ctx: WebSocketContext, e: Throwable): Unit = {}

end WebSocketHandler

/**
  * A WebSocket endpoint registered on an [[HttpServerConfig]] by path, kept separate from the
  * RPC/router because WebSocket connections are stateful and bidirectional. The optional [[filter]]
  * gates the HTTP upgrade handshake (e.g. for authentication); returning a non-2xx response (or an
  * empty `Rx`) rejects the upgrade.
  *
  * @param handlerFactory
  *   creates a fresh [[WebSocketHandler]] per accepted connection, given the upgrade request.
  */
case class WebSocketRoute(
    path: String,
    handlerFactory: Request => WebSocketHandler,
    filter: RxHttpFilter = RxHttpFilter.identity
)
