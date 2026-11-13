# REST Server

uni provides HTTP servers on all three Scala platforms — Netty on the JVM,
the Node.js `http` module on Scala.js, and a native socket server on Scala
Native — behind one shared configuration API. On the JVM you additionally get a
type-safe, annotation-based router framework for building REST APIs.

::: tip Which API is cross-platform?
**Functional handlers** (`withHandler` / `withRxHandler`) and **filters** work
on every platform. The **annotation router** (`@Endpoint`, `Router.of[T]`,
`RouterHandler`) and **WebSocket routes** are **JVM/Netty-only** today — see
[Cross-Platform Servers](#cross-platform-servers).
:::

## Quick Start

On the JVM, add the `uni-netty` module to your build (it pulls in `uni`
transitively). On Scala.js and Scala Native, the server backends are included
in `uni` itself:

```scala
// JVM only: Netty-based HTTP server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

```scala
import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.Http
import wvlet.uni.http.netty.RouterHandler

// Define a controller
class UserController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = """["alice", "bob"]"""

  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): String = s"""{"id": "${id}"}"""

// Create router and start server
val router = Router.of[UserController]
val handler = RouterHandler(router)

Http
  .server
  .withPort(8080)
  .withRxHandler(handler)
  .start { server =>
    println(s"Server running on port ${server.localPort}")
    // Server runs until the block completes
  }
```

## Cross-Platform Servers

`Http.server` starts the same server code on every platform. It picks the
platform's backend, and each returns an [`HttpServer`](#server-lifecycle):

| Platform | Backend for `Http.server` | Backend-only options |
|----------|---------------------------|----------------------------------|
| **JVM** | Netty (add the `uni-netty` module) | `Netty.options` (`wvlet.uni.http.netty.Netty`) |
| **Scala.js** | Node.js `http` | — (all settings are shared) |
| **Scala Native** | native sockets | `Native.options` (`wvlet.uni.http.Native`) |

A server that compiles and runs identically on every platform:

```scala
import wvlet.uni.http.{Http, Response}
import wvlet.uni.rx.Rx

Http
  .server
  .withPort(8080)
  .withRxHandler { request =>
    Rx.single(Response.ok(s"Hello from ${request.path}"))
  }
  .start { server =>
    println(s"Listening on ${server.localAddress}")
  }
```

`Http.server` takes these builder methods on every platform:

```scala
.withPort(8080)                       // 0 picks a random free port
.withHost("0.0.0.0")                  // default 127.0.0.1 (loopback only)
.withName("my-server")
.withHandler(f: Request => Response)        // sync handler
.withRxHandler(f: Request => Rx[Response])  // async handler
.withFilter(filter)                         // RxHttpFilter
.start { server => ... }                    // block form, auto-stops
.start()                                    // returns the HttpServer
```

On the JVM, `uni-netty` registers Netty as the backend through
`java.util.ServiceLoader`; without it on the classpath, `start()` fails with a
message that says to add it. [`examples/rpc-native`](https://github.com/wvlet/uni/tree/main/examples/rpc-native)
serves a typed RPC API this way, with one `Main` for the JVM and Scala Native.

### Tuning one backend

`Http.server` also takes the size limits every backend has:
`withMaxContentLength` (on Scala Native it covers the headers too),
`withWebSocketMaxFrameSize`, and `withWebSocketPingIntervalMillis`. Settings
you leave unset keep the backend's own default.

For an option only one backend has (Netty transport, graceful shutdown, Native
worker threads, ...), pass that backend's options to `withBackend`:
`Netty.options` (`wvlet.uni.http.netty.Netty`) on the JVM, or `Native.options`
on Scala Native. They hold only that backend's own settings, so port, handler,
and size limits are set in one place, on `Http.server`. Each backend applies
only its own options, so options for several platforms can be passed together.
(Node.js has no options of its own.)

Keep the server code shared and bind the options per platform with `Design`:

```scala
// shared
import wvlet.uni.design.Design
import wvlet.uni.http.{Http, HttpServer, Response, ServerOptions}

val serverDesign = Design
  .newDesign
  .bindProvider[ServerOptions, HttpServer] { options =>
    Http.server.withPort(8080).withHandler(_ => Response.ok("ok")).withBackend(options).start()
  }
  .onShutdown(_.stop())

// .jvm/src/main/scala
import wvlet.uni.http.netty.Netty
val platformDesign = Design
  .newDesign
  .bindInstance[ServerOptions](Netty.options.withHandlerExecutorThreads(8).withShutdownHook)

// .native/src/main/scala
import wvlet.uni.http.Native
val platformDesign = Design
  .newDesign
  .bindInstance[ServerOptions](Native.options.withWorkerThreads(32))
