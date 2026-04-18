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

import java.io.IOException
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
  *   - Automatic decompression (via Accept-Encoding)
  *
  * Note: This is a synchronous implementation. The async variant wraps sync in Rx.single since
  * libcurl's multi API adds significant complexity.
  */
class CurlChannel extends HttpChannel:
  import CurlBindings.*
  import CurlCallbacks.*

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
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

        // Add Accept-Encoding for automatic decompression if not present
        if !request.hasHeader(HttpHeader.AcceptEncoding) then
          headerList = curl_slist_append(headerList, c"Accept-Encoding: gzip, deflate")

        if headerList != null then
          curl_easy_setopt_slist(curl, CURLOPT_HTTPHEADER, headerList)

        // Set request body
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

        // Set write callback for response body
        curl_easy_setopt_callback(curl, CURLOPT_WRITEFUNCTION, writeCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_WRITEDATA, bodyBuffer.asInstanceOf[Ptr[Byte]])

        // Set header callback for response headers
        curl_easy_setopt_callback(curl, CURLOPT_HEADERFUNCTION, headerCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_HEADERDATA, headerBuffer.asInstanceOf[Ptr[Byte]])

        // Perform the request
        val result = curl_easy_perform(curl)

        if result != CURLE_OK then
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

        // Get response status code
        val statusCodePtr = stackalloc[Long]()
        curl_easy_getinfo_long(curl, CURLINFO_RESPONSE_CODE, statusCodePtr)
        val statusCode = (!statusCodePtr).toInt

        // Parse response headers
        val headerString   = getBufferString(headerBuffer)
        val responseHeader = parseHeaders(headerString)

        // Get response body
        val responseBody = getBufferData(bodyBuffer)

        // Build response
        val status  = HttpStatus.ofCode(statusCode)
        val content =
          if responseBody.isEmpty then
            HttpContent.Empty
          else
            HttpContent.bytes(responseBody)

        Response(status, responseHeader, content)
      }
    finally
      // Cleanup
      if headerList != null then
        curl_slist_free_all(headerList)
      freeResponseBuffer(bodyBuffer)
      freeResponseBuffer(headerBuffer)
      curl_easy_cleanup(curl)
    end try

  end send

  override def close(): Unit = ()

  private def checkCurlError(code: CInt, operation: String): Unit =
    if code != CURLE_OK then
      val errorMsg = fromCString(curl_easy_strerror(code))
      throw IOException(s"curl error while ${operation}: ${errorMsg} (code: ${code})")

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
  * True async would require libcurl's multi API which adds significant complexity. This wrapper
  * uses Rx.single to wrap the synchronous implementation.
  */
class CurlAsyncChannel extends HttpAsyncChannel:
  private val syncChannel = CurlChannel()

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] = Rx.single(
    syncChannel.send(request, config)
  )

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    // For Native, we don't have true streaming - return entire body as single chunk
    Rx.single(syncChannel.send(request, config).content.toContentBytes)

  override def close(): Unit = syncChannel.close()

end CurlAsyncChannel
