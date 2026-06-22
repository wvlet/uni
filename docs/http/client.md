# HTTP Client

uni provides both synchronous and asynchronous HTTP clients.

## Creating Clients

### Sync Client

```scala
import wvlet.uni.http.Http

val client = Http.client.newSyncClient
```

### Async Client

```scala
val asyncClient = Http.client.newAsyncClient
```

## Platform Backends

The same `Http.client` API runs on all three Scala platforms. uni selects the
right transport for the runtime automatically — you write the request once and
it works on JVM, Scala.js, and Scala Native.

| Platform | Sync (`newSyncClient`) | Async (`newAsyncClient`) | Underlying transport |
|----------|------------------------|--------------------------|----------------------|
| **JVM** | `JavaHttpChannel` | `JavaHttpAsyncChannel` | `java.net.http.HttpClient` (Java 11+) |
| **Scala.js** | `NodeSyncHttpChannel` *(Node.js only)* | `FetchChannel` | Fetch API (browser + Node), Node `worker_threads` for sync |
| **Scala Native** | `CurlChannel` | `CurlAsyncChannel` | libcurl |

Both client types share the same `send` / `sendStreaming` interface, so most
code is platform-agnostic. A few platform constraints are worth knowing:

- **Browsers have no synchronous HTTP.** On Scala.js, `newSyncClient` works
  only under Node.js (it uses `worker_threads` + `Atomics.wait` on a
  `SharedArrayBuffer`). In a browser, calling it throws `NotImplementedError` —
  use `newAsyncClient`, which returns `Rx[HttpResponse]`, instead. For the
  background on the Node sync implementation, see the
  [ADR](https://github.com/wvlet/uni/blob/main/adr/2026-05-14-nodejs-sync-http.md).
- **Scala Native requires libcurl** to be available at runtime; the factory
  initializes it globally the first time a client is created.
- **The async client is the cross-platform common denominator.** If you target
  the browser, prefer the async API everywhere so the same code compiles for
  every platform.

To plug in a custom transport (testing, a different HTTP library), set a
channel factory globally:

```scala
import wvlet.uni.http.Http

Http.setDefaultChannelFactory(myChannelFactory)
```

## Making Requests

### GET Request

```scala
import wvlet.uni.http.Request

val response = client.send(Request.get("https://api.example.com/users"))
println(response.status)           // HttpStatus
println(response.contentAsString)  // Response body
```

### POST Request

```scala
val request = Request
  .post("https://api.example.com/users")
  .withJsonContent("""{"name": "Alice"}""")

val response = client.send(request)
```

### With Headers

```scala
val request = Request
  .get("https://api.example.com/data")
  .withBearerToken("token123")
  .withAccept("application/json")

val response = client.send(request)
```

### Multipart Upload (multipart/form-data)

Use `withMultipart` to upload files alongside form fields in a single request.
The boundary and `Content-Type: multipart/form-data; boundary=...` header are
generated automatically.

```scala
import wvlet.uni.http.{Request, MultipartPart, ContentType}

val avatarBytes: Array[Byte] = readBytes("avatar.png")

val request = Request
  .post("https://api.example.com/upload")
  .withMultipart(Seq(
    MultipartPart.field("name", "alice"),
    MultipartPart.file("avatar", "avatar.png", avatarBytes, ContentType.ImagePng)
  ))

val response = client.send(request)
```

`MultipartPart.file` defaults to `application/octet-stream` when no content
type is given. `MultipartPart.field` is a plain form value serialized as
UTF-8. To add custom per-part headers, construct the case classes directly:

```scala
val request = Request
  .post("https://api.example.com/upload")
  .withMultipart(Seq(
    MultipartPart.FilePart(
      name = "report",
      filename = "q1.pdf",
      bytes = pdfBytes,
      contentType = ContentType.ApplicationPdf,
      headers = HttpMultiMap("X-Checksum-Sha256" -> checksum)
    )
  ))
```

For a fixed boundary (for example in snapshot tests), use the builder:

```scala
import wvlet.uni.http.Multipart

val mp = Multipart
  .builder()
  .withBoundary("----fixed-boundary")
  .addField("name", "alice")
  .addFile("avatar", "avatar.png", avatarBytes, ContentType.ImagePng)
  .build()

val request = Request.post("/upload").withMultipartContent(mp)
```

**Scope note**: `multipart/form-data` is intended for bundling form fields and
small-to-moderate files (avatars, PDFs, a few MB at most) in a single
request. All parts are held in memory. Large-file or resumable uploads (S3
multipart, tus.io) use separate protocols and are out of scope for this
API.

## Content Types and Headers

The examples above use two small helper types.

`ContentType` is an `application/...`-style media type. It provides named
constants for the common ones, so you avoid typo-prone string literals:

```scala
import wvlet.uni.http.ContentType

ContentType.ApplicationJson   // "application/json"
ContentType.ApplicationPdf    // "application/pdf"
ContentType.ApplicationOctetStream
ContentType.ImagePng          // "image/png"
ContentType.TextPlain
ContentType.TextEventStream    // "text/event-stream" (SSE)

ContentType("application/vnd.api+json")  // any custom type
```

`HttpMultiMap` is the case-insensitive multi-valued map used for headers and
form fields (a header can legitimately appear more than once). Build one from
pairs, and add to it without mutating the original:

```scala
import wvlet.uni.http.HttpMultiMap

val headers = HttpMultiMap("Accept" -> "application/json")
val more    = headers + ("X-Request-Id" -> "abc123")
```

You rarely construct an `HttpMultiMap` directly — request builders like
`withBearerToken` and `withAccept` manage headers for you — but it's the type
you reach for when adding custom per-part headers to a multipart upload.

## Response Handling

```scala
val response = client.send(request)

// Status
response.status.isSuccess    // true for 2xx
response.status.code         // 200, 404, etc.

// Headers
response.header("Content-Type")  // Option[String]

// Body
response.contentAsString     // String
response.contentAsBytes      // Array[Byte]
```

## Async Requests

```scala
val asyncClient = Http.client.newAsyncClient

asyncClient
  .send(Request.get("https://api.example.com/data"))
  .map { response =>
    response.contentAsString
  }
  .subscribe { content =>
    println(content)
  }
```

## Streaming Responses

Stream large responses as byte chunks:

```scala
asyncClient
  .sendStreaming(Request.get("https://example.com/large-file"))
  .subscribe { chunk: Array[Byte] =>
    processChunk(chunk)
  }
```

## Client Configuration

```scala
val client = Http.client
  .withConnectTimeoutMillis(5000)   // 5 seconds
  .withReadTimeoutMillis(30000)     // 30 seconds
  .withMaxRetry(3)
  .newSyncClient
```

## Disabling Retry

```scala
// Create a client with no retries
val clientNoRetry = Http.client.noRetry.newSyncClient

// Or configure zero retries
val clientNoRetry2 = Http.client.withMaxRetry(0).newSyncClient
```

## Error Handling

```scala
import wvlet.uni.http.HttpException

try
  val response = client.send(request)
  if !response.status.isSuccess then
    throw HttpException(response.status, response.contentAsString)
catch
  case e: HttpException =>
    logger.error(s"HTTP error: ${e.status}")
  case e: IOException =>
    logger.error("Network error", e)
```

## Best Practices

1. **Reuse clients** - Create once, use many times
2. **Close clients** - Call `close()` when done
3. **Set timeouts** - Prevent hanging requests
4. **Handle errors** - Check status codes
5. **Use async** - For non-blocking operations

## Example: REST Client

```scala
class ApiClient(baseUrl: String):
  private val client = Http.client.newSyncClient

  def getUser(id: String): User =
    val response = client.send(
      Request.get(s"${baseUrl}/users/${id}")
    )
    if response.status.isSuccess then
      Weaver.fromJson[User](response.contentAsString)
    else
      throw HttpException(response.status)

  def createUser(user: User): User =
    val response = client.send(
      Request
        .post(s"${baseUrl}/users")
        .withJsonContent(Weaver.toJson(user))
    )
    Weaver.fromJson[User](response.contentAsString)

  def close(): Unit = client.close()
```
