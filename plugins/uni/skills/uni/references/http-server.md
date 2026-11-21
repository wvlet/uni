# HTTP server

`Http.server` (`wvlet.uni.http.Http`, returns `HttpServerConfig`) runs the same
server code on every platform with `uni` alone. On the JVM it uses a built-in
server that needs JDK 24+, or Netty when the optional `uni-netty` is on the
classpath (for Netty's tuning options or an older JDK). The built-in
and Native servers apply Netty's request limits (request line 4 KB, headers 8 KB),
decode chunked and gzip/deflate request bodies, and compress responses.
It also takes `withMaxContentLength`, `withWebSocketMaxFrameSize`, and
`withWebSocketPingIntervalMillis`. For backend-only options, pass that backend's
options (a `ServerOptions`) from the platform's source folder or `Design`:
`withBackend(Netty.options.withHandlerExecutorThreads(8))` (`wvlet.uni.http.netty.Netty`;
`Native.options` on Scala Native); other backends ignore them. Port, handler,
and size limits are set only on `Http.server`. The backends:

| Platform | Backend | Module | Options |
|----------|---------|--------|---------|
| JVM (JDK 24+) | built-in, virtual threads | `uni` | — |
| JVM, `uni-netty` on the classpath | Netty | `uni-netty` | `wvlet.uni.http.netty.Netty.options` |
| Scala.js (Node.js) | Node `http` | `uni` | — |
| Scala Native | POSIX sockets | `uni` | `wvlet.uni.http.Native.options` |

`Http.server` takes `withPort`, `withHost` (default `127.0.0.1`), `withName`,
`withHandler(Request => Response)`, `withRxHandler(Request => Rx[Response])`,
`withFilter`, `withWebSocketRoute`, `start()`, and `start { server => ... }`.
The annotation router (`@Endpoint`, `Router`, `wvlet.uni.http.router.RouterHandler`)
and `RPCHandler` work on every platform; import them from `wvlet.uni.http.router` and
`wvlet.uni.http.rpc` (the `wvlet.uni.http.netty` names are deprecated aliases).
For SSE and WebSocket, see [streaming.md](streaming.md).

For a JVM-only project, `Netty.server` (`wvlet.uni.http.netty.Netty`, returns
`NettyServerConfig`) builds the Netty server directly: shared and Netty-only
settings are methods of one config, and `start()` returns a `NettyHttpServer`.
Use `Http.server` when the code should also build for Scala.js or Native.
`NettyServer` was removed: use `Netty.server` or `Http.server`.

```scala
import wvlet.uni.http.Response
import wvlet.uni.http.netty.Netty

Netty
  .server
  .withPort(0)
  .withHandlerExecutorThreads(8)
  .withHandler(_ => Response.ok("ok"))
  .start { server =>
    println(server.localPort)
  }
```

For a typed API, prefer [RPC](rpc.md) over hand-written REST routes.

Full reference: https://wvlet.org/uni/http/server.md

## REST controller

```scala
import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.Http
import wvlet.uni.http.router.RouterHandler
import wvlet.uni.weaver.Weaver

case class Item(id: String, name: String) derives Weaver
case class NewItem(name: String) derives Weaver

class ItemController:
  @Endpoint(HttpMethod.GET, "/items")
  def list(limit: Int = 10): Seq[Item] = Seq(Item("1", "pen")).take(limit) // ?limit=5

  @Endpoint(HttpMethod.GET, "/items/:id")
  def get(id: String): Item = Item(id, "pen")                              // path param

  @Endpoint(HttpMethod.POST, "/items")
  def create(item: NewItem): Response =                                     // JSON body
    Response.created.withJsonContent(Weaver.toJson(Item("2", item.name)))

  @Endpoint(HttpMethod.GET, "/health")
  def health(): String = "ok"

@main def serve(): Unit =
  Http
    .server
    .withPort(8080)
    .withRxHandler(RouterHandler(Router.of[ItemController]))
    .start { server =>
      server.awaitTermination()
    }
```

- Path params (`:id`) bind by name; other primitive params come from the query
  string; a single non-primitive param takes the whole JSON (or MessagePack)
  body. A `request: Request` param gives the raw request.
- Return types: `Response` as-is, `String` as text, case classes / `Seq` / `Map`
  as JSON, `Option` as value or 204, `Unit` as 204, `Rx[Response]` async.
- `Router.of[A].andThen(Router.of[B])` combines controllers.
- `RouterHandler(router)` creates controllers with their no-arg constructor.
  For controllers with dependencies, pass instances:
  `RouterHandler(router, MapControllerProvider(ItemController(repo)))`
  (`wvlet.uni.http.router.MapControllerProvider`).

## Functional handler (all platforms)

```scala
import wvlet.uni.http.{Http, Request, Response}
import wvlet.uni.rx.Rx

Http
  .server
  .withPort(8080)
  .withRxHandler { request =>
    request.path match
      case "/health"       => Rx.single(Response.ok("ok"))
      case s"/users/${id}" => Rx.single(Response.ok(s"user ${id}"))
      case _               => Rx.single(Response.notFound)
  }
  .start()
```

## Filters

```scala
import wvlet.uni.http.{Response, RxHttpFilter}
import wvlet.uni.rx.Rx

val auth = RxHttpFilter { (request, next) =>
  request.headers.get("Authorization") match
    case Some(_) => next.handle(request)
    case None    => Rx.single(Response.unauthorized)
}

val errors = RxHttpFilter { (request, next) =>
  next.handle(request).recover { case e: IllegalArgumentException =>
    Response.badRequest(e.getMessage)
  }
}

Http.server.withPort(8080).withFilter(errors).withFilter(auth).withRxHandler(handler).start()
```

Filters see exceptions from `RouterHandler` controllers and failed `Rx` values.
In a functional handler, return `Rx.exception(e)` rather than throwing, or the
filter never sees the error. Unrecovered exceptions become a plain
`500 Internal Server Error`, except a thrown
`ServiceStatus.NOT_FOUND_U5.newException("...")` (`wvlet.uni.http`), which
answers with its HTTP status (404), the `X-RPC-Status` header, and the JSON
error body that RPC endpoints send. Enable `.withDetailedErrors(true)` only in
development.

## Static files (JVM, Node.js, Native)

`StaticFiles(dir)` is a filter that answers `GET` for files under `dir` and passes
every other request on, so a built frontend and the API share one server:

```scala
import wvlet.uni.http.StaticFiles

Http
  .server
  .withPort(8080)
  .withFilter(StaticFiles("dist").withSpaFallback(true))
  .withRxHandler(handler)
  .start()
```

- `withSpaFallback(true)` serves `index.html` when the handler answers 404 to a
  `GET` that accepts `text/html`, for client-side routing.
- Safe by default: `..`, encoded separators, symbolic links below the root,
  dotfiles (except `.well-known/`), and directory listings are all 404. Every
  file gets `X-Content-Type-Options: nosniff`.
- Filters run in the order added, so add `StaticFiles` after an auth filter to
  protect the files, or before it to keep them public.
- Each file gets an `ETag` (size and mtime) and `Cache-Control: no-cache`; a
  matching `If-None-Match` gets 304. Change the policy with
  `withCacheControl("max-age=3600")` or drop it with `noCacheControl`.
- Files stream from disk in chunks. A single `Range` gets 206 (`If-Range` must
  match the ETag), one past the end gets 416, and several ranges get the whole file.
- Stream a file from a handler with
  `Response.ok(HttpContent.FileContent(path, offset, length))`.

## CORS (JVM, Node.js, Native)

`CorsFilter` answers CORS preflights and adds `Access-Control-Allow-Origin` to
responses for allowed origins. The default `CorsPolicy` allows no origin:

```scala
import wvlet.uni.http.{CorsFilter, CorsPolicy}

Http
  .server
  .withPort(8080)
  .withFilter(CorsFilter(CorsPolicy().withAllowedOrigins("https://app.example.com")))
  .withRxHandler(handler)
  .start()
```

- Add it as a server filter (`withFilter`), so it also answers preflights for
  paths with no route.
- `withOriginFilter(f)` takes a predicate for patterns; anchor regexes at `$`.
- `withAllowedOrigins("*")` with `withAllowCredentials(true)` throws.
- A disallowed preflight gets 403; other requests from a disallowed origin are
  handled without CORS headers. A handler failure from an allowed origin becomes a
  500 with CORS headers (body always `Internal Server Error`; a `ServiceException`
  keeps its status). Add error filters
  that map exceptions to statuses after `CorsFilter`, so they run inside it.

## Tracing (all platforms)

`TelemetryFilter` traces each request as a server span: an RPC call is named
`Service/method`, an `@Endpoint` call `GET /route/:template`. A client with
`withTelemetry` traces its calls and sends W3C `traceparent`, so calls made inside
a traced request join its trace. `withOtlp` sends spans and
`http.server/client.request.duration` histograms as OTLP/HTTP JSON, e.g. to the
Datadog Agent's OTLP receiver. A `Telemetry` without an exporter is disabled:

```scala
import wvlet.uni.http.TelemetryFilter
import wvlet.uni.telemetry.Telemetry
import wvlet.uni.telemetry.otlp.OtlpConfig

val telemetry = Telemetry()
  .withServiceName("orders")
  .withOtlp(OtlpConfig().withEndpoint("http://localhost:4318"))

Http.server.withPort(8080).withFilter(TelemetryFilter(telemetry)).withRxHandler(handler)
val tracedClient = Http.client.withTelemetry(telemetry).newSyncClient
```

- Add it as the outermost filter. Query strings, URL user info, and RPC
  arguments are never recorded.
- `Telemetry.currentSpan` is set while the handler's synchronous part runs;
  pass a span to `Rx` callbacks with `Telemetry.withSpan(span) { ... }`.
- `telemetry.trace("name") { span => ... }` wraps your own code in a span.
- `MCP.server.withTelemetry(telemetry)` and `MCP.client.withTelemetry(telemetry)` trace MCP
  messages as `tools/call <tool>` spans, on stdio too, joined through `params._meta.traceparent`.
  LLM clients trace when their HTTP client has telemetry (see [llm.md](llm.md#tracing)).
- At shutdown, `telemetry.shutdown().await` (JVM, Native) sends what is buffered;
  `close()` starts it without waiting.

## Lifecycle

`start()` returns an `HttpServer`: `localPort`, `localAddress`, `isRunning`,
`stop()`, `awaitTermination()`. `withPort(0)` picks a free port (useful in tests).
The block form `start { server => ... }` stops the server when the block returns.
