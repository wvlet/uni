# HTTP Channel Implementation for JVM/JS/Native

## Overview

Implement platform-specific HTTP channels for all three platforms, adapting battle-tested code from airframe-http:
- **JVM**: Java 11+ HttpClient (not URLConnection)
- **JS**: Fetch API
- **Native**: libcurl bindings

## Reference Implementation

All implementations are adapted from airframe-http:
- JVM: `JavaHttpClientChannel.scala` (Java 11+ HttpClient)
- JS: `JSFetchChannel.scala` (Fetch API)
- Native: `CurlChannel.scala` (libcurl bindings)

## New Files

```
uni/.jvm/src/main/scala/wvlet/uni/http/
  JavaHttpChannel.scala         # Java 11+ HttpClient sync/async
  JVMHttpChannelFactory.scala   # Factory + auto-registration

uni/.js/src/main/scala/wvlet/uni/http/
  FetchChannel.scala            # Fetch API async channel
  JSHttpChannelFactory.scala    # Factory + auto-registration

uni/.native/src/main/scala/wvlet/uni/http/
  CurlBindings.scala            # libcurl FFI bindings
  CurlChannel.scala             # libcurl-based channel
  NativeHttpChannelFactory.scala # Factory + auto-registration

uni/.jvm/src/test/scala/wvlet/uni/http/
  JavaHttpChannelTest.scala     # JVM integration tests
```

---

## 1. JVM: Java 11+ HttpClient

### JavaHttpChannel.scala

```scala
package wvlet.uni.http

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpClient.{Redirect, Version}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.io.{InputStream, BufferedReader, InputStreamReader}
import java.util.zip.{GZIPInputStream, InflaterInputStream}
import wvlet.uni.rx.{Rx, RxScheduler}

class JavaHttpChannel extends HttpChannel:
  private val javaClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(Redirect.NEVER)  // Handled by DefaultHttpSyncClient
    .build()

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
    val httpReq = buildRequest(request, config)
    val httpResp = javaClient.send(httpReq, BodyHandlers.ofInputStream())
    readResponse(httpResp)

  override def close(): Unit = () // Java HttpClient has no close()

  private def buildRequest(request: HttpRequest, config: HttpClientConfig): java.net.http.HttpRequest =
    val builder = java.net.http.HttpRequest.newBuilder(URI.create(request.fullUri))
      .timeout(java.time.Duration.ofMillis(config.readTimeoutMillis))

    request.headers.entries.foreach((k, v) => builder.setHeader(k, v))

    builder.method(request.method.name,
      if request.content.isEmpty then BodyPublishers.noBody()
      else BodyPublishers.ofByteArray(request.content.toContentBytes)
    )
    builder.build()

  private def readResponse(resp: java.net.http.HttpResponse[InputStream]): HttpResponse =
    val headers = HttpMultiMap.fromJava(resp.headers())
    val status = HttpStatus.ofCode(resp.statusCode())

    // Handle gzip/deflate decompression
    val stream = headers.get(HttpHeader.ContentEncoding).map(_.toLowerCase) match
      case Some("gzip") => GZIPInputStream(resp.body())
      case Some("deflate") => InflaterInputStream(resp.body())
      case _ => resp.body()

    val body = stream.readAllBytes()
    stream.close()

    Response(status, headers, HttpContent.bytes(body))
```

### JavaHttpAsyncChannel.scala

```scala
class JavaHttpAsyncChannel extends HttpAsyncChannel:
  private val javaClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(Redirect.NEVER)
    .build()

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] =
    val deferred = RxDeferred[HttpResponse]()
    val httpReq = buildRequest(request, config)

    javaClient.sendAsync(httpReq, BodyHandlers.ofInputStream())
      .thenAccept { resp =>
        deferred.complete(readResponse(resp)).run()
      }
      .exceptionally { ex =>
        deferred.completeWithError(mapException(ex)).run()
        null
      }

    deferred.get

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    val source = Rx.variable[Option[Array[Byte]]](None)
    val httpReq = buildRequest(request, config)

    RxScheduler.blocking.execute { () =>
      val resp = javaClient.send(httpReq, BodyHandlers.ofInputStream())
      val stream = resp.body()
      val buffer = new Array[Byte](8192)
      var len = stream.read(buffer)
      while len != -1 do
        source.set(Some(java.util.Arrays.copyOf(buffer, len)))
        len = stream.read(buffer)
      stream.close()
      source.stop()
    }

    source.filter(_.isDefined).map(_.get)
```

### JVMHttpChannelFactory.scala

```scala
object JVMHttpChannelFactory extends HttpChannelFactory:
  def newChannel: HttpChannel = JavaHttpChannel()
  def newAsyncChannel: HttpAsyncChannel = JavaHttpAsyncChannel()

// Auto-register in HttpCompat.scala
// Add at end: Http.setDefaultChannelFactory(JVMHttpChannelFactory)
```

---

## 2. JS: Fetch API

### FetchChannel.scala

