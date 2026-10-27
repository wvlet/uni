# Async HTTP client end-to-end on Scala Native and Scala.js

## Goals

- Prove that `Http.client.newAsyncClient.send(...)` works end-to-end on Scala Native (`CurlAsyncChannel`) and Scala.js/Node (`FetchChannel`), as #766 did for the JVM.
- Cover three outcomes per platform: a 2xx body, a 4xx that fails with `HttpException`, and a 5xx that fails with `HttpMaxRetryException` after retries.
- Fix any channel bug the tests surface, and correct the platform statements in the docs and the `uni` skill.

## Background

- #766 added `Rx.AsyncOp` so `RxDeferred` runs under `RxRunner`; that repaired the JVM async client, and `uni/.jvm/.../HttpAsyncClientTest.scala` now covers it.
- Native's `CurlAsyncChannel` wraps the blocking `CurlChannel` in `Rx.single`; retries go through `RetryContext.runAsyncWithContext`, which waits with `Rx.delay`. Nothing runs this path on Native today.
- JS `NodeServerTest` already drives the Fetch client against `NodeServer` for success and a 413 (with `noRetry`), but no test covers a 4xx failure on the default client or the 5xx retry loop (`Rx.delay` on the JS event loop).
- `Rx.await` is unsupported on Scala.js, so JS tests return the `Rx` (UniTest awaits it; it must emit a value, per `adr/2026-10-19-unitest-rx-empty-completion.md`).

## Design

Lane: **Express** — tests plus a fix only if one turns up; no always-gate area.

- Native: add `uni/.native/src/test/.../NativeHttpAsyncClientTest.scala` using `NativeServer.withPort(0).start { ... }` (the server has its own threads), with `send(...).await` like the JVM test.
- JS: add `uni/.js/src/test/.../NodeHttpAsyncClientTest.scala` using `NodeServer.withPort(0).startAndAwait { ... }`, returning the `Rx`; failures are turned into values with `.recover` so assertions run inside the stream.
- 5xx case: the handler returns 503 and counts calls; the client uses `withRetryContext(... .withMaxRetry(2).withBackOff(initialIntervalMillis = 10))` so the test stays fast. Assert `HttpMaxRetryException` with status 503 and 3 server hits.
- If a test fails, fix the channel (or Rx operator) in this PR.
- Docs: change "works in browsers and on the JVM" in `plugins/uni/skills/uni/references/http-client.md` (and `docs/http/client.md` if it says the same) to list JVM, Scala.js (browser and Node), and Scala Native; bump the plugin version.
