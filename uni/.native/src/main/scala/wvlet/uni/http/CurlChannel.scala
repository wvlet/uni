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

import wvlet.uni.rx.{Rx, RxScheduler}

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import scala.scalanative.unsafe.*

/**
  * HTTP channel implementation using libcurl for Scala Native.
  *
  * This implementation uses libcurl's easy interface for making HTTP requests. It supports:
  *   - All standard HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
  *   - Custom headers
  *   - Request body
  *   - Response headers and body
  *   - Timeouts (connect and read)
  *   - Automatic decompression (via CURLOPT_ACCEPT_ENCODING)
  *
  * `send` blocks in `curl_easy_perform`. Streaming uses the multi interface instead (see
  * [[CurlAsyncChannel]]), so that it can hand over chunks and stop on cancel.
  */
class CurlChannel extends HttpChannel:
  import CurlBindings.*
  import CurlCallbacks.*

  /**
    * How long a streaming transfer waits on the network before checking for a cancel again.
    */
  private val StreamPollMillis: CInt = 100

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
    withTransfer(request, config) { (curl, bodyBuffer, headerBuffer) =>
      val result = curl_easy_perform(curl)
      if result != CURLE_OK then
        throwCurlError(result)
      readHead(curl, headerBuffer).withContent(toContent(getBufferData(bodyBuffer)))
    }

  /**
    * Run a request through the multi interface, passing body bytes to `emit` as they arrive.
    * `onResponse` gets the status and headers before the first chunk. When `failOnError` is set, a
    * non-2xx body is read fully and thrown as an [[HttpException]] instead of being emitted.
    *
    * Returns early, closing the connection, once `isCancelled` turns true; it is checked at least
    * every [[StreamPollMillis]]. Curl callbacks only fill malloc'd buffers, and this thread copies
    * them out between `curl_multi_perform` calls, so no callback touches the GC heap.
    */
  private[http] def stream(
      request: HttpRequest,
      config: HttpClientConfig,
      isCancelled: () => Boolean,
      onResponse: HttpResponse => Unit,
      failOnError: Boolean,
      emit: Array[Byte] => Unit
  ): Unit =
    withTransfer(request, config) { (curl, bodyBuffer, headerBuffer) =>
      val multi = curl_multi_init()
      if multi == null then
        throw IOException("Failed to initialize curl multi handle")
      try
        // CURLOPT_TIMEOUT_MS limits the whole transfer, which would end a long stream. A stream has
        // no total timeout, as on the JVM, where the read timeout covers only the response head.
        curl_easy_setopt_long(curl, CURLOPT_TIMEOUT_MS, 0L)
        checkMultiError(curl_multi_add_handle(multi, curl), "adding the transfer")
        try
          val running              = stackalloc[CInt]()
          val numfds               = stackalloc[CInt]()
          val queued               = stackalloc[CInt]()
          var head: HttpResponse   = null
          var result: Option[CInt] = None
          def streamsBody: Boolean = head.isSuccessful || !failOnError
          def deliverHead(): Unit  =
            head = readHead(curl, headerBuffer)
            onResponse(head)
          def drainBody(): Unit =
            if head != null && streamsBody && bodyBuffer._2 > 0L then
              emit(takeBufferData(bodyBuffer))

          while result.isEmpty && !isCancelled() do
            checkMultiError(curl_multi_perform(multi, running), "running the transfer")
            if head == null && headersComplete(getBufferString(headerBuffer)) then
              deliverHead()
            drainBody()

            var msg = curl_multi_info_read(multi, queued)
            while msg != null do
              if msg._1 == CURLMSG_DONE then
                result = Some(!msg.at3.asInstanceOf[Ptr[CInt]])
              msg = curl_multi_info_read(multi, queued)

            if result.isEmpty && !isCancelled() then
              checkMultiError(
                curl_multi_wait(multi, StreamPollMillis, numfds),
                "waiting for the transfer"
              )
              if !numfds == 0 then
                // Nothing to wait on yet (e.g. name resolution): don't spin
                Thread.sleep(10)
          end while

          result.foreach { code =>
            if code != CURLE_OK then
              throwCurlError(code)
            if head == null then
              deliverHead()
            drainBody()
            if !streamsBody then
              throw HttpException.fromResponse(
                head.withContent(toContent(getBufferData(bodyBuffer)))
              )
          }
        finally
          curl_multi_remove_handle(multi, curl)
        end try
      finally
        curl_multi_cleanup(multi)
      end try
    }

  /**
    * Set up an easy handle for the request, run `body` with it and its body and header buffers, and
    * free them all afterwards.
    */
  private def withTransfer[A](request: HttpRequest, config: HttpClientConfig)(
      body: (CURL, Ptr[ResponseBuffer], Ptr[ResponseBuffer]) => A
  ): A =
    val curl = curl_easy_init()
    if curl == null then
      throw IOException("Failed to initialize curl handle")

    var headerList: Ptr[CurlSlist] = null
    val bodyBuffer                 = allocResponseBuffer()
    val headerBuffer               = allocResponseBuffer()

    try
      Zone.acquire { implicit z =>
        // Set URL
        checkCurlError(
          curl_easy_setopt_str(curl, CURLOPT_URL, toCString(request.fullUri)),
          "setting URL"
        )

        // Set HTTP method
        request.method match
          case HttpMethod.GET =>
            curl_easy_setopt_long(curl, CURLOPT_HTTPGET, 1L)
          case HttpMethod.POST =>
            curl_easy_setopt_long(curl, CURLOPT_POST, 1L)
          case HttpMethod.HEAD =>
            curl_easy_setopt_long(curl, CURLOPT_NOBODY, 1L)
          case HttpMethod.PUT | HttpMethod.DELETE | HttpMethod.PATCH | HttpMethod.OPTIONS =>
            curl_easy_setopt_str(curl, CURLOPT_CUSTOMREQUEST, toCString(request.method.name))
          case other =>
            curl_easy_setopt_str(curl, CURLOPT_CUSTOMREQUEST, toCString(other.name))

        // Set request headers
        for (key, value) <- request.wireHeaders.entries do
          val headerLine = s"${key}: ${value}"
          headerList = curl_slist_append(headerList, toCString(headerLine))

        // Let curl advertise every encoding it supports and decode the response. A raw
        // Accept-Encoding header alone would leave the body compressed. A caller-supplied
        // Accept-Encoding header still replaces the one curl sends, and curl decodes whatever
        // encoding the server picks.
        curl_easy_setopt_str(curl, CURLOPT_ACCEPT_ENCODING, c"")

        if headerList != null then
          curl_easy_setopt_slist(curl, CURLOPT_HTTPHEADER, headerList)

        // Set request body. Curl doesn't copy it, so the zone must outlive the transfer.
        val contentBytes = request.content.toContentBytes
        if contentBytes.nonEmpty then
          val bodyPtr = alloc[Byte](contentBytes.length)
          var i       = 0
          while i < contentBytes.length do
            !(bodyPtr + i) = contentBytes(i)
            i += 1
          curl_easy_setopt_ptr(curl, CURLOPT_POSTFIELDS, bodyPtr)
          curl_easy_setopt_long(curl, CURLOPT_POSTFIELDSIZE, contentBytes.length.toLong)

        // Set timeouts
        if config.connectTimeoutMillis > 0 then
          curl_easy_setopt_long(curl, CURLOPT_CONNECTTIMEOUT_MS, config.connectTimeoutMillis)

        if config.readTimeoutMillis > 0 then
          curl_easy_setopt_long(curl, CURLOPT_TIMEOUT_MS, config.readTimeoutMillis)

        // Follow redirects handled by DefaultHttpSyncClient, but set FOLLOWLOCATION to 0
        // to let uni's redirect handling work
        curl_easy_setopt_long(curl, CURLOPT_FOLLOWLOCATION, 0L)

        // Keep a proxy's `200 Connection established` out of the header output, so it isn't taken
        // for the response's own header block
        curl_easy_setopt_long(curl, CURLOPT_SUPPRESS_CONNECT_HEADERS, 1L)

        // Set write callback for response body
        curl_easy_setopt_callback(curl, CURLOPT_WRITEFUNCTION, writeCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_WRITEDATA, bodyBuffer.asInstanceOf[Ptr[Byte]])

        // Set header callback for response headers
        curl_easy_setopt_callback(curl, CURLOPT_HEADERFUNCTION, headerCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_HEADERDATA, headerBuffer.asInstanceOf[Ptr[Byte]])

        body(curl, bodyBuffer, headerBuffer)
      }
    finally
      // Cleanup
      if headerList != null then
        curl_slist_free_all(headerList)
      freeResponseBuffer(bodyBuffer)
      freeResponseBuffer(headerBuffer)
      curl_easy_cleanup(curl)
    end try

  end withTransfer

  /**
    * The response status and headers, without the body.
    */
  private def readHead(curl: CURL, headerBuffer: Ptr[ResponseBuffer]): HttpResponse =
    val statusCodePtr = stackalloc[Long]()
    curl_easy_getinfo_long(curl, CURLINFO_RESPONSE_CODE, statusCodePtr)
    val statusCode = (!statusCodePtr).toInt
    Response(
      HttpStatus.ofCode(statusCode),
      parseHeaders(finalHeaderBlock(getBufferString(headerBuffer)))
    )

  private def toContent(bytes: Array[Byte]): HttpContent =
    if bytes.isEmpty then
      HttpContent.Empty
    else
      HttpContent.bytes(bytes)

  private def throwCurlError(result: CInt): Nothing =
    val errorMsg = fromCString(curl_easy_strerror(result))
    result match
      case CURLE_COULDNT_RESOLVE_HOST | CURLE_COULDNT_RESOLVE_PROXY =>
        throw HttpException.connectionFailed(s"Could not resolve host: ${errorMsg}")
      case CURLE_COULDNT_CONNECT =>
        throw HttpException.connectionFailed(s"Connection failed: ${errorMsg}")
      case CURLE_OPERATION_TIMEDOUT =>
        throw HttpException.connectionTimeout(s"Connection timed out: ${errorMsg}")
      case CURLE_SSL_CONNECT_ERROR | CURLE_PEER_FAILED_VERIFICATION =>
        throw HttpException.sslError(s"SSL error: ${errorMsg}")
      case _ =>
        throw HttpException.connectionFailed(s"curl error: ${errorMsg} (code: ${result})")

  private def checkMultiError(code: CInt, operation: String): Unit =
    if code != CURLM_OK then
      val errorMsg = fromCString(curl_multi_strerror(code))
      throw IOException(s"curl error while ${operation}: ${errorMsg} (code: ${code})")

  override def close(): Unit = ()

  private def checkCurlError(code: CInt, operation: String): Unit =
    if code != CURLE_OK then
      val errorMsg = fromCString(curl_easy_strerror(code))
      throw IOException(s"curl error while ${operation}: ${errorMsg} (code: ${code})")

  /**
    * Whether curl's header output holds the final response's complete header block. Interim 1xx
    * responses (e.g. `100 Continue`) come before it, each in its own block.
    */
  private def headersComplete(headerString: String): Boolean =
    headerString.endsWith("\r\n\r\n") && !isInterim(finalHeaderBlock(headerString))

  private def isInterim(block: String): Boolean =
    block.split(" ", 3) match
      case Array(version, code, _*) if version.startsWith("HTTP/") =>
        code.startsWith("1")
      case _ =>
        false

  /**
    * The last header block of curl's header output, which may also hold interim 1xx responses.
    */
  private def finalHeaderBlock(headerString: String): String =
    val blocks = headerString.split("\r\n\r\n")
    if blocks.isEmpty then
      ""
    else
      blocks.last

  /**
    * Parse HTTP headers from curl's header output.
    *
    * Curl returns headers in the format: "HTTP/1.1 200 OK\r\n" "Header-Name: Header-Value\r\n" ...
    */
  private def parseHeaders(headerString: String): HttpMultiMap =
    val builder = HttpMultiMap.newBuilder
    val lines   = headerString.split("\r\n")

    for line <- lines do
      // Skip empty lines and status line (starts with HTTP/)
      if line.nonEmpty && !line.startsWith("HTTP/") then
        val colonIndex = line.indexOf(':')
        if colonIndex > 0 then
          val key   = line.substring(0, colonIndex).trim
          val value = line.substring(colonIndex + 1).trim
          builder.add(key, value)

    builder.result()