```

### JVM-only apps: `Netty.server`

When a project only runs on the JVM, `Netty.server` (`wvlet.uni.http.netty.Netty`)
builds the Netty server directly. Every setting, shared or Netty-only, is a
method of the one config, `start()` returns a `NettyHttpServer`, and a missing
`uni-netty` dependency fails at compile time instead of at `start()`:

```scala
import wvlet.uni.http.Response
import wvlet.uni.http.netty.Netty

Netty
  .server
  .withPort(8080)
  .withHandlerExecutorThreads(8)
  .withGracefulShutdown(quietPeriodSeconds = 2, timeoutSeconds = 15)
  .withHandler(_ => Response.ok("ok"))
  .start { server =>
    println(s"Listening on ${server.localAddress}")
  }
```

Pick `Http.server` when the same server code should also build for Scala.js or
Scala Native, and `Netty.server` when it never will. Switching later only
changes where the Netty-only settings go: into `withBackend(Netty.options...)`.

::: warning JVM-only features
The annotation router (`@Endpoint`, `Router.of[T]`, `RouterHandler`),
[server-side WebSocket](#websocket), and the Netty tuning knobs
(`withMaxContentLength`, native transport, graceful shutdown) exist only on the
JVM. For code that runs on every platform, route requests yourself inside a
functional handler:

```scala
import wvlet.uni.http.Http

Http
  .server
  .withPort(8080)
  .withRxHandler { request =>
    request.path match
      case "/health"      => Rx.single(Response.ok("ok"))
      case s"/users/${id}" => Rx.single(Response.ok(s"user ${id}"))
      case _              => Rx.single(Response.notFound)
  }
  .start()
```
:::

::: warning Use the Boehm GC on Scala Native
Build a Scala Native server app with `nativeConfig ~= (_.withGC(scala.scalanative.build.GC.boehm))`,
and install `libgc` (`libgc-dev` on Debian/Ubuntu, `bdw-gc` on Homebrew). Scala Native 0.5.12's
default collector, Immix, can free or overwrite live objects when several threads allocate at once
([scala-native#5046](https://github.com/scala-native/scala-native/issues/5046),
[#5160](https://github.com/scala-native/scala-native/issues/5160)). The server runs handlers on
worker threads, so under concurrent load it then fails requests or aborts.
:::

The rest of this page uses `Http.server` on the JVM (Netty) and the annotation router, which is
the richest path. The request/response model, response conversion, and filters
described below apply to functional handlers on every platform.

## Defining Endpoints

Use the `@Endpoint` annotation to mark controller methods as HTTP endpoints:

```scala
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.router.Endpoint

class ApiController:
  @Endpoint(HttpMethod.GET, "/health")
  def health(): String = "ok"

  @Endpoint(HttpMethod.POST, "/data")
  def createData(request: Request): Response =
    val body = request.content.toContentString
    Response.ok(s"Received: ${body}")

  @Endpoint(HttpMethod.PUT, "/items/:id")
  def updateItem(id: String, request: Request): Response =
    Response.ok(s"Updated item ${id}")

  @Endpoint(HttpMethod.DELETE, "/items/:id")
  def deleteItem(id: String): Response =
    Response.noContent
```

Only `wvlet.uni.http.router.Endpoint` defines a route. An annotation from another framework with the same simple name (e.g., one kept on an API trait for documentation generation) is ignored, and a uni `@Endpoint` whose method or path cannot be read fails when the router is built.

### Path Parameters

Use `:paramName` syntax in the path to capture path segments:

```scala
class ItemController:
  @Endpoint(HttpMethod.GET, "/items/:id")
  def getItem(id: String): String = s"Item ${id}"

  @Endpoint(HttpMethod.GET, "/users/:userId/posts/:postId")
  def getUserPost(userId: String, postId: String): String =
    s"User ${userId}, Post ${postId}"
```

Path parameter names must match the method parameter names.

### Query Parameters

Method parameters that don't match path parameters are automatically bound from query string:

```scala
class SearchController:
  @Endpoint(HttpMethod.GET, "/search")
  def search(query: String, limit: Int = 10): String =
    s"Searching for '${query}' with limit ${limit}"
```

Request: `GET /search?query=hello&limit=20`

Supported parameter types:
- `String`
- `Int`, `Long`, `Short`, `Byte`
- `Double`, `Float`
- `Boolean`
- `Option[T]` for optional parameters

### Request Object Access

Add a `Request` parameter to access the full request:

```scala
class MyController:
  @Endpoint(HttpMethod.POST, "/upload")
  def upload(request: Request): Response =
    val contentType = request.headers.get("Content-Type")
    val body = request.content.toContentString
    Response.ok(s"Received ${body.length} bytes")
