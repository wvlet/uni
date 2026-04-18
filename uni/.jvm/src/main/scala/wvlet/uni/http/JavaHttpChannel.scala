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

import wvlet.uni.rx.{Rx, RxDeferred, RxScheduler}

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient.Redirect
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpClient
import java.util.function.Consumer
import java.util.zip.{GZIPInputStream, InflaterInputStream}
import scala.jdk.CollectionConverters.*

// Type alias to avoid confusion with java.net.http.HttpResponse
private[http] type JHttpResponse = java.net.http.HttpResponse[InputStream]

/**
  * Shared helper methods for Java HTTP channel implementations.
  */
private[http] object JavaHttpChannelHelper:
  def buildRequest(request: Request, config: HttpClientConfig): java.net.http.HttpRequest =
    val builder = java
      .net
      .http
      .HttpRequest
      .newBuilder(URI.create(request.fullUri))
      .timeout(java.time.Duration.ofMillis(config.readTimeoutMillis))

    request
      .wireHeaders
      .entries
      .foreach { case (k, v) =>
        builder.setHeader(k, v)
      }

    builder.method(
      request.method.name,
      if request.content.isEmpty then
        BodyPublishers.noBody()
      else
        BodyPublishers.ofByteArray(request.content.toContentBytes)
    )

    builder.build()

  def readResponse(httpResponse: JHttpResponse): Response =
    val headerBuilder = HttpMultiMap.newBuilder
    httpResponse
      .headers()
      .map()
      .asScala
      .foreach { case (key, values) =>
        // Skip pseudo-headers (HTTP/2)
        if !key.startsWith(":") then
          values
            .asScala
            .foreach { v =>
              headerBuilder.add(key, v)
            }
      }
    val headers = headerBuilder.result()
    val status  = HttpStatus.ofCode(httpResponse.statusCode())

    // Handle content decompression
    val inputStream =
      headers.get(HttpHeader.ContentEncoding).map(_.toLowerCase) match
        case Some("gzip") =>
          GZIPInputStream(httpResponse.body())
        case Some("deflate") =>
          InflaterInputStream(httpResponse.body())
        case _ =>
          httpResponse.body()

    try
      val body    = inputStream.readAllBytes()
      val content =
        if body.isEmpty then
          HttpContent.Empty
        else
          HttpContent.bytes(body)
      Response(status, headers, content)
    finally
      inputStream.close()

  end readResponse

end JavaHttpChannelHelper

/**
  * Synchronous HTTP channel implementation using Java 11+ HttpClient.
  */
class JavaHttpChannel extends HttpChannel:
  import JavaHttpChannelHelper.*

  private val javaClient: HttpClient = HttpClient
    .newBuilder()
    .followRedirects(Redirect.NEVER) // Redirects handled by DefaultHttpSyncClient
    .build()

  override def send(request: Request, config: HttpClientConfig): Response =
    val httpReq  = buildRequest(request, config)
    val httpResp = javaClient.send(httpReq, BodyHandlers.ofInputStream())
    readResponse(httpResp)

  override def close(): Unit = ()

end JavaHttpChannel

/**
  * Asynchronous HTTP channel implementation using Java 11+ HttpClient.
  */
class JavaHttpAsyncChannel extends HttpAsyncChannel:
  import JavaHttpChannelHelper.*

  private val javaClient: HttpClient = HttpClient
    .newBuilder()
    .followRedirects(Redirect.NEVER)
    .build()

  override def send(request: Request, config: HttpClientConfig): Rx[Response] =
    val deferred = RxDeferred[Response]()

    try
      val httpReq = buildRequest(request, config)
      javaClient
        .sendAsync(httpReq, BodyHandlers.ofInputStream())
        .thenAccept(
          new Consumer[JHttpResponse]:
            override def accept(r: JHttpResponse): Unit =
              try
                val resp = readResponse(r)
                deferred.complete(resp).run()
              catch
                case e: Throwable =>
                  deferred.completeWithError(e).run()
        )
        .exceptionally { (ex: Throwable) =>
          deferred.completeWithError(ex).run()
          null
        }
    catch
      case e: Throwable =>
        deferred.completeWithError(e).run()

    deferred.get

  override def sendStreaming(request: Request, config: HttpClientConfig): Rx[Array[Byte]] =
    val source = Rx.variable[Option[Array[Byte]]](None)

    RxScheduler
      .blocking
      .execute { () =>
        try
          val httpReq  = buildRequest(request, config)
          val httpResp = javaClient.send(httpReq, BodyHandlers.ofInputStream())
          val stream   = httpResp.body()

          try
            val buffer = new Array[Byte](8192)
            var len    = stream.read(buffer)
            while len != -1 do
              source.set(Some(java.util.Arrays.copyOf(buffer, len)))
              len = stream.read(buffer)
            source.stop()
          finally
            stream.close()
        catch
          case e: Throwable =>
            source.setException(e)
      }

    source.filter(_.isDefined).map(_.get)

  override def close(): Unit = ()

end JavaHttpAsyncChannel