end CurlChannel

/**
  * Async HTTP channel implementation wrapping CurlChannel.
  *
  * `send` wraps the synchronous implementation in `Rx.single`. `sendStreaming` drives the transfer
  * through libcurl's multi interface on the blocking scheduler, emitting chunks as they arrive;
  * cancelling the subscription closes the connection.
  */
class CurlAsyncChannel extends HttpAsyncChannel:
  private val syncChannel = CurlChannel()

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] = Rx.single(
    syncChannel.send(request, config)
  )

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    streamResponse(request, config, _ => (), failOnError = false)

  override def sendStreaming(
      request: HttpRequest,
      config: HttpClientConfig,
      onResponse: HttpResponse => Unit
  ): Rx[Array[Byte]] = streamResponse(request, config, onResponse, failOnError = true)

  private def streamResponse(
      request: HttpRequest,
      config: HttpClientConfig,
      onResponse: HttpResponse => Unit,
      failOnError: Boolean
  ): Rx[Array[Byte]] =
    // Each subscription sends its own request, so cancelling one doesn't abort another's
    Rx.single(())
      .flatMap { _ =>
        val source    = Rx.variable[Option[Array[Byte]]](None)
        val cancelled = AtomicBoolean(false)
        val started   = AtomicBoolean(false)

        def run(): Unit =
          try
            syncChannel.stream(
              request,
              config,
              () => cancelled.get(),
              onResponse,
              failOnError,
              chunk => source.set(Some(chunk))
            )
            if !cancelled.get() then
              source.stop()
          catch
            case _: Throwable if cancelled.get() =>
            // Cancelled: the error has no one to report to
            case e: Throwable =>
              source.setException(e)

        source
          .tap { chunk =>
            // RxVar emits its current value (None) right after registering a subscriber, so the
            // request starts only once someone listens and no chunk is lost
            if chunk.isEmpty && started.compareAndSet(false, true) then
              RxScheduler.blocking.execute(() => run())
          }
          .onCancel(cancelled.set(true))
          .filter(_.isDefined)
          .map(_.get)
      }

  end streamResponse

  override def close(): Unit = syncChannel.close()

end CurlAsyncChannel