```scala
package wvlet.uni.http

import org.scalajs.dom.{fetch, Headers, RequestInit, RequestRedirect}
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.*
import wvlet.uni.rx.Rx

class FetchChannel extends HttpAsyncChannel:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  override def send(request: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] =
    val req = new RequestInit {
      method = request.method.name.asInstanceOf[org.scalajs.dom.HttpMethod]
      headers = new Headers(request.headers.entries.map((k, v) =>
        js.Array(k, v)).toJSArray)
      redirect = RequestRedirect.manual  // Handled by DefaultHttpAsyncClient
      body = if request.content.isEmpty then js.undefined
             else request.content.toContentBytes.toTypedArray
    }

    val future = fetch(request.fullUri, req).toFuture.flatMap { resp =>
      val headers = HttpMultiMap.fromJS(resp.headers)
      val status = HttpStatus.ofCode(resp.status)

      resp.arrayBuffer().toFuture.map { buf =>
        val bytes = new Int8Array(buf).toArray
        Response(status, headers, HttpContent.bytes(bytes))
      }
    }

    Rx.fromFuture(future)

  override def sendStreaming(request: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
    // Use ReadableStream for chunked reading
    val source = Rx.variable[Option[Array[Byte]]](None)

    fetch(request.fullUri, req).toFuture.foreach { resp =>
      val reader = resp.body.getReader()
      def process(): Unit =
        reader.read().toFuture.foreach { result =>
          if result.done then
            reader.releaseLock()
            source.stop()
          else
            val chunk = new Int8Array(result.value.buffer).toArray
            source.set(Some(chunk))
            process()
        }
      process()
    }

    source.filter(_.isDefined).map(_.get)

  // Sync not supported in JS
  def send(request: HttpRequest, config: HttpClientConfig): HttpResponse = ???
  def close(): Unit = ()
```

### JSHttpChannelFactory.scala

```scala
object JSHttpChannelFactory extends HttpChannelFactory:
  def newChannel: HttpChannel =
    throw NotImplementedError("Sync HTTP not supported in JavaScript")
  def newAsyncChannel: HttpAsyncChannel = FetchChannel()

// Auto-register in HttpCompat.scala (JS)
```

---

## 3. Native: libcurl

### CurlBindings.scala

```scala
package wvlet.uni.http

import scala.scalanative.unsafe.*
import scala.scalanative.libc.{stdlib, string}

object CurlBindings:
  type CURL = Ptr[Byte]
  type CurlSlist = CStruct2[CString, Ptr[Byte]]
  type WriteCallback = CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize]
  type ResponseBuffer = CStruct3[Ptr[Byte], Long, Long]  // data, size, capacity

  // Error codes
  final val CURLE_OK: CInt = 0
  final val CURLE_COULDNT_CONNECT: CInt = 7
  final val CURLE_OPERATION_TIMEDOUT: CInt = 28
  final val CURLE_SSL_CONNECT_ERROR: CInt = 35

  // CURLOPT constants
  final val CURLOPT_URL: CInt = 10002
  final val CURLOPT_HTTPHEADER: CInt = 10023
  final val CURLOPT_WRITEFUNCTION: CInt = 20011
  final val CURLOPT_HEADERFUNCTION: CInt = 20079
  // ... (full list from airframe)

  @link("curl") @extern
  private object Extern:
    @name("curl_easy_init") def easyInit(): CURL = extern
    @name("curl_easy_perform") def easyPerform(curl: CURL): CInt = extern
    @name("curl_easy_cleanup") def easyCleanup(curl: CURL): Unit = extern
    // ... (all curl functions)

  // Callback implementation
  val writeCallback: WriteCallback = (ptr, size, nmemb, userdata) =>
    val realSize = size * nmemb
    if userdata != null && realSize.toLong > 0L then
      appendToBuffer(userdata.asInstanceOf[Ptr[ResponseBuffer]], ptr, realSize.toLong)
    realSize
```

### CurlChannel.scala

