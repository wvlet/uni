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
    * Connect to a `ws://` or `wss://` URL. The `handler` receives the connection's lifecycle
    * callbacks (`onOpen`/`onTextMessage`/`onBinaryMessage`/`onClose`/`onError`). The returned `Rx`
    * emits the open [[WebSocketContext]] — used to `send`/`close` — once the handshake completes,
    * or fails with the handshake error. (`handler.onOpen` fires for the same event.)
    */
  def connect(uri: String, handler: WebSocketHandler): Rx[WebSocketContext]

end WebSocketClient
