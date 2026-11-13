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
import java.util.concurrent.{CancellationException, CompletableFuture, ExecutionException}
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
    * Stream chunks read from the input stream returned by `open`. Each subscription calls `open` on
    * the blocking scheduler once it is subscribed, so no chunk is emitted before the subscriber is
    * ready, and one subscription's request is independent of another's. An exception thrown by
    * `open` fails the stream. Cancelling the subscription before the body ends aborts the transfer
    * through the given [[StreamTransfer]].
    */
  def streamChunks(open: StreamTransfer => InputStream): Rx[Array[Byte]] = Rx
    .single(())
    .flatMap { _ =>
      val source   = Rx.variable[Option[Array[Byte]]](None)
      val transfer = StreamTransfer()
      val started  = AtomicBoolean(false)

      def readAll(): Unit =
        try
          transfer.enter()
          val stream = transfer.register(open(transfer))
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
          case _: Throwable if transfer.isCancelled =>
          // Cancelled: the abort's error has no one to report to
          case e: Throwable =>
            source.setException(e)
        finally
          transfer.leave()

      source
        .tap { chunk =>
          // RxVar emits its current value (None) right after registering a subscriber
          if chunk.isEmpty && started.compareAndSet(false, true) then
            RxScheduler.blocking.execute(() => readAll())
        }
        .onCancel(transfer.cancel())
        .filter(_.isDefined)
        .map(_.get)
    }

  end streamChunks

  def isEventStreamResponse(request: Request, head: Response): Boolean =
    request.eventHandler != ServerSentEventHandler.empty && head.isSuccessful &&
      head.contentType.exists(_.isEventStream)

end JavaHttpChannelHelper

/**
  * The in-flight state of one streamed response, so that a cancel from another thread can abort it.
  * Cancelling cancels the pending request, closes the body, and interrupts the reader thread while
  * it is inside the transfer (between `enter` and `leave`), since a blocked read may not notice the
  * closed body.
  */
private[http] class StreamTransfer:
  private var cancelled                     = false
  private var reader: Thread                = null
  private var pending: CompletableFuture[?] = null
  private var body: InputStream             = null

  def isCancelled: Boolean = synchronized(cancelled)

  def enter(): Unit = synchronized {
    if cancelled then
      throw CancellationException("The stream was cancelled")
    reader = Thread.currentThread()
  }

  def leave(): Unit =
    synchronized {
      reader = null
    }
    // Clear an interrupt from cancel() so it doesn't leak into the next task of the pooled thread
    Thread.interrupted()

  /**
    * Wait for the response of a request sent with `sendAsync`. Cancelling cancels the request.
    */
  def await[A](future: CompletableFuture[A]): A =
    synchronized {
      if cancelled then
        future.cancel(true)
      pending = future
    }
    try
      future.get()
    catch
      case e: ExecutionException if e.getCause != null =>
        throw e.getCause

  def register(in: InputStream): InputStream =
    val alreadyCancelled = synchronized {
      body = in
      cancelled
    }
    if alreadyCancelled then
      in.close()
      throw CancellationException("The stream was cancelled")
    in

  def cancel(): Unit =
    val (f, in) = synchronized {
      cancelled = true
      // A downstream `take(n)` cancels on the reader thread itself; interrupting it would fail the
      // subscriber's own blocking calls before the reader returns
      if reader != null && (reader ne Thread.currentThread()) then
        reader.interrupt()
      (pending, body)
    }
    if f != null then
      f.cancel(true)
    if in != null then
      try
        in.close()
      catch
        case _: Throwable =>
          ()

end StreamTransfer

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
        ServerSentEventParser.parseStream(streamChunks(_ => openBody(head, httpResp)))
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
    streamChunks { transfer =>
      transfer
        .await(javaClient.sendAsync(buildRequest(request, config), BodyHandlers.ofInputStream()))
        .body()
    }

  override def sendStreaming(
      request: Request,
      config: HttpClientConfig,
      onResponse: Response => Unit
  ): Rx[Array[Byte]] = streamChunks { transfer =>
    val httpResp = transfer.await(
      javaClient.sendAsync(buildRequest(request, config), BodyHandlers.ofInputStream())
    )
    val head = readResponseHead(httpResp)
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