```

### Request Body

Parameters not bound from the path or query string are read from the request body, decoded with a [Weaver](../core/weaver.md):

```scala
case class Message(role: String, text: String)
case class MessagesRequest(model: String, messages: Seq[Message])

class ChatController:
  // The whole JSON body decodes into `request`; `httpRequest` is the raw Request
  @Endpoint(HttpMethod.POST, "/v1/messages")
  def messages(request: MessagesRequest, httpRequest: Request): Reply = ???

  // Several body parameters: each is read from the JSON object field of its name
  @Endpoint(HttpMethod.POST, "/projects/:id/rename")
  def rename(id: String, name: String, tags: Seq[String]): Project = ???
```

- When exactly one parameter, other than `Request`, `RequestAttributes`, and path parameters, is not a primitive, the whole body decodes into it, and the other parameters come from the query string. A collection or map parameter takes the whole body only when it is the only such parameter. Otherwise the body must be an object, and each parameter is read from the field of the same name. Unknown fields are ignored.
- Content types: JSON (`application/json`, `*+json`, or no `Content-Type`), and MessagePack (`application/msgpack` or `application/x-msgpack`).
- Custom codecs: a `given Weaver[A]` in `A`'s companion object, or imported where `Router.of` is called, is used when the parameter type is `A`, or a case class with an `A` field. Weavers are resolved when `Router.of` is compiled, so a given that is only in scope elsewhere is not seen. For `Option[A]`, `Seq[A]`, or a sealed trait, define a given for that type to get the same.
- Errors return 400 and never echo the body: malformed JSON or MessagePack, a value of the wrong shape, or a missing body for a non-`Option` object parameter. A required body in another content type (e.g. a form post) returns 415.
- A method that also takes `Request` may read the body itself: a body that does not fit its other parameters is an error only when a required parameter is left without a value. A body without a `Content-Type` that does not parse is always left alone.

## Request and Response

### Request Properties

```scala
request.method              // HttpMethod (GET, POST, etc.)
request.uri                 // Full URI string
request.path                // Path portion of URI
request.headers             // HttpHeaders
request.content             // HttpContent (request body)
request.getQueryParam(name) // Option[String]
```

### Response Factory Methods

```scala
Response.ok                        // 200 OK
Response.ok("body")                // 200 with text body
Response.created                   // 201 Created
Response.noContent                 // 204 No Content
Response.badRequest("message")     // 400 Bad Request
Response.notFound                  // 404 Not Found
Response.notFound("message")       // 404 with message
Response.internalServerError(msg)  // 500 Internal Server Error
```

### Response Content

```scala
Response.ok
  .withTextContent("Hello")           // text/plain
  .withJsonContent("""{"a": 1}""")    // application/json
  .withBytesContent(bytes)            // application/octet-stream
  .withContent(HttpContent.json(str)) // Custom content
  .addHeader("X-Custom", "value")     // Add header
```

## Response Conversion

Controller methods can return various types that are automatically converted to responses:

| Return Type | Conversion |
|-------------|------------|
| `Response` | Returned as-is |
| `Rx[Response]` | Async response |
| `String` | 200 OK with text/plain |
| `Seq[T]` | 200 OK with JSON array |
| `Map[K, V]` | 200 OK with JSON object |
| `Option[T]` | Value or 204 No Content |
| `Unit` | 204 No Content |
| Other types | Serialized to JSON |

```scala
class DataController:
  @Endpoint(HttpMethod.GET, "/text")
  def getText(): String = "plain text"

  @Endpoint(HttpMethod.GET, "/list")
  def getList(): Seq[String] = Seq("a", "b", "c")

  @Endpoint(HttpMethod.GET, "/map")
  def getMap(): Map[String, Int] = Map("count" -> 42)

  @Endpoint(HttpMethod.GET, "/async")
  def getAsync(): Rx[Response] =
    Rx.single(Response.ok("async result"))
```

## Filters

Use `RxHttpFilter` to intercept requests and responses:

```scala
import wvlet.uni.http.{RxHttpFilter, RxHttpHandler}

val loggingFilter = RxHttpFilter { (request, next) =>
  println(s"Request: ${request.method} ${request.path}")
  next
    .handle(request)
    .map { response =>
      println(s"Response: ${response.status}")
      response
    }
}

