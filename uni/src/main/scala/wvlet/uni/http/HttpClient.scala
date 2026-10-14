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
  * Synchronous HTTP client interface.
  *
  * Retry is enabled by default. Use `noRetry` to disable.
  *
  * Example:
  * {{{
  * // Create client via Http.client
  * val client = Http.client.newSyncClient
  *
  * // Simple request with default retry
  * val response = client.send(HttpRequest.get("/api/users"))
  *
  * // Disable retry for specific request
  * val response = client.noRetry.send(request)
  *
  * // Custom retry configuration
  * val response = client.withMaxRetry(5).send(request)
  * }}}
  */
trait HttpSyncClient extends AutoCloseable:
  def config: HttpClientConfig

  /**
    * Send an HTTP request and return the response. Retries automatically based on config.
    */
  def send(request: HttpRequest): HttpResponse

  /**
    * Return a new client with retry disabled
    */
  def noRetry: HttpSyncClient

  /**
    * Return a new client with specified max retry count
    */
  def withMaxRetry(maxRetries: Int): HttpSyncClient

  /**
    * Return a new client with specified config
    */
  def withConfig(config: HttpClientConfig): HttpSyncClient

  def close(): Unit = ()

/**
  * Asynchronous HTTP client interface using Rx.
  *
  * Retry is enabled by default. Use `noRetry` to disable.
  *
  * Example:
  * {{{
  * // Create client via Http.client
  * val client = Http.client.newAsyncClient
  *
  * // Simple async request
  * client.send(HttpRequest.get("/api/users"))
  *   .map(response => response.contentAsString)
  *   .run(println)
  *
  * // Disable retry
  * client.noRetry.send(request)
  * }}}
  */
trait HttpAsyncClient extends AutoCloseable:
  def config: HttpClientConfig

  /**
    * Send an HTTP request asynchronously, returning an Rx stream. Retries automatically based on
    * config.
    */
  def send(request: HttpRequest): Rx[HttpResponse]

  /**
    * Send an HTTP request and stream the response as an Rx of byte chunks
    */
  def sendStreaming(request: HttpRequest): Rx[Array[Byte]]

  /**
    * Send an HTTP request and stream the response as Server-Sent Events.
    *
    * This method:
    *   - Sets the Accept header to "text/event-stream" if not already set
    *   - Streams the response body and parses it as SSE
    *   - Invokes the request's eventHandler callbacks for each event
    *   - Returns an Rx stream of parsed ServerSentEvent objects
    *
    * Example:
    * {{{
    * val handler = ServerSentEventHandler { event =>
    *   println(s"Received: \${event.data}")
    * }
    *
    * client.sendSSE(Request.get("/events").withEventHandler(handler))
    *   .run(event => println(event))
    * }}}
    */
  def sendSSE(request: HttpRequest): Rx[ServerSentEvent]

  /**
    * Return a new client with retry disabled
    */
  def noRetry: HttpAsyncClient

  /**
    * Return a new client with specified max retry count
    */
  def withMaxRetry(maxRetries: Int): HttpAsyncClient

  /**
    * Return a new client with specified config
    */
  def withConfig(config: HttpClientConfig): HttpAsyncClient

  def close(): Unit = ()

end HttpAsyncClient
