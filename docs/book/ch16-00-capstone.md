# 16. Capstone — A Bookmarks Service

You've met every piece of Uni on its own. This chapter sets the
platform-specific side-tracks aside and builds one small, complete thing
with the core stack — a service that saves and lists bookmarks — so you
can see how the pieces fit into a single program.

By the end you'll have a typed RPC service, wired with Design, hardened
with a retry, and tested without a network. Nothing here is new; the
point is the *assembly*.

## The data and the contract

Start with the data — a `Bookmark` — and the contract clients call, a
trait. Both `derive Weaver` so they cross the wire as [Chapter
6](./ch06-00-data) showed:

```scala
import wvlet.uni.weaver.Weaver

case class Bookmark(id: Long, url: String, title: String) derives Weaver

trait BookmarkService:
  def add(url: String, title: String): Bookmark
  def list(): Seq[Bookmark]
```

The trait is the whole API. Server and client are both checked against
it, so they cannot drift ([Chapter 10](./ch10-00-rpc)).

## The implementation

The implementation keeps an in-memory store. It takes nothing it doesn't
need in its constructor — here, just a starting `Seq` of seed bookmarks,
which makes it trivial to construct in a test:

```scala
import java.util.concurrent.atomic.AtomicReference

class BookmarkServiceImpl(seed: Seq[Bookmark]) extends BookmarkService:
  private val store = AtomicReference(seed.toVector)
  private val nextId = java.util.concurrent.atomic.AtomicLong(seed.size + 1L)

  def add(url: String, title: String): Bookmark =
    val bookmark = Bookmark(nextId.getAndIncrement(), url, title)
    store.updateAndGet(_ :+ bookmark)
    bookmark

  def list(): Seq[Bookmark] = store.get()
```

## Wiring it with Design

`Design` ([Chapter 3](./ch03-00-design)) decides how the service is
built. Binding the seed as an instance and the service as a singleton is
the entire dependency graph:

```scala
import wvlet.uni.design.Design

val appDesign = Design.newDesign
  .bindInstance[Seq[Bookmark]](Seq.empty)
  .bindSingleton[BookmarkServiceImpl]
```

That `appDesign` is the one artifact production and tests share. The test
later swaps just the seed; everything else stays real.

## Serving it over RPC

Turn the implementation into routes with `RPCRouter.of`, wrap it in an
`RPCHandler`, and serve it on Netty ([Chapter 9](./ch09-00-http),
[Chapter 10](./ch10-00-rpc)). The server runs inside the Design session,
so it is built and torn down with everything else:

```scala
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.http.netty.{NettyServer, RPCHandler}

appDesign.build[BookmarkServiceImpl] { service =>
  val router = RPCRouter.of[BookmarkService](service)
  NettyServer
    .withPort(8080)
    .withRxHandler(RPCHandler(router))
    .start { server =>
      println(s"Bookmarks service on ${server.localAddress}")
      server.awaitTermination()
    }
}
```

A generated `BookmarkServiceClient` (set up as in
[client generation](/http/rpc#generating-a-client)) then calls
`add`/`list` as if they were local methods.

## Hardening one call

Suppose `add` should fetch the page title when the caller omits it. That's
a network call, and network calls fail — so wrap it in a retry
([Chapter 8](./ch08-00-control)), and only for this idempotent GET:

```scala
import wvlet.uni.control.Retry
import wvlet.uni.http.{Http, Request}

class TitleFetcher(client: wvlet.uni.http.HttpSyncClient):
  def titleOf(url: String): String =
    Retry.withBackOff(maxRetry = 3).run {
      val html = client.send(Request.get(url)).contentAsString.getOrElse("")
      extractTitle(html).getOrElse(url)
    }

  private def extractTitle(html: String): Option[String] =
    val open = html.indexOf("<title>")
    val close = html.indexOf("</title>")
    if open >= 0 && close > open then Some(html.substring(open + 7, close)) else None
```

`TitleFetcher` takes its `HttpSyncClient` as a constructor parameter, so
Design owns the client's lifecycle (`bindInstance(...).onShutdown(_.close())`,
as in [Chapter 3](./ch03-00-design)) and a test can hand it a stand-in.

## Testing it, without a network

Because the wiring is a value, the test takes `appDesign` and overrides
exactly one binding — the seed — leaving the real `BookmarkServiceImpl`
in place ([Chapter 4](./ch04-00-testing)):

```scala
import wvlet.uni.design.Design
import wvlet.uni.test.UniTest

class BookmarkServiceTest extends UniTest:
  test("add appends and list returns everything") {
    val seeded = appDesign +
      Design.newDesign.bindInstance[Seq[Bookmark]](
        Seq(Bookmark(1, "https://wvlet.org", "Wvlet"))
      )

    seeded.build[BookmarkServiceImpl] { service =>
      service.list().size shouldBe 1
      val added = service.add("https://scala-lang.org", "Scala")
      service.list().map(_.title) shouldContain "Scala"
      added.id shouldBe 2
    }
  }
```

The real `add`/`list` logic runs; only the seed is a fixture. No server,
no socket — the suite finishes in milliseconds.

## What you built

In one chapter you assembled the whole core stack into a working service:

- **Data + contract** as `derives Weaver` case classes and a trait.
- **Design** for the dependency graph, shared by production and tests.
- **RPC + Netty** to serve the trait, with a generated typed client.
- **Retry** around the one call that touches the network.
- **UniTest + Design override** to test the real logic without a network.

That is the shape of a Uni application — *parse, wire, do, clean up* from
[Chapter 2](./ch02-00-cli-app), grown up. From here, the platform Parts
([VII](./ch12-00-npm-facades), [VIII](./ch14-00-calling-c)) take the same
code to the browser and to native binaries, and the
[appendices](./appendix-a-scala3) collect the supporting material.

[← 15. Exposing Scala Native to C and Rust](./ch15-00-exposing-native) | [Next → Appendix A: Scala 3 Syntax Notes](./appendix-a-scala3)