val authFilter = RxHttpFilter { (request, next) =>
  request.headers.get("Authorization") match
    case Some(token) if isValid(token) =>
      next.handle(request.addHeader("X-User-Id", extractUserId(token)))
    case _ =>
      Rx.single(Response.unauthorized.withTextContent("Missing or invalid token"))
}
```

### Handling Errors in a Filter

An exception thrown by a controller method reaches the enclosing filters as a failed `Rx`, the same as an exception inside a returned `Rx`. A filter can `recover` from it and map the original exception to a response, so error classification can live in one place:

```scala
val errorFilter = RxHttpFilter { (request, next) =>
  next
    .handle(request)
    .recover {
      case e: IllegalArgumentException => Response.badRequest(e.getMessage)
    }
}
```

An exception that no filter recovers from becomes a generic `500 Internal Server Error` (see [Security Defaults](#security-defaults)). Because a failed `Rx` skips `map`, a filter that decorates every response (logging, headers) never sees this 500. Place such a filter outside an error filter that recovers, or handle the failure itself with `recover`/`tapOnFailure`. A malformed path or query parameter is answered with 400 by `RouterHandler` itself, before any exception reaches a filter.

### Applying Filters

```scala
// Apply filter to server
Http
  .server
  .withPort(8080)
  .withFilter(loggingFilter)
  .withFilter(authFilter)
  .withRxHandler(handler)
  .start()

// Or compose filters
val combinedFilter = loggingFilter.andThen(authFilter)
```

### Router-Level Filters

Define filters that apply to specific controllers:

```scala
class LogFilter extends RxHttpFilter:
  def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    println(s"Handling: ${request.path}")
    next.handle(request)

val router = Router
  .filter[LogFilter]
  .andThen(Router.of[UserController])
```

### Passing Data from a Filter to Controllers

A filter can attach server-side attributes to the request, e.g. the identity an authentication filter resolved. Attributes never come from the client: a request decoded by the server starts with none, so a client cannot spoof them with headers. Since `Request` is immutable, each attribute stays with the request it was set on.

```scala
import wvlet.uni.http.{Request, RequestAttribute, RequestAttributes, RxHttpFilter}

object Auth:
  // Keys compare by identity: share this instance rather than re-creating it by name
  val Identity: RequestAttribute[User] = RequestAttribute("identity")

  val filter = RxHttpFilter { (request, next) =>
    authenticate(request) match
      case Some(user) => next.handle(request.withAttribute(Identity, user))
      case None       => Rx.single(Response.unauthorized)
  }

class ApiController:
  @Endpoint(HttpMethod.GET, "/me")
  def me(request: Request): User = request.attribute(Auth.Identity).get

  // Or take the attributes alone
  @Endpoint(HttpMethod.GET, "/me/name")
  def name(attributes: RequestAttributes): String = attributes.get(Auth.Identity).get.name
```

The Netty server sets `RequestAttribute.RemoteAddress` (`host:port` of the connected peer, with IPv6 hosts in brackets, which is the load balancer when there is one). `RequestAttributes.toString` prints only the attribute names, so logging a request does not leak their values.

### Access Log

`HttpAccessLogFilter` writes one entry per request, using the field names of Airframe's `HttpLogger` (`method`, `path`, `status_code`, `response_time_ms`, `request_size`, `response_size`, `remote_address`, `request_header`, `response_header`, `exception_class`, ...):

```scala
import wvlet.uni.http.{HttpAccessLogConfig, HttpAccessLogFilter, HttpAccessLogWriter}
import wvlet.uni.log.FileLogHandlerConfig

val accessLog = HttpAccessLogFilter(
  HttpAccessLogWriter.jsonFile(FileLogHandlerConfig("log/http_server.json")),
  HttpAccessLogConfig()
    .withStreamWriter(HttpAccessLogWriter.jsonFile(FileLogHandlerConfig("log/http_stream.json")))
)

Http
  .server
  .withFilter(accessLog.andThen(authFilter)) // outermost, so it sees the final response
  .withRxHandler(RouterHandler(router))
  .start()

// In an inner filter or a controller
HttpAccessLogFilter.addEntry(request, "user_id", user.id)
```

- `authorization`, `proxy-authorization`, `cookie`, `set-cookie`, and `x-api-key` are logged as `[redacted]`. `withRedactedHeaders(...)` replaces the list, and `withExcludedHeaders(...)` leaves headers out.
- A request that reaches an `@Endpoint` or RPC method also gets `rpc_interface`, `rpc_class`, `rpc_method`, and `rpc_args`, the decoded method arguments. These fields are recorded even when the method throws.
- Parameters and fields marked `@secret` (`wvlet.uni.surface.secret`) are left out of `rpc_args`, at any depth inside case classes, `Seq`, `Option`, `Map`, and arrays. A value whose fields can't be inspected, such as an argument typed as a sealed trait, is logged as its class name, never as its `toString`. `Request` arguments are skipped. `noRpcArgs()` turns `rpc_args` off.
- A controller exception is logged with status 500 and its class and message, and is then rendered by the server as usual.
- An SSE response gets a second entry when the stream ends (`stream_event_count`, `stream_bytes`, `stream_duration_ms`), written to the stream writer.
- `jsonFile` writes JSON lines with daily and size-based rotation. For Fluentd or another sink, implement `HttpAccessLogWriter.write(entry: Map[String, Any])`.
- Requests rejected before filters run (oversized or malformed bodies, read timeouts, the host check) are not logged.

### CORS

`CorsFilter` lets a page on another origin call the server. It answers CORS preflights (`OPTIONS` with `Access-Control-Request-Method`) itself, and adds `Access-Control-Allow-Origin` to responses for allowed origins. The default `CorsPolicy` allows no origin:

```scala
import wvlet.uni.http.{CorsFilter, CorsPolicy, HttpMethod}

