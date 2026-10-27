---
description: "Grow a Scala Toolkit script (sttp + uPickle) into a Scala 3 service with Uni: Design object wiring, logging, a REST endpoint on Netty, and UniTest, while keeping sttp and uPickle. All code is a tested example project."
---

# From a Scala Toolkit script to a Uni service

**Many Scala projects start as a [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html)
script and later need to become a service. This tutorial takes a small sttp + uPickle
script that reads a GitHub repository, and grows it step by step into a service with
object wiring ([Design](../core/design)), [logging](../core/logging), a
[REST endpoint](../http/server), and a [UniTest](../core/unitest) suite. sttp and
uPickle stay where they still fit: they keep calling and decoding the GitHub API.**

All the code on this page comes from the
[`examples/toolkit-service`](https://github.com/wvlet/uni/tree/main/examples/toolkit-service)
project, which CI compiles and tests with the sttp and uPickle versions of Toolkit 0.9.2.
For when to choose each library, see [Uni and the Scala Toolkit](./uni-vs-scala-toolkit).

## 1. Start with a Toolkit script

The script fetches a repository from the GitHub API with sttp and decodes the JSON
with uPickle. Run it with `scala-cli run RepoScript.scala -- scala toolkit`.

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoScript.scala#script

This is the Toolkit at its best: one directive, no build file, and readable code.
Nothing here needs to change yet.

## 2. Add Uni to the build

When the script becomes a service, move it to an sbt project and add Uni next to
the Toolkit libraries. `uni-netty` provides the HTTP server on the JVM.

```scala
// build.sbt
scalaVersion := "__SCALA_VERSION__"
libraryDependencies ++= Seq(
  "com.softwaremill.sttp.client4" %% "core"      % "4.0.21",
  "com.lihaoyi"                   %% "upickle"   % "4.4.3",
  "org.wvlet.uni"                 %% "uni"       % "__UNI_VERSION__",
  "org.wvlet.uni"                 %% "uni-netty" % "__UNI_VERSION__",
  "org.wvlet.uni"                 %% "uni-test"  % "__UNI_VERSION__" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

These are the sttp and uPickle versions that Toolkit 0.9.2 uses. You can also depend
on `"org.scala-lang" %% "toolkit" % "0.9.2"` instead. Uni requires Scala 3.9 or later.

The service file imports from both sides. The package names do not overlap:

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoServer.scala#imports

## 3. Put the script's code behind a trait

The service will depend on a `RepoSource` trait instead of calling sttp directly,
so a test can supply its own data. The script's sttp and uPickle code moves into
`GitHubRepoSource` with almost no change. `LogSupport` adds `debug` and `info`
with source locations.

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoServer.scala#source

## 4. Write the service

`RepoService` takes its dependency as a constructor parameter and caches results.
Its own API returns `RepoSummary`, which derives `Weaver`, because Uni's HTTP server
encodes responses with Weaver. `GitHubRepo` keeps `derives ReadWriter` for the GitHub
API, so each type has one codec.

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoServer.scala#service

## 5. Serve it over HTTP

An `@Endpoint` method on a controller becomes a REST route. Path parameters bind to
method parameters, and the `RepoSummary` result is returned as JSON.

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoServer.scala#controller

## 6. Wire it with Design

`Design` lists how to build each object. `build[RepoServer]` creates the server and
everything it needs through their constructors, runs `onStart`, and runs `onShutdown`
when the block exits.

<<< @/../examples/toolkit-service/src/main/scala/example/toolkit/RepoServer.scala#server

Run it and call it:

```bash
./sbt exampleToolkitService/run    # in the uni repository
curl localhost:8080/api/repos/scala/toolkit
# {"name":"scala/toolkit","stars":...,"forks":...}
```

## 7. Test it with UniTest

The test builds the production design with port 0 (a free port) and replaces the
GitHub source with an in-memory one, so it needs no network. A later binding for
the same type replaces the earlier one. The second test decodes GitHub JSON with
uPickle and encodes the summary with Weaver in the same suite.

<<< @/../examples/toolkit-service/src/test/scala/example/toolkit/RepoServerTest.scala#test

If your project already has MUnit suites, keep them. MUnit can test Uni code, and
MUnit and UniTest suites run in the same `sbt test`.

## What stayed, what was added

| Piece | Kept from the Toolkit | Added with Uni |
|-------|-----------------------|----------------|
| Calling the GitHub API | sttp `quickRequest` | |
| Decoding GitHub JSON | uPickle `derives ReadWriter` | |
| The service's own JSON | | Weaver `derives Weaver` |
| Building and connecting objects | | `Design`, with `onStart` / `onShutdown` |
| Logging | | `LogSupport` |
| HTTP server | | `NettyServer` with a `Router` controller |
| Tests | MUnit, if you have it | UniTest |

From here, you can turn the service into [typed RPC](../http/rpc), add
[retries](../control/retry), or replace sttp with Uni's [HTTP client](../http/client)
if you want one client on the JVM, Scala.js, and Scala Native. Keeping sttp is fine too.
