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

import wvlet.uni.control.Retry
import wvlet.uni.control.Retry.RetryContext

/**
  * Configuration for HTTP clients. Includes channel factory for creating platform-specific clients.
  */
case class HttpClientConfig(
    baseUri: Option[String] = None,
    connectTimeoutMillis: Long = 30000,
    readTimeoutMillis: Long = 60000,
    followRedirects: Boolean = true,
    maxRedirects: Int = 10,
    // Upper bound on a single response size. Currently enforced only by the Node.js sync HTTP
    // channel, which must size a fixed SharedArrayBuffer up front; other channels ignore it.
    maxResponseBytes: Int = 16 * 1024 * 1024,
    retryContext: RetryContext =
      Retry
        .withBackOff(maxRetry = 3, initialIntervalMillis = 1000, maxIntervalMillis = 30000)
        .noRetryLogging,
    requestFilter: HttpRequest => HttpRequest = identity,
    channelFactory: HttpChannelFactory = HttpClientConfig.NoOpChannelFactory
):
  def withBaseUri(uri: String): HttpClientConfig = copy(baseUri = Some(uri))
  def noBaseUri: HttpClientConfig                = copy(baseUri = None)

  def withConnectTimeoutMillis(millis: Long): HttpClientConfig = copy(connectTimeoutMillis = millis)
  def withReadTimeoutMillis(millis: Long): HttpClientConfig    = copy(readTimeoutMillis = millis)

  def withFollowRedirects: HttpClientConfig = copy(followRedirects = true)
  def noFollowRedirects: HttpClientConfig   = copy(followRedirects = false)

  def withMaxRedirects(max: Int): HttpClientConfig = copy(maxRedirects = max)

  def withMaxResponseBytes(bytes: Int): HttpClientConfig = copy(maxResponseBytes = bytes)

  def withRetryContext(ctx: RetryContext): HttpClientConfig = copy(retryContext = ctx)
  def noRetry: HttpClientConfig = copy(retryContext = retryContext.noRetry)

  def withRequestFilter(filter: HttpRequest => HttpRequest): HttpClientConfig = copy(requestFilter =
    filter
  )

  def addRequestFilter(filter: HttpRequest => HttpRequest): HttpClientConfig = copy(requestFilter =
    requestFilter.andThen(filter)
  )

  def withChannelFactory(factory: HttpChannelFactory): HttpClientConfig = copy(channelFactory =
    factory
  )

  def withMaxRetry(maxRetries: Int): HttpClientConfig = copy(retryContext =
    retryContext.withMaxRetry(maxRetries)
  )

  def resolveUri(uri: String): String =
    baseUri match
      case Some(base) if !uri.startsWith("http://") && !uri.startsWith("https://") =>
        if base.endsWith("/") && uri.startsWith("/") then
          s"${base.dropRight(1)}${uri}"
        else if !base.endsWith("/") && !uri.startsWith("/") then
          s"${base}/${uri}"
        else
          s"${base}${uri}"
      case _ =>
        uri

  /**
    * Create a new synchronous HTTP client with this configuration.
    */
  def newSyncClient: HttpSyncClient = DefaultHttpSyncClient(this, channelFactory.newChannel)

  /**
    * Create a new asynchronous HTTP client with this configuration.
    */
  def newAsyncClient: HttpAsyncClient = DefaultHttpAsyncClient(this, channelFactory.newAsyncChannel)

end HttpClientConfig

object HttpClientConfig:
  val default: HttpClientConfig = HttpClientConfig()

  /**
    * No-op channel factory used when no platform-specific implementation is available
    */
  private[http] object NoOpChannelFactory extends HttpChannelFactory:
    def newChannel: HttpChannel =
      throw NotImplementedError(
        "No HttpChannel implementation available. Import a platform-specific module."
      )

    def newAsyncChannel: HttpAsyncChannel =
      throw NotImplementedError(
        "No HttpAsyncChannel implementation available. Import a platform-specific module."
      )