val cors = CorsFilter(
  CorsPolicy()
    .withAllowedOrigins("https://app.example.com", "http://localhost:5173")
    .withAllowedMethods(HttpMethod.GET, HttpMethod.POST)
    .withAllowCredentials(true)
)

Http
  .server
  .withFilter(accessLog) // outermost
  .withFilter(cors)      // a server filter, so it also answers preflights for unrouted paths
  .withRxHandler(RouterHandler(router))
  .start()
```

- An allowed preflight gets `204` with `Access-Control-Allow-Methods`, `-Headers`, `-Max-Age` (600 s by default), and `-Credentials` when enabled. A preflight from a disallowed origin, or for a method or header the policy doesn't allow, gets `403`.
- Requests from a disallowed origin are still handled, but get no CORS headers, so the browser keeps the page from reading the response. CORS protects what a page can read; it does not authenticate requests.
- Responses get `Vary: Origin` (kept alongside the handler's own `Vary` values), so a cache does not serve one origin's answer to another. With `withAllowedOrigins("*")` the answer is the same for every origin, and `Vary` is left alone.
- `withAllowedOrigins` matches exactly, ignoring case and a trailing slash. For patterns, use `withOriginFilter(o => pattern.matches(o))`, and anchor the pattern at the end so `app.example.com.evil.test` does not match.
- `withAllowedOrigins("*")` answers `Access-Control-Allow-Origin: *`. It cannot be combined with `withAllowCredentials(true)`: `CorsFilter` throws `IllegalArgumentException`.
- By default a preflight may ask for any request header. `withAllowedHeaders(...)` limits them, and `withExposedHeaders(...)` lets the page read more response headers.
- A handler failure on a request from an allowed origin becomes a generic 500 inside `CorsFilter`, with the CORS headers, so the browser sees the 500 rather than a CORS error. The exception is logged, and the access log records its `exception_class` and `exception_message`. The body is always `Internal Server Error`, even with `withDetailedErrors`. Failures on other requests reach the server unchanged. Add an error filter that maps exceptions to statuses after `CorsFilter` (inside it): placed outside, it never sees an allowed origin's failure, which `CorsFilter` has already turned into a 500.
- WebSocket upgrades are not affected; they use [`withAllowedOrigins`](#allowed-origins) on the server.

## Server Configuration

### Server Options

```scala
import wvlet.uni.http.Http
import wvlet.uni.http.netty.Netty

Http
  .server
  .withPort(8080)                      // Port to listen on (0 for random)
  .withHost("0.0.0.0")                 // Bind address (default: 127.0.0.1)
  .withName("my-server")               // Server name for logging
  .withMaxContentLength(1024 * 1024)   // Max request body size (Netty default: 64KB)
  .withBackend(
    Netty
      .options
      .withMaxHeaderSize(8192)             // Max header size
      .withMaxInitialLineLength(4096)      // Max request line length
      .withRequestReadTimeoutMillis(30000) // Close connections that stall before a full request
      .withMaxDecompressedRequestSize(64 * 1024 * 1024) // Max decoded size of a gzip/deflate body
      .noNativeTransport                   // Disable native transport (epoll/kqueue)
  )
  .withHandler(handler)
  .start()
