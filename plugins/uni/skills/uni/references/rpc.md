# Typed RPC

Define the API as a Scala trait, serve it with `RPCRouter`, and call it with a
client generated from the same trait. Arguments and results go through Weaver,
so case classes, `Option`, `Seq`, and `Map` work without codecs.

Full reference: https://wvlet.org/uni/http/rpc.md

## 1. Service trait (shared module)

Keep the trait and its case classes in a module both server and client depend
on. Each public method becomes `POST /<package>.<TraitName>/<methodName>` (e.g.
`/example.api.UserService/getUser`) with a JSON body
`{"request": {<param>: <value>, ...}}`.

```scala
package example.api

import wvlet.uni.rx.Rx

case class User(id: Long, name: String)

trait UserService:
  def getUser(id: Long): User
  def createUser(name: String): User
  def listUsers(limit: Int = 10): Seq[User] // default values keep old clients working
  def findUserAsync(id: Long): Rx[User]     // Rx[T] for non-blocking handlers
```

Rules:
- No overloaded method names; `RPCRouter.of` rejects them.
- Pass the trait as the type argument: `RPCRouter.of[UserService](impl)`. It sets
  the route prefix (`/<trait FQCN>/<method>`); an inferred impl type gives other
  paths than `RPCClient.of[UserService]` calls, so every call returns 404.
- Annotate in-process helpers with `@rpcIgnore` (`wvlet.uni.http.rpc.rpcIgnore`)
  to keep them out of the endpoints.
- Methods inherited from `LogSupport`, `scala.*`, or `java.*` are never exposed.

## 2. Implementation and errors

Throw `RPCException` via an `RPCStatus` code. The client receives the same status.

```scala
import wvlet.uni.http.rpc.RPCStatus
import wvlet.uni.rx.Rx

class UserServiceImpl extends UserService:
  private var users = Map(1L -> User(1L, "alice"))

  def getUser(id: Long): User =
    users.getOrElse(id, throw RPCStatus.NOT_FOUND_U5.newException(s"user ${id} not found"))

  def createUser(name: String): User =
    if name.isEmpty then throw RPCStatus.INVALID_ARGUMENT_U2.newException("name is empty")
    val u = User(users.size + 1L, name)
    users += u.id -> u
    u

  def listUsers(limit: Int): Seq[User] = users.values.take(limit).toSeq
  def findUserAsync(id: Long): Rx[User] = Rx.single(getUser(id))
```

Common codes: `INVALID_REQUEST_U1` (400), `INVALID_ARGUMENT_U2` (400),
`NOT_FOUND_U5` (404), `ALREADY_EXISTS_U6` (409), `UNAUTHENTICATED_U13` (401),
`PERMISSION_DENIED_U14` (403), `INTERNAL_ERROR_I0` (500), `UNAVAILABLE_I2` (503),
`EXCEEDED_RATE_LIMIT_R2` (429). Error bodies carry no stack trace unless the
dispatcher uses `.withDetailedErrors(true)` (development only; auth errors and
`.noStackTrace` exceptions still omit it). Other exceptions become
`INTERNAL_ERROR_I0` with no message.

## 3. Serve it

JVM (`uni-netty`):

```scala
import wvlet.uni.http.Http
import wvlet.uni.http.netty.RPCHandler
import wvlet.uni.http.rpc.RPCRouter

@main def server(): Unit =
  val router = RPCRouter.of[UserService](UserServiceImpl())
  Http
    .server
    .withPort(8080)
    .withRxHandler(RPCHandler(router))
    .start { server =>
      server.awaitTermination()
    }
```

For a server that runs on the JVM, Scala.js, and Scala Native with the same
code, pass the cross-platform dispatcher to `Http.server` instead of `RPCHandler`:

```scala
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}

val dispatcher = RPCDispatcher(RPCRouter.of[UserService](UserServiceImpl()))
Http.server.withPort(8080).withRxHandler(dispatcher.dispatch).start()
```

## 4. Generated client (sbt-uni)

```scala sbt
// project/plugins.sbt — same version as the uni library
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")

// build.sbt
lazy val api = project
  .settings(libraryDependencies += "org.wvlet.uni" %% "uni" % "<version>")

lazy val app = project
  .enablePlugins(UniPlugin)
  .dependsOn(api)
  .settings(
    // "<trait FQN>:<sync|async|both|rpc>[:<target package>]"; rpc = sync
    uniHttpClients := Seq("example.api.UserService:both:example.client")
  )
```

`compile` generates `object UserServiceClient` with `SyncClient` (methods
return the declared types) and `AsyncClient` (methods return `Rx[...]`):

```scala
import wvlet.uni.http.Http
import example.client.UserServiceClient

val client = UserServiceClient.SyncClient(
  Http.client.withBaseUri("http://localhost:8080").newSyncClient
)
val user: User = client.getUser(1)
```

A failed call throws the HTTP client's exception, not `RPCException`: a 4xx
status throws `HttpException`, and a 5xx status (retried first) throws
`HttpMaxRetryException`. Both carry the response; restore the server's
`RPCException` from it:

```scala
import wvlet.uni.http.HttpException
import wvlet.uni.http.rpc.{RPCException, RPCStatus}

try client.getUser(99)
catch
  case e: HttpException if e.response.isDefined =>
    val rpcError = RPCException.fromResponse(e.response.get)
    rpcError.status shouldBe RPCStatus.NOT_FOUND_U5 // or match on it
```

## Without codegen

In a single-module project, write the client by hand with `RPCClient`, the same
runtime the generated client calls. `newSyncClient(http)` (or `newAsyncClient`) binds the
HTTP client once, and `call` takes the method name and its arguments in order:

```scala
import wvlet.uni.http.{Http, HttpSyncClient}
import wvlet.uni.http.rpc.RPCClient

class UserServiceClient(http: HttpSyncClient) extends UserService:
  private val rpc = RPCClient.of[UserService].newSyncClient(http)
  def getUser(id: Long): User           = rpc.call("getUser", id)
  def createUser(name: String): User    = rpc.call("createUser", name)
  def listUsers(limit: Int): Seq[User]  = rpc.call("listUsers", limit)
  def findUserAsync(id: Long): Rx[User] = Rx.single(getUser(id))
```
