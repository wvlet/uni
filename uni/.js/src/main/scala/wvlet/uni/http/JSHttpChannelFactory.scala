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
  * JavaScript-specific HTTP channel factory.
  *
  *   - `newAsyncChannel` uses the Fetch API (works in browsers and Node alike).
  *   - `newChannel` (synchronous) uses `worker_threads` + `Atomics.wait` on a `SharedArrayBuffer`.
  *     Node.js only — there is no recovery path for sync HTTP in modern browsers (sync XHR is
  *     deprecated, and `worker_threads` doesn't exist on the web). On browser-like environments we
  *     throw a `NotImplementedError` recommending the async client.
  */
object JSHttpChannelFactory extends HttpChannelFactory:

  override def newChannel: HttpChannel =
    if NodeSyncHttpChannel.isNode then
      NodeSyncHttpChannel()
    else
      throw NotImplementedError(
        "Synchronous HTTP is not supported in browser JavaScript. " +
          "Use Http.client.newAsyncClient instead, or run on Node.js."
      )

  override def newAsyncChannel: HttpAsyncChannel = FetchChannel()

  override def newWebSocketClient: WebSocketClient = JSWebSocketClient()

end JSHttpChannelFactory