```

Netty-only options go in `Netty.options` and Native-only ones in
`Native.options`; see [Tuning one backend](#tuning-one-backend).

### Request Decompression

The Netty server decodes request bodies sent with `Content-Encoding: gzip` or `deflate` (and the `x-gzip` / `x-deflate` aliases) before any filter or handler sees them, and removes the `Content-Encoding` header. Other encodings such as `br` pass through unchanged.

- A compressed body that decodes to more than `withMaxDecompressedRequestSize` bytes (default 64 MiB) gets a plain-text `413`, and the connection is closed. Uncompressed requests are not counted against this limit.
- A malformed compressed body gets a plain-text `400`, and the connection is closed.
- The decoded body must also fit in `withMaxContentLength`, so raise both for large compressed uploads.
- `Netty.options.noRequestDecompression` passes the compressed bytes to the handler, e.g. to authenticate a request before inflating it.

### Security Defaults

All three backends start with these defaults:

| Default | Why | Opt out |
|---------|-----|---------|
| Bind to `127.0.0.1` | An unauthenticated dev or sidecar server is not reachable from the network | `.withHost("0.0.0.0")` for Docker, Kubernetes, or any server other hosts must reach |
| 500 responses say only `Internal Server Error`; RPC errors say `Internal error` | Exception messages can leak file paths, SQL, hostnames, or credentials. The full exception is logged on the server | `.withDetailedErrors(true)` on the server config, which also covers `RouterHandler` controller exceptions; `RPCHandler(router).withDetailedErrors(true)` for RPC |
| On a loopback server, requests whose `Host` is not `localhost`, `*.localhost`, `127.0.0.1` or `[::1]` get 403 | DNS rebinding: a web page can point its own host name at 127.0.0.1 and then read the local server as same-origin. The browser still sends the page's host name in `Host` | `.withAllowedHosts("app.example.com", "*.internal.example")` to replace the list, `.noHostCheck` to accept any. A server bound to `0.0.0.0` accepts any host unless `withAllowedHosts` is set |
| WebSocket upgrades from another browser origin get 403 | Browsers attach cookies to cross-origin WebSocket handshakes (cross-site WebSocket hijacking) | `.withAllowedOrigins("https://app.example.com")`, or `"*"` for any origin |
| Bounded request size | A single huge request cannot exhaust memory | `.withMaxContentLength` (defaults: Netty 64 KB, Node 1 MB with 413 for larger bodies, Native 1 MB including headers); `Netty.options.withMaxDecompressedRequestSize` for gzip/deflate bodies (default 64 MiB) |
| Request read timeout (Netty: 30s) | Bounds slowloris clients and idle keep-alive connections | `Netty.options.withRequestReadTimeoutMillis(...)` / `.noRequestReadTimeout`. On Native: `Native.options.withIdleTimeoutMillis(...)` / `.withReadTimeoutMillis(...)` |
| Bounded long-lived connections | Idle Server-Sent Events streams (and WebSockets on Native) cannot exhaust threads or starve ordinary requests; over the limit they get 503 | `Netty.options.withMaxStreamingConnections(n)` / `Native.options.withMaxStreamingConnections(n)`. Netty default: 256 SSE streams. Native default: `workerThreads` minus up to 4 workers kept for ordinary requests |
| CR, LF, or NUL in a header name or value is rejected | Prevents HTTP response splitting when a header echoes user input | — (`addHeader`/`setHeader` throw `IllegalArgumentException`) |

Requests without an `Origin` header (CLI tools, server-to-server clients) and same-origin pages are always accepted for WebSocket. A malformed path or query parameter (e.g. `?limit=abc` for an `Int`) returns 400 rather than 500.

TLS is not built in: terminate HTTPS at a reverse proxy or load balancer.

### Tuning a Public API Server

The defaults suit a small internal service. A public API server usually reviews these together:

| Setting | Default | When to change |
|---------|---------|----------------|
| `.withMaxContentLength(n)` | 64 KB | Raise it to the largest request body the API accepts, e.g. LLM prompts with images. A decompressed body must also fit in this limit |
| `Netty.options.withRequestReadTimeoutMillis(ms)` | 30 s | The same timer closes idle keep-alive connections. Keep it longer than the idle timeout of the load balancer in front of the server, or the server closes connections the balancer still reuses |
| `Netty.options.withMaxStreamingConnections(n)` | 256 | The maximum number of concurrent SSE streams per server; streams beyond it get 503. Size it to the expected number of concurrent streaming clients |
| `.withHost("0.0.0.0")` / `.withAllowedHosts(...)` | `127.0.0.1`, loopback host check | Required behind a load balancer or in a container |
| `.withDetailedErrors(true)` | off | Keep it off in production; the full exception is logged on the server |

### Server Lifecycle

```scala
// Start and get server instance
val server = Http
  .server
  .withPort(8080)
  .withHandler(handler)
  .start()

println(s"Running on port ${server.localPort}")
server.isRunning  // true

// Stop when done
server.stop()
server.isRunning  // false
```

### Block-based Server

For simpler use cases, use the block form that automatically stops the server:

```scala
Http
  .server
  .withPort(8080)
  .withHandler(handler)
  .start { server =>
    // Server runs while this block executes
    println(s"Server on port ${server.localPort}")
    Thread.sleep(60000)  // Run for 1 minute
  }
