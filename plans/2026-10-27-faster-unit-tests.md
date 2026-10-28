# Faster unit tests

## Goals

- Make `./sbt test` (and `testOnly` on a single suite) finish fast enough for an interactive edit–test loop.
- Remove fixed waits that do not test anything, without weakening what the tests check.

## Background

A full JVM run (`coreJVM/testFull; uniJVM/testFull; testJVM/testFull; netty/testFull`) takes about 2m15s. Per-suite timings:

| Suite | Time |
|---|---|
| NettyServerTest | 39.6s |
| NettyServerSecurityTest | 30.6s |
| WebSocketTest | 26.0s |
| IOWatchJvmTest | 10.4s |
| WebSocketClientTest | 9.1s |
| other Netty suites | ~2.1s each |
| everything else | < 1s each |

The Netty suites account for ~110s. Nearly every Netty test takes ~2.08s no matter what it does: stopping the server calls Netty's `shutdownGracefully` with the default `shutdownQuietPeriodSeconds = 2`, so each stop waits 2s for a quiet period. That default makes sense for production (in-flight requests drain), but test servers on port 0 have nothing to drain once the test is done.

IOWatchJvmTest takes ~2s per test on macOS because the JDK's WatchService there is a polling implementation with a 2s floor. On Linux (CI) it uses inotify and is fast. Speeding it up on macOS would need a different watcher implementation, which is out of scope.

## Design

Lane: **Express** — test-only change, no change in library behaviour, no always-gate area.

- In `uni-netty` tests, build every test server with `.withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)` next to `.withPort(0)`, the idiom several Netty tests already use. The 5s timeout only bounds the drain; test servers finish well within it. Expected: Netty suites drop from ~110s to a few seconds.
- Leave tests that check the shutdown configuration itself (`withGracefulShutdown`, `withShutdownQuietPeriod` assertions) as they are.
- Keep the production default (2s) unchanged.
- Re-measure after the change and list the new slowest suites in the PR.

## Alternatives and Why Not?

- **Lower the default quiet period to 0** — fastest to write, but changes shutdown behaviour for every user to speed up our tests.
- **A shared test helper or suite-level server** (`addDesign` reusing one server) — less repetition, but tests build servers in varied builder orders, and an explicit setting at each call site documents why stop is instant. With the quiet period gone, a server starts and stops in milliseconds, so reusing one saves little. Most tests also need their own handler, filters, or limits, and separate servers keep stateful tests (SSE limits, read timeouts, kept-alive connections) independent.
- **Run suites in parallel** — hides the waits instead of removing them, and doesn't help `testOnly` on one suite.
