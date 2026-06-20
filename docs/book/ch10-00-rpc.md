# 10. Typed RPC

In Chapter 9 both ends of the call wrote out the contract by hand: the
server picked the path `/users/:id` and shaped the JSON; the client typed
the same path and parsed the same JSON back. Nothing connects those two
copies. Rename a field on the server and the client keeps compiling — and
breaks at runtime, in production, against the one response you didn't
test.

RPC closes that gap. You write the contract *once*, as a Scala trait, and
both sides are checked against it by the compiler.

## Define the contract once

The API is an ordinary trait. The server implements it; the client will
call it.

```scala
case class User(id: Long, name: String, email: String) derives Weaver

trait UserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User

class UserServiceImpl extends UserService:
  def getUser(id: Long): User =
    User(id, "Alice", "alice@example.com")
  def createUser(name: String, email: String): User =
    User(1L, name, email)
```

There is no annotation on the methods, no per-endpoint glue. `getUser` is
a normal method; RPC will turn it into a network call without you
describing the wire shape.

## Serve it

`RPCRouter.of[UserService]` reads the trait at compile time and builds
the routes; `RPCHandler` turns that router into something a server can
run:

```scala
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.http.netty.{NettyServer, RPCHandler}

val router = RPCRouter.of[UserService](UserServiceImpl())

NettyServer
  .withPort(8080)
  .withRxHandler(RPCHandler(router))
  .start { _ => /* serving */ }
```

Each method becomes a `POST /UserService/{methodName}` endpoint. You
never chose those paths — they fall out of the trait, which is the point:
there is no path for the client and server to disagree about.

## Call it like a local method

On the client side, the `sbt-uni` plugin generates a typed client from
the *same* trait (see [client generation](/http/rpc#generating-a-client)
for the build setup). Calling a remote method then looks like calling a
local one:

```scala
import wvlet.uni.http.Http
import com.example.client.UserServiceClient

val client = UserServiceClient.SyncClient(
  Http.client.withBaseUri("http://localhost:8080").newSyncClient
)

val user: User = client.getUser(42)   // a network round-trip, fully typed
```

`client.getUser(42)` is checked by the compiler against `UserService`.
Pass a `String` where the trait wants a `Long`, or rename `getUser` on
the server, and the *client* stops compiling — the failure moves from a
2 a.m. production page to your build. That is the entire value
proposition: the contract is one artifact, and a violation is a compile
error.

## What crosses the wire

Arguments and return values are serialized with [Weaver](./ch06-00-data),
the same derivation you met in Chapter 6 — which is why `User` has
`derives Weaver`. Over the wire RPC uses MessagePack by default: it is
compact and fast, and since both ends were generated from one trait,
there is no human reading the bytes who would prefer JSON. The encoding
is an internal detail you mostly never see.

Errors cross the boundary as data too. When the server throws, the client
sees an `RPCException` carrying a structured `RPCStatus` — a typed code
like `NOT_FOUND` or `INVALID_ARGUMENT` — rather than a bare HTTP number
you have to interpret. The [RPC reference](/http/rpc#rpc-status-codes)
lists the status categories.

## When RPC, when REST

RPC is the right tool when **you own both ends** — two of your own
services, or your frontend and backend, evolving together. The shared
trait is a feature: it keeps them in lockstep.

It is the *wrong* tool when the other end is not yours to recompile. A
public API consumed by third parties, a webhook, anything that must speak
a stable, language-neutral contract — that wants plain
[HTTP/REST](./ch09-00-http), where the wire format *is* the interface and
no client needs your trait. Use RPC inside your system; use REST at its
public edge.

## What you have, what comes next

You can now connect two services without hand-written glue:

- **A trait is the contract** — implemented on the server, generated into
  a client, checked by the compiler on both sides.
- **`RPCRouter.of[T](impl)` + `RPCHandler`** serves it;
  **`sbt-uni`** generates the typed client.
- Payloads ride **Weaver/MessagePack**; errors arrive as typed
  **`RPCStatus`**.
- Reach for RPC when you own both ends, REST at the public edge.

That closes Part V. Next, [Part VI](./ch11-00-cross-platform) steps back
to the property that has been quietly true this whole time: nearly
everything you've written runs on three different runtimes from one
codebase.

[← 9. HTTP Clients and Servers](./ch09-00-http) | [Next → 11. One Codebase, Three Runtimes](./ch11-00-cross-platform)
