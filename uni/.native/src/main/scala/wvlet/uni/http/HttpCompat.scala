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

import wvlet.uni.control.ResultClass.Failed

/**
  * Scala Native-specific HTTP compatibility layer. Returns empty classifiers since javax.net.ssl
  * classes are not available.
  */
private[http] object HttpCompat extends HttpCompatApi:

  // A def, not a val: loading NativeHttpChannelFactory initializes libcurl, which a server-only
  // binary must not link
  override def defaultHttpChannelFactory: HttpChannelFactory = NativeHttpChannelFactory

  override def startServer(config: HttpServerConfig): HttpServer =
    val base =
      config
        .backendOptions
        .collect { case o: NativeOptions =>
          o
        }
        .foldLeft(NativeServerConfig())((c, o) => o.applyTo(c))
    base
      .copy(
        name = config.name,
        host = config.host,
        port = config.port,
        handler = config.handler,
        filters = config.filters,
        webSocketRoutes = config.webSocketRoutes,
        allowedOrigins = config.allowedOrigins,
        allowedHosts = config.allowedHosts,
        detailedErrors = config.detailedErrors,
        maxRequestSize = config.maxContentLength.getOrElse(base.maxRequestSize),
        webSocketMaxFrameSize = config.webSocketMaxFrameSize.getOrElse(base.webSocketMaxFrameSize),
        webSocketPingIntervalMillis = config
          .webSocketPingIntervalMillis
          .getOrElse(base.webSocketPingIntervalMillis)
      )
      .start()

  override def sslExceptionClassifier: PartialFunction[Throwable, Failed] = PartialFunction.empty

  override def connectionExceptionClassifier: PartialFunction[Throwable, Failed] =
    PartialFunction.empty

  override def rootCauseExceptionClassifier: PartialFunction[Throwable, Failed] =
    HttpExceptionClassifier.rootCauseExceptionClassifierSimple

end HttpCompat
