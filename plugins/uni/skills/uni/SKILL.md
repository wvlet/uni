---
name: uni
description: Write idiomatic Scala 3 code with Uni (org.wvlet.uni, packages wvlet.uni.*), the Airframe successor for dependency injection (Design), logging (LogSupport), JSON/MessagePack codecs (Weaver), reactive streams (Rx), HTTP client/server, typed RPC, MCP servers, retry, and unit testing (UniTest) on JVM, Scala.js, and Scala Native. Use when a Scala 3 project's build.sbt mentions org.wvlet.uni or its code imports wvlet.uni, when the user asks for DI, logging, JSON, HTTP, RPC, MCP, or tests in Scala 3 with few dependencies, or when migrating from Airframe (wvlet.airframe, wvlet.log, AirSpec).
---

# Uni

Uni is one Scala 3 library (`org.wvlet.uni`) for DI, logging, JSON/MessagePack,
Rx, HTTP, RPC, MCP servers, and testing, with the same API on JVM, Scala.js, and
Scala Native. It replaces Airframe; the package root is `wvlet.uni.*`.

## sbt setup

```scala
// build.sbt — use the latest version from
// https://central.sonatype.com/artifact/org.wvlet.uni/uni_3 (or the one already in the build)
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"      % "<version>",
  "org.wvlet.uni" %% "uni-test" % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// JVM-only Netty HTTP/RPC server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "<version>"
```

On sbt 2.x use `%%` for all platforms. On sbt 1.x use `%%%` for Scala.js and
Scala Native.

## Canonical imports

| Task | Import | Entry point |
|------|--------|-------------|
| Dependency injection | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap` |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (JVM, `uni-netty`) | `wvlet.uni.http.netty.{NettyServer, RouterHandler}` | [references/http-server.md](references/http-server.md) |
| Typed RPC | `wvlet.uni.http.rpc.RPCRouter`, `wvlet.uni.http.netty.RPCHandler` | [references/rpc.md](references/rpc.md) |
| MCP server (stdio) | `wvlet.uni.mcp.{MCPServer, description}` | `MCPServer().withName(n).withTools[T](impl).serveStdio()` |
| Retry | `wvlet.uni.control.Retry` | `Retry.withBackOff().run { ... }` |
| Unit tests | `wvlet.uni.test.*` | `class T extends UniTest: test("...") { x shouldBe y }` |

## Mistakes to avoid

- **Scala 2 syntax.** Uni is Scala 3 only: indentation syntax, `given`/`using`,
  `enum`, `derives`. Omit `new` for classes (`StringBuilder()`).
- **ScalaTest matchers in UniTest.** Write `(x >= 1) shouldBe true`, not
  `x should be >= 1`. For type checks, `v shouldMatch { case _: T => }` instead of
  `asInstanceOf`. Use `shouldNotBe`, `shouldContain`, `intercept[E] { ... }`.
- **Importing only `UniTest`.** Use `import wvlet.uni.test.*`; `shouldBe defined`
  and `shouldBe empty` need the matcher objects from that package.
- **Checking `isSuccessful` after `send`.** The HTTP client throws on non-2xx:
  `HttpException` for most 4xx, `HttpMaxRetryException` (not a subclass) after
  retrying 5xx/429. RPC client calls fail the same way.
- **Hand-written JSON codecs.** Add `derives Weaver` to the case class.
- **A second DI framework, logging backend, or JSON library next to Uni.** Uni
  already provides them.
- **Airframe imports.** `wvlet.airframe.*`, `wvlet.log.*`, and `wvlet.airspec.*`
  are the old library. Map them with [references/airframe-migration.md](references/airframe-migration.md).
- **Constructing services by hand.** Take dependencies as constructor parameters
  and let `Design` build them; bind interfaces with `bindImpl[A, B]` and values with
  `bindInstance[A](v)`.

## References

Read the reference for the task before writing code:

| Task | Reference |
|------|-----------|
| DI, logging, JSON, retry, MCP server | [references/core.md](references/core.md) |
| Typed RPC service, server, and generated client | [references/rpc.md](references/rpc.md) |
| HTTP client | [references/http-client.md](references/http-client.md) |
| HTTP server, REST controllers, filters | [references/http-server.md](references/http-server.md) |
| Unit, HTTP, RPC, and property tests | [references/testing.md](references/testing.md) |
| Migrating from Airframe | [references/airframe-migration.md](references/airframe-migration.md) |

- Full documentation in one Markdown file: https://wvlet.org/uni/llms-full.txt
- Page index: https://wvlet.org/uni/llms.txt (any page is also available as `<page>.md`)
