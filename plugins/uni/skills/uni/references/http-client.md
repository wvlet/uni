# HTTP client

`Http.client` works on the JVM (java.net.http), Scala.js (Fetch; sync client on
Node.js only), and Scala Native (libcurl). Retries with backoff and redirects are
on by default.

Full reference: https://wvlet.org/uni/http/client.md

## Sync client

```scala
import wvlet.uni.http.{Http, HttpException, Request}
import wvlet.uni.weaver.Weaver

case class User(id: Long, name: String) derives Weaver

val client = Http.client
  .withBaseUri("https://api.example.com") // relative request paths resolve against it
  .withConnectTimeoutMillis(5000)
  .withReadTimeoutMillis(30000)
  .withMaxRetry(3)                        // or .noRetry
  .newSyncClient

// GET + decode JSON
val resp = client.send(Request.get("/users/1").withBearerToken(token))
val user = Weaver.fromJson[User](resp.contentAsString.getOrElse("{}"))

// POST JSON
val created = client.send(
  Request.post("/users").withJsonContent(Weaver.toJson(User(0, "alice")))
)

client.close()
```

Key facts:
- `send` returns the response for 2xx. **Other statuses throw**, so do not check
  `isSuccessful` after `send` expecting to see a 404:
  - non-retryable codes (most 4xx) throw `HttpException`;
  - retryable codes (5xx, 408, 429, ...) are retried, then throw
    `HttpMaxRetryException`. It is **not** a subclass of `HttpException`, and it
    is thrown even with `.noRetry`.
- `contentAsString` and `contentAsBytes` return `Option`.
- Other builders: `Request.put/delete/patch/head`, `.addHeader(name, value)`, `.setHeader(name, value)`,
  `.withAccept(...)`, `.withContent(HttpContent)`.
- Response: `resp.status` (`HttpStatus`), `resp.statusCode`, `resp.header(name)`
  (`Option[String]`), `resp.isSuccessful`.

## Error handling

```scala
import wvlet.uni.http.HttpMaxRetryException

try client.send(Request.get("/users/999"))
catch
  case e: HttpException =>
    e.statusCode // Option[Int], e.g. Some(404)
    e.status     // Option[HttpStatus]
    e.response   // Option[HttpResponse], with the error body
  case e: HttpMaxRetryException =>
    e.response   // the last HttpResponse (e.g. 503)
```

## Async client (Scala.js)

Browsers have no synchronous HTTP, so Scala.js code in a browser uses the async
client. **On the JVM, use the sync client**: running the JVM async client's
`Rx` with `subscribe`/`await` currently fails with a `MatchError` on
`RxDeferred.get`.

```scala
import wvlet.uni.rx.Rx

val async = Http.client.withBaseUri("https://api.example.com").newAsyncClient

val name: Rx[String] = async
  .send(Request.get("/users/1"))
  .map(resp => Weaver.fromJson[User](resp.contentAsString.getOrElse("{}")).name)

name.subscribe(n => println(n)) // or compose with other Rx
```

`async.sendStreaming(request)` returns `Rx[Array[Byte]]` chunks for large bodies.
For server-sent events, see https://wvlet.org/uni/http/sse.md.

## Typed RPC client

For a server built with `RPCRouter`, generate a typed client instead of writing
requests by hand: see [rpc.md](rpc.md).