```scala
class CurlChannel extends HttpChannel:
  import CurlBindings.*

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
    val curl = curl_easy_init()
    var headerList: Ptr[CurlSlist] = null
    val bodyBuffer = allocResponseBuffer()
    val headerBuffer = allocResponseBuffer()

    try
      Zone.acquire { implicit z =>
        // Set URL, method, headers, body, timeouts
        curl_easy_setopt_str(curl, CURLOPT_URL, toCString(request.fullUri))

        request.method match
          case HttpMethod.GET => curl_easy_setopt_long(curl, CURLOPT_HTTPGET, 1L)
          case HttpMethod.POST => curl_easy_setopt_long(curl, CURLOPT_POST, 1L)
          case other => curl_easy_setopt_str(curl, CURLOPT_CUSTOMREQUEST, toCString(other.name))

        // Set headers
        for (k, v) <- request.headers.entries do
          headerList = curl_slist_append(headerList, toCString(s"${k}: ${v}"))
        curl_easy_setopt_slist(curl, CURLOPT_HTTPHEADER, headerList)

        // Set body
        if request.content.nonEmpty then
          val bytes = request.content.toContentBytes
          val bodyPtr = alloc[Byte](bytes.length)
          // Copy bytes to native memory
          curl_easy_setopt_ptr(curl, CURLOPT_POSTFIELDS, bodyPtr)
          curl_easy_setopt_long(curl, CURLOPT_POSTFIELDSIZE, bytes.length.toLong)

        // Set timeouts
        curl_easy_setopt_long(curl, CURLOPT_CONNECTTIMEOUT_MS, config.connectTimeoutMillis)
        curl_easy_setopt_long(curl, CURLOPT_TIMEOUT_MS, config.readTimeoutMillis)

        // Set callbacks
        curl_easy_setopt_callback(curl, CURLOPT_WRITEFUNCTION, writeCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_WRITEDATA, bodyBuffer)
        curl_easy_setopt_callback(curl, CURLOPT_HEADERFUNCTION, headerCallback)
        curl_easy_setopt_ptr(curl, CURLOPT_HEADERDATA, headerBuffer)

        // Perform request
        val result = curl_easy_perform(curl)
        if result != CURLE_OK then
          throw HttpException.connectionFailed(s"curl error: ${fromCString(curl_easy_strerror(result))}")

        // Get status code
        val statusCodePtr = stackalloc[Long]()
        curl_easy_getinfo_long(curl, CURLINFO_RESPONSE_CODE, statusCodePtr)

        val status = HttpStatus.ofCode((!statusCodePtr).toInt)
        val headers = parseHeaders(getBufferString(headerBuffer))
        val body = getBufferData(bodyBuffer)

        Response(status, headers, HttpContent.bytes(body))
      }
    finally
      if headerList != null then curl_slist_free_all(headerList)
      freeResponseBuffer(bodyBuffer)
      freeResponseBuffer(headerBuffer)
      curl_easy_cleanup(curl)
```

### NativeHttpChannelFactory.scala

```scala
object NativeHttpChannelFactory extends HttpChannelFactory:
  // Initialize libcurl globally
  private val initResult = CurlBindings.curl_global_init(CurlBindings.CURL_GLOBAL_DEFAULT)

  def newChannel: HttpChannel =
    if initResult != CurlBindings.CURLE_OK then
      throw IllegalStateException("Failed to initialize libcurl")
    CurlChannel()

  def newAsyncChannel: HttpAsyncChannel =
    // Wrap sync in Rx.single (true async requires curl_multi)
    new HttpAsyncChannel:
      private val syncChannel = newChannel
      def send(req: HttpRequest, config: HttpClientConfig): Rx[HttpResponse] =
        Rx.single(syncChannel.send(req, config))
      def sendStreaming(req: HttpRequest, config: HttpClientConfig): Rx[Array[Byte]] =
        Rx.single(syncChannel.send(req, config).content.toContentBytes)
      def close(): Unit = syncChannel.close()

// Auto-register in HttpCompat.scala (Native)
```

---

## Build Configuration

### build.sbt changes

```scala
// Native needs libcurl linking
lazy val uni = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .nativeSettings(
    nativeConfig ~= { _.withLinkingOptions(_ :+ "-lcurl") }
  )
```

---

## Auto-Registration

Update each platform's `HttpCompat.scala`:

**JVM** (`uni/.jvm/src/main/scala/wvlet/uni/http/HttpCompat.scala`):
```scala
// Add at end of object
Http.setDefaultChannelFactory(JVMHttpChannelFactory)
```

**JS** (`uni/.js/src/main/scala/wvlet/uni/http/HttpCompat.scala`):
```scala
Http.setDefaultChannelFactory(JSHttpChannelFactory)
```

**Native** (`uni/.native/src/main/scala/wvlet/uni/http/HttpCompat.scala`):
```scala
Http.setDefaultChannelFactory(NativeHttpChannelFactory)
```

---

## Verification

1. **Compile all platforms**: `./sbt compile`
2. **Format**: `./sbt scalafmtAll`
3. **JVM Tests**: `./sbt "uniJVM/testOnly wvlet.uni.http.*"`
4. **JS Tests**: `./sbt "uniJS/testOnly wvlet.uni.http.*"`
5. **Native Tests**: `./sbt "uniNative/testOnly wvlet.uni.http.*"`

## Test Plan

```scala
class HttpChannelTest extends UniTest:
  test("GET request"):
    val client = Http.client.newSyncClient  // or newAsyncClient for JS
    val response = client.send(Request.get("https://httpbin.org/get"))
    response.status shouldBe HttpStatus.Ok_200

  test("POST with JSON"):
    val response = client.send(
      Request.post("https://httpbin.org/post")
        .withJsonContent("""{"test": true}""")
    )
    response.status shouldBe HttpStatus.Ok_200
    response.contentAsString.get shouldContain "test"

  test("Timeout handling"):
    val client = Http.client.withConnectTimeoutMillis(1).newSyncClient
    intercept[HttpException]:
      client.send(Request.get("https://10.255.255.1/"))
```

## Summary

| Platform | Channel | Async Support | Streaming |
|----------|---------|---------------|-----------|
| JVM | Java 11+ HttpClient | Native (sendAsync) | Via InputStream |
| JS | Fetch API | Native (Promise) | Via ReadableStream |
| Native | libcurl | Wrapped sync | Via buffer |