// Server automatically stopped here
```

## Simple Handler

For simple cases without routing, use a direct handler:

```scala
Http
  .server
  .withPort(8080)
  .withHandler { request =>
    Response.ok(s"Hello from ${request.path}")
  }
  .start()
```

Or with async responses:

```scala
Http
  .server
  .withPort(8080)
  .withRxHandler { request =>
    Rx.single(Response.ok("async response"))
  }
  .start()
```

## Static Files

`StaticFiles` serves files from a directory, such as a built web frontend, next to your API on the
same server. It is a filter: a `GET` for a file under the root returns the file, and every other
request goes on to your handler.

```scala
import wvlet.uni.http.{Http, StaticFiles}

Http
  .server
  .withPort(8080)
  .withFilter(StaticFiles("dist").withSpaFallback(true))
  .withRxHandler(api)
  .start()
```

| Request | Response |
|---------|----------|
| `GET /assets/app.js` | The file, as `text/javascript; charset=utf-8` |
| `GET /` or `GET /docs/` | The directory's `index.html` (`withIndexFile` changes the name) |
| `POST /assets/app.js`, `GET /api/users` | Your handler |
| `GET /chats/42` from a browser, unknown to your handler | `index.html`, with `withSpaFallback(true)` |

It works on the JVM, Node.js, and Scala Native. A relative root resolves against the current
directory, and the root may be created after the server starts.

### Safe defaults

A request that could reach outside the root, or reach a file you did not mean to publish, gets a
plain 404. The response doesn't say why, so it reveals nothing about the file system.

- **Path traversal**: each path segment is percent-decoded once, and a segment of `.` or `..`, an
  empty one, or one containing `/`, `\`, `:`, NUL, or another control character is refused. So
  are names that Windows rejects or aliases (`<>"|?*`, a trailing dot or space, 8.3 short names
  like `GIT~1`), on every platform.
- **Symbolic links**: every component below the root is checked without following links, and a
  link (or NTFS junction) anywhere below the root is refused, even one that points inside it. The root itself may be
  a link.
- **Dotfiles**: names starting with `.` (`.env`, `.git/`) are refused, except `.well-known/`.
  `withDotfiles(true)` serves them.
- **Directories**: only the index file is served; there are no listings. A directory path needs
  its trailing slash (`/docs/`), so relative links in its index resolve correctly.
- **Content types**: a built-in table covers the usual web build output (HTML, CSS, JavaScript,
  JSON, source maps, SVG, images, fonts, WebAssembly). Other extensions are served as
  `application/octet-stream`, and every response sets `X-Content-Type-Options: nosniff` so a
  browser never guesses a type. Add a type with `withContentType("glb", ContentType("model/gltf-binary"))`.
- **Caching**: every file gets an `ETag` from its size and modification time, and
  `Cache-Control: no-cache`, so the browser revalidates on each load. A request whose
  `If-None-Match` matches gets `304 Not Modified` without the file being read, and a new build is
  picked up on the next load. Set another policy with `withCacheControl("max-age=3600")`, or send
  none with `noCacheControl`.

Filters run in the order you add them. Add `StaticFiles` after an authentication filter to protect
the files, or before it to keep them public.

::: tip For app bundles
A request that is not answered with 304 reads the whole file, with no `Range` requests or streaming. That
fits the HTML, scripts, and assets of a web app. Put large media behind a CDN or a dedicated file
server.
:::

## WebSocket

The Netty server supports WebSocket connections. WebSocket routes are registered by path,
separately from the REST router, because connections are stateful and bidirectional rather than
request/response. A fresh `WebSocketHandler` is created per connection, so it may hold
per-connection state; all of its callbacks have no-op defaults, so you override only what you need.

```scala
import wvlet.uni.http.{WebSocketContext, WebSocketHandler}
import wvlet.uni.http.Http

Http
  .server
  .withPort(8080)
  .withWebSocketRoute("/ws/echo") { request =>
    new WebSocketHandler:
      override def onOpen(ctx: WebSocketContext): Unit =
        ctx.send("welcome")
      override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
        ctx.send(s"echo: ${message}")
      override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit =
        ctx.send(message)
      override def onClose(ctx: WebSocketContext): Unit =
        println("connection closed")
      override def onError(ctx: WebSocketContext, e: Throwable): Unit =
        println(s"error: ${e.getMessage}")
  }
  .start()
```

### WebSocketHandler Callbacks

