# Uni examples

Small, runnable apps built with [Uni](https://wvlet.org/uni/). Each folder is a self-contained
template you can copy: one or two Scala files, a README that explains what it shows, and the
`libraryDependencies` you need in your own build.

| Example | Platforms | Shows |
|---------|-----------|-------|
| [`cli-tool`](cli-tool/) | JVM, Scala Native | `Launcher` sub-commands and options, `LogSupport`, `derives Weaver` JSON decoding, the HTTP client, a native binary via `nativeLink` |
| [`rest-server`](rest-server/) | JVM | `NettyServer` with `Router` REST endpoints and an `RPCRouter` service, a typed RPC client, `Design` wiring, and a UniTest suite that starts the server |
| [`rpc-native`](rpc-native/) | JVM, Scala Native | One `TodoApi` trait served by `NativeServer` as a native binary (no JVM) and by `NettyServer`, a typed client, a UniTest suite, and a reproducible [benchmark](rpc-native/bench/) |
| [`mcp-server`](mcp-server/) | JVM | A stdio MCP server with `MCP.server.withTools[...]`, tested in-process, and how to add it to Claude Code |
| [`web-ui`](web-ui/) | Scala.js | A todo list with `RxElement`, `Rx.variable`, and two-way form binding, loaded by a static `index.html` |
| [`electron-app`](electron-app/) | Scala.js | A desktop app that calls the main process over Electron IPC with Uni RPC (standalone build) |

## Run them from this repository

The first five are projects in the root `build.sbt`, so CI compiles and tests them against the
current source and they never go stale. They are not published.

```bash
./sbt "exampleCliJVM/run repo wvlet/uni"      # CLI tool on the JVM
./sbt exampleCliNative/nativeLink             # CLI tool as a native binary
./sbt exampleRestServer/run                   # REST + RPC server on http://localhost:8080
./sbt exampleRestServer/test                  # starts the server on a free port and calls it
./sbt exampleRpcNativeNative/nativeLink       # typed RPC server as a native binary
./sbt exampleRpcNativeJVM/test                # the same server on Netty, called by the typed client
./sbt --error exampleMcpServer/run            # stdio MCP server (speaks JSON-RPC on stdin/stdout)
./sbt exampleWebUi/fastLinkJS                 # builds examples/web-ui/target/js/main.js
```

## Copy one into your own project

Each README lists the dependencies. In a standalone sbt build, depend on the published artifacts:

```scala
// build.sbt
scalaVersion := "3.9.0"
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"       % "<version>",
  "org.wvlet.uni" %% "uni-netty" % "<version>",        // only for the JVM HTTP server
  "org.wvlet.uni" %% "uni-test"  % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

Use the latest version from [Maven Central](https://central.sonatype.com/artifact/org.wvlet.uni/uni_3).
On sbt 1.x, use `%%%` instead of `%%` for Scala.js and Scala Native projects.
