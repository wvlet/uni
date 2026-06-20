# 9. HTTP Clients and Servers

Chapter 2 made a single HTTP call to get a CLI tool working. This chapter
takes HTTP seriously from both sides: the **client** that calls out, and
the **server** that answers. The two share a request/response model, so
once you know one, the other reads naturally.

## The client, synchronous first

The simplest client blocks until the response arrives:

```scala
import wvlet.uni.http.{Http, Request}

val client = Http.client.newSyncClient

val response = client.send(Request.get("https://httpbin.org/get"))
println(response.status)                       // 200 OK
println(response.contentAsString.getOrElse("")) // the body
```

`Request.get(url)` builds a request; `client.send` runs it and returns an
`HttpResponse`. `response.status` is a typed `HttpStatus`, and
`response.contentAsString` is an `Option[String]` because some responses
have no body. You build other verbs the same way — `Request.post(url)`,
then `.withJsonContent("""{"name":"Alice"}""")` to attach a body.

Start with the sync client. It is the easy thing to reason about: one
call, one result, top to bottom. Reach for async only when you need
concurrency.

## The client, asynchronously

The async client returns the value-over-time version of the response — an
`Rx[HttpResponse]`, exactly the shape from [Chapter 7](./ch07-00-rx):

```scala
import wvlet.uni.http.{Http, Request}

val client = Http.client.newAsyncClient

client
  .send(Request.get("https://httpbin.org/get"))
  .map(_.contentAsString.getOrElse(""))
  .subscribe(println)
```

Because the result is an `Rx`, you compose it with `map` and `subscribe`
instead of blocking. The same operators you learned for streams work
here — async HTTP is not a separate world.

Both clients come configured with sensible defaults, including the retry
policy from [Chapter 8](./ch08-00-control). Tune them with `withXxx`
setters before creating the client:

```scala
val client = Http.client
  .withConnectTimeoutMillis(5000)
  .withMaxRetry(3)
  .newSyncClient
```

## The server

A server answers requests. At its simplest, a handler is a function from
`Request` to a response — and that form runs on **every** platform:

```scala
import wvlet.uni.http.{Request, Response}
import wvlet.uni.http.netty.NettyServer
import wvlet.uni.rx.Rx

NettyServer
  .withPort(8080)
  .withRxHandler { (request: Request) =>
    Rx.single(Response.ok(s"You asked for ${request.path}"))
  }
  .start { server =>
    println(s"listening on ${server.localPort}")
  }
```

`start { server => … }` runs the server for the duration of the block and
shuts it down cleanly when the block ends — the same lifecycle shape as
`Design.build`. On Scala.js and Native you swap `NettyServer` for
`NodeServer` or `NativeServer`; the builder API is identical.

## Routing without a switch statement

A real API has many endpoints, and you don't want one giant `match` on
`request.path`. On the JVM, annotate the methods of a controller and let
the router dispatch:

```scala
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.http.netty.{NettyServer, RouterHandler}

class UserController:
  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): String = s"""{"id":"${id}"}"""

  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): Seq[String] = Seq("alice", "bob")

val router = Router.of[UserController]

NettyServer
  .withPort(8080)
  .withRxHandler(RouterHandler(router))
  .start { _ => /* serve */ }
```

`Router.of[UserController]` reads the annotations at compile time and
builds the route table; path parameters like `:id` bind to method
parameters by name, and a returned `Seq` or case class is serialized to
JSON for you (via the [Weaver](./ch06-00-data) machinery from Chapter 6).
The [server reference](/http/server) covers query parameters, filters,
and combining controllers.

::: tip JVM-only routing
The annotation router (`@Endpoint`, `Router.of`, `RouterHandler`) is a
JVM/Netty feature. On Scala.js / Native, route inside a functional
`withRxHandler` by matching on `request.path`. The server *itself* runs
on all three platforms; only the annotation-based routing is JVM-only.
:::

## One client, three platforms

The client code at the top of this chapter compiles and runs unchanged on
the JVM, in the browser and Node.js (Scala.js), and as a native binary —
because `Http.client` resolves to the right transport for each runtime
(`java.net.http`, the Fetch API, or libcurl). You write the request once.
The one constraint worth knowing: browsers have no synchronous HTTP, so
in browser code use `newAsyncClient`. [Chapter 11](./ch11-00-cross-platform)
is about this property in general; the [client reference](/http/client)
has the per-platform backend table.

For responses too large to hold in memory, or for a server pushing
updates to a client, see [streaming and server-sent events](/http/sse).

## What you have, what comes next

You can now build both ends of an HTTP exchange:

- **`Http.client.newSyncClient`** for blocking calls,
  **`newAsyncClient`** for `Rx[HttpResponse]`.
- A **server** from a functional handler (every platform) or an
  **annotation router** (`@Endpoint` + `Router.of`, JVM).
- The same client source runs on JVM, Scala.js, and Native.

Next, [Chapter 10](./ch10-00-rpc) removes the hand-written URLs and JSON
entirely: define a trait once and get a typed client and server that
speak it.

[← 8. Living With Failure](./ch08-00-control) | [Next → 10. Typed RPC](./ch10-00-rpc)
