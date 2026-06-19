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
  * Low-level HTTP channel interface for platform-specific implementations.
  *
  * HttpChannel handles the actual HTTP communication. Platform-specific implementations (JVM,
  * Scala.js, Scala Native) should implement this interface.
  *
  * The high-level HttpSyncClient and HttpAsyncClient use HttpChannel internally to perform HTTP
  * requests, adding features like retry, redirect handling, and configuration management on top.
  *
  * Implementations:
  *   - JVM: URLConnection, OkHttp, Netty, etc.
  *   - Scala.js: Fetch API
  *   - Scala Native: libcurl
  */
trait HttpChannel extends AutoCloseable:
  /**
    * Send an HTTP request and return the response. This is the core method that platform
    * implementations must provide.
    *
    * This method should:
    *   - Establish connection to the server
    *   - Send request headers and body
    *   - Read response status, headers, and body
    *   - Handle connection timeouts and read timeouts
    *
    * This method should NOT:
    *   - Handle retries (done by HttpSyncClient/HttpAsyncClient)
    *   - Handle redirects (done by HttpSyncClient/HttpAsyncClient)
    *   - Apply default headers (done by HttpSyncClient/HttpAsyncClient)
    *
    * @param request
    *   the HTTP request to send
    * @param config
    *   client configuration for timeouts and other settings
    * @return
    *   the HTTP response
    * @throws HttpException
    *   on connection failure, timeout, or other errors
    */
  def send(request: HttpRequest, config: HttpClientConfig): HttpResponse

  def close(): Unit = ()

/**
  * Asynchronous HTTP channel interface for platform-specific implementations.
  */
trait HttpAsyncChannel extends AutoCloseable:
  /**
    * Send an HTTP request asynchronously and return the response as an Rx stream.
    *
    * @param request
    *   the HTTP request to send
    * @param config
    *   client configuration for timeouts and other settings
    * @return
    *   Rx stream that emits the HTTP response
    */
  def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse]

  /**
    * Send an HTTP request and stream the response body as chunks.
    *
    * @param request
    *   the HTTP request to send
    * @param config
    *   client configuration for timeouts and other settings
    * @return
    *   Rx stream that emits response body chunks
    */
  def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]]

  def close(): Unit = ()

/**
  * Factory for creating platform-specific HttpChannel implementations.
  *
  * Platform-specific modules should provide an implementation of this trait.
  */
trait HttpChannelFactory:
  /**
    * Create a new synchronous HTTP channel
    */
  def newChannel: HttpChannel

  /**
    * Create a new asynchronous HTTP channel
    */
  def newAsyncChannel: HttpAsyncChannel

  /**
    * Create a WebSocket client. Platforms that don't yet implement one throw.
    */
  def newWebSocketClient: WebSocketClient =
    throw NotImplementedError("WebSocket client is not supported on this platform")
