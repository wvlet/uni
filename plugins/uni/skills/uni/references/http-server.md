# HTTP server

`Http.server` (`wvlet.uni.http.Http`) runs the same server code on every
platform; on the JVM it needs `uni-netty` on the classpath. Use a platform's own
entry point for backend-specific settings:

| Platform | Server | Module |
|----------|--------|--------|
| JVM | `wvlet.uni.http.netty.NettyServer` | `uni-netty` |
| Scala.js (Node.js) | `wvlet.uni.http.NodeServer` | `uni` |
| Scala Native | `wvlet.uni.http.NativeServer` | `uni` |

`Http.server` and all three share `withPort`, `withHost` (default `127.0.0.1`), `withName`,
`withHandler(Request => Response)`, `withRxHandler(Request => Rx[Response])`,
`withFilter`, `withWebSocketRoute`, `start()`, and `start { server => ... }`.
The annotation router (`@Endpoint`, `Router`, `RouterHandler`) is **JVM only**.
For SSE and WebSocket, see [streaming.md](streaming.md).
For a typed API, prefer [RPC](rpc.md) over hand-written REST routes.

Full reference: https://wvlet.org/uni/http/server.md

## REST controller (JVM)

```scala
import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.netty.{NettyServer, RouterHandler}
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
  NettyServer
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

NettyServer.withPort(8080).withFilter(errors).withFilter(auth).withRxHandler(handler).start()
```

Filters see exceptions from `RouterHandler` controllers and failed `Rx` values.
In a functional handler, return `Rx.exception(e)` rather than throwing, or the
filter never sees the error. Unrecovered exceptions become a plain
`500 Internal Server Error`; enable `.withDetailedErrors(true)` only in
development.

## Lifecycle

`start()` returns an `HttpServer`: `localPort`, `localAddress`, `isRunning`,
`stop()`, `awaitTermination()`. `withPort(0)` picks a free port (useful in tests).
The block form `start { server => ... }` stops the server when the block returns.