| Callback | When it fires |
|----------|---------------|
| `onOpen(ctx)` | After the handshake completes, before any message |
| `onTextMessage(ctx, message)` | A complete text message arrived |
| `onBinaryMessage(ctx, message)` | A complete binary message arrived |
| `onClose(ctx)` | The connection closed (client- or server-initiated); delivered exactly once |
| `onError(ctx, e)` | A callback or the connection raised an error |

### WebSocketContext

The `ctx` passed to each callback controls the connection. Its `send`/`close` methods are safe to
call from any thread:

```scala
ctx.request                      // the original HTTP upgrade request
ctx.send("hello")                // send a text message
ctx.send(Array[Byte](1, 2, 3))   // send a binary message
ctx.close()                      // close with 1000 (normal closure)
ctx.close(1001, "going away")    // close with a status code and reason
```

### Allowed Origins

Before the route filter runs, the server checks the browser `Origin` of the upgrade. Same-origin
pages and clients that send no `Origin` are accepted; any other origin gets 403. Allow a front end
served from another origin (e.g. a Vite dev server) explicitly:

```scala
Http
  .server
  .withPort(8080)
  .withAllowedOrigins("http://localhost:5173", "https://app.example.com")
  .withWebSocketRoute("/ws") { _ => handler }
```

### Gating the Upgrade With a Filter

Pass an `RxHttpFilter` to authenticate or otherwise gate the upgrade handshake. A 2xx response
allows the upgrade; a non-2xx response (or an empty `Rx`) rejects it. Attributes the filter adds to
the request are visible via `ctx.request`:

```scala
import wvlet.uni.http.{Response, RxHttpFilter, WebSocketContext, WebSocketHandler}
import wvlet.uni.http.Http
import wvlet.uni.rx.Rx

val authFilter = RxHttpFilter { (request, next) =>
  request.header("Authorization") match
    case Some(token) if isValid(token) =>
      next.handle(request.addHeader("X-User", userOf(token)))
    case _ =>
      Rx.single(Response.unauthorized)
}

Http
  .server
  .withPort(8080)
  .withWebSocketRoute("/ws/secure", authFilter) { request =>
    new WebSocketHandler:
      override def onOpen(ctx: WebSocketContext): Unit =
        ctx.send(s"hello ${ctx.request.header("X-User").getOrElse("anonymous")}")
  }
  .start()
```

### Frame Size

Inbound messages are aggregated from continuation frames up to a configurable limit (1 MB by
default):

```scala
Http
  .server
  .withWebSocketMaxFrameSize(4 * 1024 * 1024)  // 4 MB
  .withWebSocketRoute("/ws") { _ => new WebSocketHandler {} }
  .start()
```

WebSocket support is server-side and JVM (Netty) only. WebSocket routes coexist with REST endpoints
on the same server and port. For connecting *to* a WebSocket endpoint, see the cross-platform
[WebSocket Client](./websocket).

## Combining Controllers

Combine multiple controllers into a single router:

```scala
class UserController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = "[]"

class ItemController:
  @Endpoint(HttpMethod.GET, "/items")
  def listItems(): String = "[]"

val router = Router.of[UserController]
  .andThen(Router.of[ItemController])

val handler = RouterHandler(router)
```

## Example: Complete REST API

```scala
import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.RxHttpFilter
import wvlet.uni.http.Http
import wvlet.uni.http.netty.RouterHandler
import wvlet.uni.rx.Rx

case class User(id: String, name: String)

class UserController:
  private var users = Map(
    "1" -> User("1", "Alice"),
    "2" -> User("2", "Bob")
  )

  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): Seq[User] = users.values.toSeq

  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): Response =
    users.get(id) match
      case Some(user) => Response.ok.withJsonContent(s"""{"id":"${user.id}","name":"${user.name}"}""")
      case None => Response.notFound(s"User ${id} not found")

  @Endpoint(HttpMethod.POST, "/users")
  def createUser(request: Request): Response =
    // Parse and create user
    Response.created.withJsonContent("""{"id":"3","name":"New User"}""")

  @Endpoint(HttpMethod.DELETE, "/users/:id")
  def deleteUser(id: String): Response =
    users -= id
    Response.noContent

val loggingFilter = RxHttpFilter { (request, next) =>
  val start = System.currentTimeMillis()
  next.handle(request).map { response =>
    val duration = System.currentTimeMillis() - start
    println(s"${request.method} ${request.path} -> ${response.status} (${duration}ms)")
    response
  }
}

val router = Router.of[UserController]
val handler = RouterHandler(router)

Http
  .server
  .withPort(8080)
  .withFilter(loggingFilter)
  .withRxHandler(handler)
  .start { server =>
    println(s"API running at http://localhost:${server.localPort}")
    Thread.currentThread().join()  // Run indefinitely
  }
```
