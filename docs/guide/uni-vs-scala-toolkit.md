---
description: Uni vs the Scala Toolkit — when to choose the official Scala starter kit (sttp, uPickle, os-lib, MUnit) and when to choose Uni (object wiring, logging, HTTP, RPC, MCP servers, and testing on JVM, Scala.js, and Scala Native), with a feature table and side-by-side code.
---

# Uni vs the Scala Toolkit

**The [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html) is
the official starter bundle of libraries for scripts, teaching, and everyday tasks.
It includes sttp for HTTP requests, uPickle for JSON, os-lib for files and processes,
and MUnit for tests, and you add it with one line, `//> using toolkit latest`.
Uni is a single Scala 3 library for building applications and services. On top of
JSON, HTTP, files, and tests, it adds object wiring (Design), logging, an HTTP
server, typed RPC, MCP servers, Rx, and CLI tools, with one API on the JVM,
Scala.js, and Scala Native.** In short, the Toolkit is the easiest start for
scripts, and Uni covers the whole application.

The two can live in the same project. See [Using them together](#using-them-together).

## Feature by feature

Toolkit facts are from version 0.9.2 (April 2026) and its published POMs on
Maven Central. Uni facts are from the current source.

| Need | Scala Toolkit 0.9.2 | Uni |
|------|---------------------|-----|
| Object wiring and lifecycle | Not included | [Design](../core/design): constructor wiring, singletons, `onStart`/`onShutdown` hooks |
| Logging | Not included | [`LogSupport`](../core/logging): `info`/`debug`/`warn` with source locations |
| JSON / MessagePack | [uPickle](https://com-lihaoyi.github.io/upickle/): `derives ReadWriter`, JSON and MessagePack | [Weaver](../core/weaver): `derives Weaver`, JSON and MessagePack |
| HTTP client | [sttp client4](https://sttp.softwaremill.com/): JVM, Scala.js, Native | [`Http.client`](../http/client): sync and async, retries on by default; JVM, Scala.js (sync on Node.js only), Native (libcurl) |
| HTTP server | Not in the artifact; the Toolkit tutorials add [Cask](https://docs.scala-lang.org/toolkit/web-server-intro.html) separately, JVM only | [REST Server](../http/server): Netty on the JVM (`uni-netty`), Node.js on Scala.js, and a native server on Scala Native. Request handlers work on all three; the annotation router (`@Endpoint`) is JVM-only |
| RPC | Not included | [Typed RPC](../http/rpc) from a Scala trait (`RPCRouter`, `RPCClient`) |
| MCP servers | Not included | [`uni.mcp`](../mcp/): a stdio MCP server on all three platforms |
| Reactive streams | Not included | [Rx](../rx/): `Rx.variable`, `Rx.single`, `map`/`flatMap` |
| CLI | No argument parser; os-lib runs subprocesses | [Command Launcher](../cli/launcher) for arguments and sub-commands, plus terminal colors, progress bars, and spinners. No subprocess API |
| File I/O | [os-lib](https://github.com/com-lihaoyi/os-lib): JVM and Native; **not in the Scala.js artifact** | [`IO`](../core/filesystem): JVM, Scala.js (Node.js; async APIs in browsers), Native |
| Testing | [MUnit](https://scalameta.org/munit/) via `toolkit-test`: `assertEquals` | [UniTest](../core/unitest): `shouldBe`, `shouldMatch`, property checks |
| Scala versions | Scala 3 (built on 3.3 LTS) and Scala 2.13 | Scala 3.9 or later only |
| Platforms | JVM, Scala.js, Scala Native 0.5 | JVM, Scala.js, Scala Native |
| Install | `//> using toolkit latest`, or `"org.scala-lang" %% "toolkit" % "0.9.2"` plus `toolkit-test` | `"org.wvlet.uni" %% "uni" % "__UNI_VERSION__"` plus `uni-test` ([Installation](./installation)) |

## Choose the Scala Toolkit when…

- **You write a script, a notebook, or course material.** The Toolkit is the
  official answer, its tutorials sit on docs.scala-lang.org, and
  `//> using toolkit latest` is the shortest setup in Scala.
- **You want widely used libraries.** sttp, uPickle, os-lib, and MUnit each have
  a large user base, many Stack Overflow answers, and broad coverage in AI training
  data. Uni is newer and has a smaller community.
- **You need Scala 2.13 or a Scala 3 version before 3.9.** Uni requires Scala 3.9.
- **You run shell commands.** os-lib's subprocess API has no Uni equivalent.
- **You only need a few of these features.** Adding one Toolkit library, such as
  uPickle, is a smaller change than adopting a new library's conventions.

## Choose Uni when…

- **You build an application or service, not a script.** Uni covers the parts
  the Toolkit leaves out: object wiring with lifecycle hooks, logging, an HTTP
  server, typed RPC, and Rx, all following one set of conventions.
- **You build tools for AI agents.** [`uni.mcp`](../mcp/) turns a Scala trait
  into an MCP server. Compiled with Scala Native, it becomes a single binary
  that starts instantly.
- **The same code must run everywhere.** The HTTP client, file I/O, MCP, and
  tests work on the JVM, Scala.js, and Scala Native, and so do HTTP servers
  built from request handlers (the annotation router is JVM-only). In the
  Toolkit, os-lib is missing on Scala.js and Cask runs on the JVM only.
- **You want few transitive dependencies.** On the JVM, `uni` depends only on
  `uni-core` and the Scala library.
- **You are moving from Airframe.** Uni is its Scala 3 successor. See the
  [migration table](./for-ai-agents#how-do-i-migrate-from-airframe).

## Side by side

### HTTP GET and decode JSON

::: code-group

```scala [Toolkit] skip
//> using toolkit latest
import sttp.client4.quick.*
import upickle.default.*

case class Repo(full_name: String, stargazers_count: Int) derives ReadWriter

@main def showRepo =
  val response = quickRequest.get(uri"https://api.github.com/repos/scala/toolkit").send()
  val repo     = read[Repo](response.body)
  println(s"${repo.full_name}: ${repo.stargazers_count} stars")
```

```scala [Uni]
//> using dep org.wvlet.uni::uni:__UNI_VERSION__
import wvlet.uni.http.{Http, Request}
import wvlet.uni.weaver.Weaver

case class Repo(full_name: String, stargazers_count: Int) derives Weaver

@main def showRepo =
  val client   = Http.client.newSyncClient
  val response = client.send(Request.get("https://api.github.com/repos/wvlet/uni"))
  val repo     = Weaver.fromJson[Repo](response.contentAsString.getOrElse("{}"))
  println(s"${repo.full_name}: ${repo.stargazers_count} stars")
  client.close()
```

:::

Both decoders ignore JSON fields the case class does not declare. The Uni client
throws `HttpException` for most 4xx responses and retries 5xx and 429 before it
throws `HttpMaxRetryException`, so you do not need to check the status code.

### A unit test

::: code-group

```scala [Toolkit] skip
class MyTests extends munit.FunSuite:
  test("sum of two integers") {
    val obtained = 2 + 2
    val expected = 4
    assertEquals(obtained, expected)
  }
```

```scala [Uni]
import wvlet.uni.test.*

class MyTests extends UniTest:
  test("sum of two integers") {
    val obtained = 2 + 2
    obtained shouldBe 4
  }
```

:::

### Write and read a file

::: code-group

```scala [Toolkit] skip
val path = os.pwd / "notes.txt"
os.write.over(path, "hello")
println(os.read(path))
```

```scala [Uni]
import wvlet.uni.io.IO

IO.writeString("notes.txt", "hello")
println(IO.readString("notes.txt"))
```

:::

The os-lib version runs on the JVM and Scala Native. The Uni version also runs
on Scala.js under Node.js.

## Using them together

Uni and the Toolkit coexist. Their packages do not overlap (`wvlet.uni.*` vs.
`sttp`, `upickle`, `os`, `munit`), and Uni has no dependency that conflicts with
them. We checked this with Scala CLI, Toolkit 0.9.2, and Uni on Scala 3.9:

- One case class can derive both `ReadWriter` and `Weaver`.
- A MUnit `FunSuite` can test Uni code, and MUnit and UniTest suites run in the
  same `test` command.

Common combinations:

- **Keep MUnit, add Uni.** Test Uni services with your existing MUnit suites,
  and register UniTest too (`testFrameworks += new TestFramework("wvlet.uni.test.Framework")`
  in sbt) only if you want its matchers.
- **Keep os-lib for subprocesses** in a Uni application.
- **Pick one JSON library per type.** Having both is possible, but two codecs
  for the same case class are two things to keep in sync. If you use Uni's HTTP
  server or RPC, use Weaver, because they encode with it.
