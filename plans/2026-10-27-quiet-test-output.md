# Quiet test output

## Goals

- Make `./sbt projectJVM/test` output show test results, not ~800 lines of expected warnings and stack traces, so a real warning stands out.

## Background

`projectJVM/testFull` printed ~820 non-`[info]` lines, ~730 of them stack-trace frames:

- Tests that make a handler fail on purpose (500 rendering, detailed errors, RPC errors, access-log callback failures, SSE limits, CR/LF header rejection) log `warn` with a full stack trace from `NettyRequestHandler`, `RPCDispatcher`, or `HttpAccessLogFilter`.
- `GitHubCliTest` prints the CLI help twice; `LogLevelTest` prints "Unknown log level" to stderr.
- A `uni` skill snippet (`references/http-client.md`) compiled with an E129 "pure expression does nothing" warning.
- Netty logs `RejectedExecutionException: event executor terminated` when a server with `handlerExecutorThreads` stops while a client keeps a connection open: `stop()` terminates the handler executor before the worker group, so later events on that connection cannot be dispatched.

## Design

Lane: **Express**: test, example-test, and skill-snippet changes only; no library behaviour change.

- Wrap each test that triggers an error on purpose in `Logger.of[X].suppressWarnings { ... }`, the idiom `DITest` and `LoggerTest` already use, naming the class that logs. `RouterHandlerErrorTest` wraps its `withServer` helper, since every test there exercises error paths.
- Capture stdout/stderr with `Console.withOut` / `Console.withErr` in `GitHubCliTest` and `LogLevelTest`, and assert the captured text, which also makes those tests check the message.
- Bind the values in the skill snippet to `val`s; bump the plugin version.
- Leave the Netty shutdown-order warnings visible: they point at a real `NettyHttpServer.stop()` issue that also affects production, to be fixed separately.

## Alternatives and Why Not?

- **Raise the default test log level to ERROR** — hides unexpected warnings too, which defeats the purpose.
- **Suppress at suite level everywhere** — same problem at a smaller scale; per-test wrapping keeps the suppression next to the error it expects.
