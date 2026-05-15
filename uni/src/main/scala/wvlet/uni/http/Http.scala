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
    * Platform-specific default channel factory. This will be provided by platform-specific modules
    * (.jvm, .js, .native).
    */
  private[http] var defaultChannelFactory: HttpChannelFactory = HttpClientConfig.NoOpChannelFactory

  /**
    * Set the default channel factory. Called by platform-specific initialization.
    */
  def setDefaultChannelFactory(factory: HttpChannelFactory): Unit = defaultChannelFactory = factory

  // Touch the platform's HttpCompat object so its class-init side-effect runs, registering the
  // platform default channel factory before any caller reaches `Http.client.newSyncClient`. Without
  // this, downstream code has to call `Http.setDefaultChannelFactory(...)` itself in per-platform
  // sources, because HttpCompat only loads when the error-classifier path runs.
  private val _httpCompatInit = HttpCompat
