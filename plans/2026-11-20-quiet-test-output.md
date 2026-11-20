# Quiet unit test output

## Goals

- A passing `./sbt test` should print only test results, so that a real failure's log output stands out.
- Today a full JVM run prints about 430 stack-trace lines and a dozen info lines from tests that pass.

## Background

- Most of the noise comes from tests that make a handler fail on purpose (`boom`, `handler failed`). The server or filter then logs the failure with its full stack trace at WARN level.
- UniTest already has `captureLogs { ... }` (ADR `2026-10-27-unitest-capture-logs.md`). It collects these records instead of printing them, and replays them if the block throws. `RouterHandlerErrorTest`, `NettyServerTest`, and others already use it.
- Noisy suites in the JVM run:
  - Expected-failure warnings with stack traces: `CorsFilterTest`, `CorsFilterServerTest`, `WebSocketRelaySpec`, `HttpAccessLogRPCArgsTest`, `MCPClientTest`, `RPCErrorStackTraceTest`, `RxOnCancelTest`, `NettyStaticFilesTest` (missing file).
  - Info lines: `JvmHttpServer` logs "Started the built-in HTTP server at …" once per server start (12 to 34 lines per run), and the codegen test logs "Generated: …".
- sbt 2 caches test results, so `test` reruns only changed suites. I used `testFull` to see every suite. Scala.js output is checked in the same pass. The Scala Native suites run the same shared tests.

## Design

- Lane: **Express**. The change touches only tests and build settings, with no change in library behaviour, and no always-gate area.
- Wrap each expected-failure block in `captureLogs` (narrowed with a type argument where the block is long), and assert on the captured warning, so the change also checks that the failure is logged.
- Tests whose body returns an Rx (`MCPClientTest`, some Scala.js suites) cannot use `captureLogs`, which rejects an Rx block. They drive the in-memory Rx synchronously inside the capture and assert after it, as `MCPServerTest` does.
- Keep the "Started the built-in HTTP server" line at INFO for users. Silence it only in tests, by passing UniTest's existing `-l:wvlet.uni.http.JvmHttpServer=warn` option through `Test / testOptions` in the JVM settings of `uni` and `netty`. Use `-l:wvlet.uni.http.JvmHttpServer=info` to see it again.
  - This sets the level of one logger, and only for INFO lines. sbt doesn't fork tests, so the level stays in the sbt JVM for later test runs. Wrapping all ~30 server starts in `captureLogs` would avoid that, but it touches every server test.
- Capture the codegen "Generated:" lines, the Scala.js warnings (Electron RPC rejections, Retry), and the `NodeSyncHttpChannelTest` debug-style info lines in the same way.
- Fix the Node.js `fs.rmdir(recursive)` deprecation warning printed by the Scala.js file system by calling `fs.rmSync` in `deleteRecursively`.
- Done when a `testFull` run of the JVM and JS modules prints no log lines or stack traces while every test passes.

## Alternatives and Why Not?

- Lower the server-start log to DEBUG: this changes what server users see at startup. That is a separate product decision, not test cleanup.
- Raise the global test log level to WARN or ERROR: this hides the logs that explain real failures, and the expected-failure warnings are WARN anyway.
- Stop logging stack traces in the filters: this would hide production diagnostics just to quiet the tests.
