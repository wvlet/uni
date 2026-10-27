# Toolkit script to Uni service: a GitHub repository API

A [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html) script (sttp + uPickle)
grown into a service with Uni, keeping sttp and uPickle for the GitHub API. The guide page
[From a Scala Toolkit script to a Uni service](https://wvlet.org/uni/guide/toolkit-to-uni) walks
through this code step by step; its code blocks are imported from these files.

## What it shows

- **The starting script**: [`RepoScript.scala`](src/main/scala/example/toolkit/RepoScript.scala)
  fetches a repository with sttp `quickRequest` and decodes it with uPickle `derives ReadWriter`.
- **Toolkit libraries and Uni in one build**: the project depends on the sttp and uPickle versions
  that Toolkit 0.9.2 pins, plus `uni`, `uni-netty`, and `uni-test`.
- **A trait in front of sttp**: `GitHubRepoSource` keeps the script's code; `RepoService` depends
  on the `RepoSource` trait and logs with `LogSupport`.
- **One codec per type**: `GitHubRepo` keeps uPickle for the external API; `RepoSummary`, which the
  Uni server returns, derives `Weaver`.
- **REST with `Router`** on `NettyServer`, and **Design wiring** with `onStart` / `onShutdown`.
- **UniTest**: [`RepoServerTest`](src/test/scala/example/toolkit/RepoServerTest.scala) replaces the
  GitHub source with an in-memory one (a later binding wins), starts the server on a free port, and
  calls it. No network needed.

Source: [`RepoServer.scala`](src/main/scala/example/toolkit/RepoServer.scala).

## Run

```bash
./sbt "exampleToolkitService/runMain example.toolkit.repoScript scala toolkit"   # the script
./sbt exampleToolkitService/run      # the service on http://localhost:8080
./sbt exampleToolkitService/test
```

Then, from another terminal:

```bash
curl localhost:8080/api/repos/scala/toolkit
```

## Dependencies

```scala
libraryDependencies ++= Seq(
  "com.softwaremill.sttp.client4" %% "core"      % "4.0.21",
  "com.lihaoyi"                   %% "upickle"   % "4.4.3",
  "org.wvlet.uni"                 %% "uni"       % "<version>",
  "org.wvlet.uni"                 %% "uni-netty" % "<version>",
  "org.wvlet.uni"                 %% "uni-test"  % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```
