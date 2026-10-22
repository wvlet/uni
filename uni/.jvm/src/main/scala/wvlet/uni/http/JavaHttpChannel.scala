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
import java.util.concurrent.atomic.AtomicBoolean
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

  /**
    * Read the response status and headers without consuming the body.
    */
  def readResponseHead(httpResponse: JHttpResponse): Response =
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
    Response(HttpStatus.ofCode(httpResponse.statusCode()), headerBuilder.result())

  /**
    * Open the response body, decompressing it according to Content-Encoding.
    */
  def openBody(head: Response, httpResponse: JHttpResponse): InputStream =
    head.headers.get(HttpHeader.ContentEncoding).map(_.toLowerCase) match
      case Some("gzip") =>
        GZIPInputStream(httpResponse.body())
      case Some("deflate") =>
        InflaterInputStream(httpResponse.body())
      case _ =>
        httpResponse.body()

  def readResponse(httpResponse: JHttpResponse): Response = readResponseBody(
    readResponseHead(httpResponse),
    httpResponse
  )

  def readResponseBody(head: Response, httpResponse: JHttpResponse): Response =
    val inputStream = openBody(head, httpResponse)
    try
      val body    = inputStream.readAllBytes()
      val content =
        if body.isEmpty then
          HttpContent.Empty
        else
          HttpContent.bytes(body)
      head.withContent(content)
    finally
      inputStream.close()

  /**
    * Stream chunks read from the input stream returned by `open`. Reading runs on the blocking
    * scheduler and starts only once the stream is subscribed, so no chunk is emitted before the
    * subscriber is ready. An exception thrown by `open` fails the stream.
    */
  def streamChunks(open: () => InputStream): Rx[Array[Byte]] =
    val source  = Rx.variable[Option[Array[Byte]]](None)
    val started = AtomicBoolean(false)

    def readAll(): Unit =
      try
        val stream = open()
        try
          val buffer = new Array[Byte](8192)
          var len    = stream.read(buffer)
          while len != -1 do
            if len > 0 then
              source.set(Some(java.util.Arrays.copyOf(buffer, len)))
            len = stream.read(buffer)
          source.stop()
        finally
          stream.close()
      catch
        case e: Throwable =>
          source.setException(e)

    source
      .tap { chunk =>
        // RxVar emits its current value (None) right after registering a subscriber
        if chunk.isEmpty && started.compareAndSet(false, true) then
          RxScheduler.blocking.execute(() => readAll())
      }
      .filter(_.isDefined)
      .map(_.get)

  end streamChunks

  def isEventStreamResponse(request: Request, head: Response): Boolean =
    request.eventHandler != ServerSentEventHandler.empty && head.isSuccessful &&
      head.contentType.exists(_.isEventStream)

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
    val head     = readResponseHead(httpResp)
    if isEventStreamResponse(request, head) then
      // Leave the event stream unread; the client dispatches the events to the request's handler
      head.withEvents(
        ServerSentEventParser.parseStream(streamChunks(() => openBody(head, httpResp)))
      )
    else
      readResponseBody(head, httpResp)

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

  end sendStreaming

  override def sendStreaming(
      request: Request,
      config: HttpClientConfig,
      onResponse: Response => Unit
  ): Rx[Array[Byte]] = streamChunks { () =>
    val httpResp = javaClient.send(buildRequest(request, config), BodyHandlers.ofInputStream())
    val head     = readResponseHead(httpResp)
    try
      onResponse(head)
      if head.isSuccessful then
        openBody(head, httpResp)
      else
        throw HttpException.fromResponse(readResponseBody(head, httpResp))
    catch
      case e: Throwable =>
        httpResp.body().close()
        throw e
  }

  override def close(): Unit = ()

end JavaHttpAsyncChannel
