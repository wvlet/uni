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

import wvlet.uni.control.ResultClass.{Failed, nonRetryableFailure, retryableFailure}

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.*
import java.nio.channels.ClosedChannelException
import java.util.ServiceLoader
import java.util.concurrent.ExecutionException
import javax.net.ssl.{
  SSLException,
  SSLHandshakeException,
  SSLKeyException,
  SSLPeerUnverifiedException
}

/**
  * JVM-specific HTTP compatibility layer with full SSL and connection exception handling.
  */
private[http] object HttpCompat extends HttpCompatApi:

  override val defaultHttpChannelFactory: HttpChannelFactory = JVMHttpChannelFactory

  // uni can't depend on uni-netty (it depends on uni), so the backend registers itself as a
  // ServiceLoader provider in META-INF/services. Look in the thread's context class loader, then in
  // uni's own, for hosts that load uni in a child class loader the context loader can't see.
  override def startServer(config: HttpServerConfig): HttpServer =
    val providerClass = classOf[HttpServerProvider]
    var providers     = ServiceLoader.load(providerClass).iterator()
    if !providers.hasNext then
      providers = ServiceLoader.load(providerClass, providerClass.getClassLoader).iterator()
    if !providers.hasNext then
      throw IllegalStateException(
        "No HTTP server backend on the classpath: add uni-netty (\"org.wvlet.uni\" %% \"uni-netty\") to serve Http.server on the JVM"
      )
    providers.next().start(config)

  override def sslExceptionClassifier: PartialFunction[Throwable, Failed] =
    case e: SSLException =>
      e match
        // Deterministic SSL exceptions are not retryable
        case _: SSLHandshakeException =>
          nonRetryableFailure(e)
        case _: SSLKeyException =>
          nonRetryableFailure(e)
        case _: SSLPeerUnverifiedException =>
          nonRetryableFailure(e)
        case _ =>
          // SSLProtocolException and other SSL exceptions may be retryable
          retryableFailure(e)

  override def connectionExceptionClassifier: PartialFunction[Throwable, Failed] =
    case e: InterruptedException =>
      retryableFailure(e)
    case e: ProtocolException =>
      retryableFailure(e)
    case e: ConnectException =>
      retryableFailure(e)
    case e: ClosedChannelException =>
      retryableFailure(e)
    case e: SocketTimeoutException =>
      retryableFailure(e)
    case e: SocketException =>
      e match
        case _: BindException =>
          retryableFailure(e)
        case _: NoRouteToHostException =>
          retryableFailure(e)
        case _: PortUnreachableException =>
          retryableFailure(e)
        case se if se.getMessage == "Socket closed" =>
          retryableFailure(e)
        case _ =>
          nonRetryableFailure(e)
    // HTTP/2 GOAWAY handling
    case e: IOException if Option(e.getMessage).exists(_.contains("GOAWAY received")) =>
      retryableFailure(e)

  override def rootCauseExceptionClassifier: PartialFunction[Throwable, Failed] =
    case e: ExecutionException if e.getCause != null =>
      HttpExceptionClassifier.classifyExecutionFailure(e.getCause)
    case e: InvocationTargetException if e.getTargetException != null =>
      HttpExceptionClassifier.classifyExecutionFailure(e.getTargetException)
    case e if e.getCause != null =>
      HttpExceptionClassifier.classifyExecutionFailure(e.getCause)

end HttpCompat
