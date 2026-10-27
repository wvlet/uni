# CLI tool: GitHub repository lookup

A command-line tool that looks up GitHub repositories. It runs on the JVM and builds into a
single Scala Native binary with no JVM and millisecond startup.

```text
$ uni-gh repo wvlet/uni
wvlet/uni — Essential Scala 3 utilities for JVM, Scala.js and Scala Native: ...
  language: Scala, stars: 17, forks: 0
  https://github.com/wvlet/uni

$ uni-gh stars wvlet/uni wvlet/airframe
    665  wvlet/airframe
     17  wvlet/uni
```

## What it shows

- **Launcher** (`wvlet.uni.cli.launcher`): global options on the class constructor (`-v`, `--api`),
  sub-commands as `@command` methods (`repo`, `stars`), positional `@argument`s including
  `Seq[String]`, and generated `--help` for the tool and each sub-command.
- **Logging**: `LogSupport` with `debug(...)`, turned on by `-v` via `Logger.setDefaultLogLevel`.
- **Weaver JSON**: `case class Repo(...) derives Weaver` decodes the GitHub response. Keys match
  flexibly (`stargazers_count` → `stargazersCount`) and unknown fields are skipped.
- **HTTP client**: `Http.client.withBaseUri(...).newSyncClient`, request headers, and
  `AutoCloseable` cleanup. Non-2xx responses throw `HttpException`, so there is no status check.
- **UniTest**: [`GitHubCliTest`](.jvm/src/test/scala/example/cli/GitHubCliTest.scala) tests JSON
  decoding and help output without touching the network.

Source: [`GitHubCli.scala`](src/main/scala/example/cli/GitHubCli.scala).

## Run

```bash
./sbt "exampleCliJVM/run repo wvlet/uni"
./sbt "exampleCliJVM/run -v repo wvlet/uni --json"
./sbt "exampleCliJVM/run stars wvlet/uni wvlet/airframe"
./sbt exampleCliJVM/test
```

## Build a native binary

Scala Native needs clang and libcurl
([setup](https://scala-native.org/en/stable/user/setup.html)). Then:

```bash
./sbt exampleCliNative/nativeLink
./target/out/native0.5/scala-3.9.0/uni-example-cli/uni-example-cli repo wvlet/uni
```

sbt prints the binary's path at the end of `nativeLink`. Copy it anywhere on your `PATH`.

## Use it in your own build

The sources go under `src/main/scala` of a JVM or Scala Native project:

```scala
// project/plugins.sbt (Native only)
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")

// build.sbt
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"      % "<version>",
  "org.wvlet.uni" %% "uni-test" % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
// Native only: the HTTP client links against libcurl
// enablePlugins(ScalaNativePlugin)
// nativeConfig ~= { _.withLinkingOptions(_ ++ Seq("-lcurl", "-lz")) }
```
