# REST Server

uni provides a Netty-based HTTP server with a type-safe router framework for building REST APIs.

## Quick Start

```scala
import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.netty.{NettyServer, RouterHandler}

// Define a controller
class UserController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = """["alice", "bob"]"""

  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): String = s"""{"id": "${id}"}"""

// Create router and start server
val router = Router.of[UserController]
val handler = RouterHandler(router)

NettyServer
  .withPort(8080)
  .withRxHandler(handler)
  .start { server =>
    println(s"Server running on port ${server.localPort}")
    // Server runs until the block completes
  }
```

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
      Rx.single(Response.unauthorized("Missing or invalid token"))
}
```

### Applying Filters

```scala
// Apply filter to server
NettyServer
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

## Server Configuration

### NettyServerConfig Options

```scala
NettyServer
  .withPort(8080)                      // Port to listen on (0 for random)
  .withHost("0.0.0.0")                 // Bind address
  .withName("my-server")               // Server name for logging
  .withMaxContentLength(1024 * 1024)   // Max request body size (1MB)
  .withMaxHeaderSize(8192)             // Max header size
  .withMaxInitialLineLength(4096)      // Max request line length
  .noNativeTransport                   // Disable native transport (epoll/kqueue)
  .withHandler(handler)
  .start()
```

### Server Lifecycle

```scala
// Start and get server instance
val server = NettyServer
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
NettyServer
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
NettyServer
  .withPort(8080)
  .withHandler { request =>
    Response.ok(s"Hello from ${request.path}")
  }
  .start()
```

Or with async responses:

```scala
NettyServer
  .withPort(8080)
  .withRxHandler { request =>
    Rx.single(Response.ok("async response"))
  }
  .start()
```

## WebSocket

The Netty server supports WebSocket connections. WebSocket routes are registered by path,
separately from the REST router, because connections are stateful and bidirectional rather than
request/response. A fresh `WebSocketHandler` is created per connection, so it may hold
per-connection state; all of its callbacks have no-op defaults, so you override only what you need.

```scala
import wvlet.uni.http.{WebSocketContext, WebSocketHandler}
import wvlet.uni.http.netty.NettyServer

NettyServer
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

### Gating the Upgrade With a Filter

Pass an `RxHttpFilter` to authenticate or otherwise gate the upgrade handshake. A 2xx response
allows the upgrade; a non-2xx response (or an empty `Rx`) rejects it. Attributes the filter adds to
the request are visible via `ctx.request`:

```scala
import wvlet.uni.http.{Response, RxHttpFilter, WebSocketContext, WebSocketHandler}
import wvlet.uni.http.netty.NettyServer
import wvlet.uni.rx.Rx

val authFilter = RxHttpFilter { (request, next) =>
  request.header("Authorization") match
    case Some(token) if isValid(token) =>
      next.handle(request.addHeader("X-User", userOf(token)))
    case _ =>
      Rx.single(Response.unauthorized)
}

NettyServer
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
NettyServer
  .withWebSocketMaxFrameSize(4 * 1024 * 1024)  // 4 MB
  .withWebSocketRoute("/ws") { _ => new WebSocketHandler {} }
  .start()
```

WebSocket support is server-side and JVM (Netty) only. WebSocket routes coexist with REST endpoints
on the same server and port.

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
import wvlet.uni.http.netty.{NettyServer, RouterHandler}
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

NettyServer
  .withPort(8080)
  .withFilter(loggingFilter)
  .withRxHandler(handler)
  .start { server =>
    println(s"API running at http://localhost:${server.localPort}")
    Thread.currentThread().join()  // Run indefinitely
  }
```
