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
  * Entry point for HTTP client and server creation.
  *
  * Example:
  * {{{
  * // Create a sync client
  * val client = Http.client.newSyncClient
  * val response = client.send(HttpRequest.get("/api/users"))
  *
  * // Create an async client with custom config
  * val asyncClient = Http.client
  *   .withBaseUri("https://api.example.com")
  *   .withMaxRetry(5)
  *   .newAsyncClient
  *
  * // Future: Create a server
  * // val server = Http.server.withPort(8080).start()
  * }}}
  */
object Http:
  /**
    * Entry point for creating HTTP clients. Uses the default platform-specific channel factory.
    */
  def client: HttpClientConfig = HttpClientConfig.default.withChannelFactory(defaultChannelFactory)

  /**
    * Entry point for opening WebSocket client connections, using the default platform-specific
    * channel factory. Currently implemented on the JVM.
    */
  def webSocketClient: WebSocketClient = defaultChannelFactory.newWebSocketClient

  /**
    * Default HTTP channel factory. Initialized from the platform-specific
    * `HttpCompat.defaultHttpChannelFactory` so cross-platform callers can use
    * `Http.client.newSyncClient` without per-platform setup. Callers that need a non-default
    * factory can override via `setDefaultChannelFactory`.
    *
    * `@volatile` so `setDefaultChannelFactory` writes are visible across threads on the JVM. No-op
    * on Scala.js / Native.
    */
  @volatile
  private[http] var defaultChannelFactory: HttpChannelFactory = HttpCompat.defaultHttpChannelFactory

  /**
    * Override the default channel factory. The default is the platform-specific factory from
    * `HttpCompat`; callers (e.g., `wvlet-server`) can substitute their own implementation.
    */
  def setDefaultChannelFactory(factory: HttpChannelFactory): Unit = defaultChannelFactory = factory
