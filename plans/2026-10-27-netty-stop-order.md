# Close connections before stopping the Netty handler pool

## Goals

- Stop `NettyServer` without Netty logging `RejectedExecutionException: event executor terminated` when a server with `withHandlerExecutorThreads` still has open client connections.
- Keep graceful shutdown: in-flight requests still finish and their responses reach the client.
- Close keep-alive and WebSocket connections explicitly on stop, instead of leaving them open until the event loops terminate.

## Background

`NettyHttpServer.stop()` runs in this order:

1. Close the server channel (no new connections).
2. `shutdownGracefully` the handler executor group, so in-flight handlers can still write responses through the event loop.
3. Shut down the SSE executor.
4. `shutdownGracefully` the worker group, then the boss group.

Accepted connections are never closed explicitly. A keep-alive connection that is idle at step 2 stays open. If the client then sends bytes or closes it, the event loop dispatches `channelRead` / `exceptionCaught` / `channelInactive` to the `"handler"` pipeline entry, which is bound to the terminated handler group. Netty rejects the dispatch and logs three warnings with stack traces (`Failed to submit an exceptionCaught() event`, `A task raised an exception`). `NettyServerTest` and `WebSocketTest` reproduce this every run through their `withHandlerExecutorThreads` tests. In production it shows up as scary shutdown logs whenever clients keep pooled connections, which is the common case.

Without `withHandlerExecutorThreads` the handler runs on the event loop, so nothing is rejected, but idle connections are still dropped abruptly when the worker group terminates.

## Design

Lane: **Standard**: changes production shutdown behaviour.

- Track accepted connections in a `DefaultChannelGroup` (Netty removes a channel from the group when it closes).
- Add a small per-connection `ConnectionDrainHandler` right after the HTTP codec. It counts requests received minus responses written (the same `LastHttpContent` counting `RequestReadTimeoutHandler` already does) and exposes `drain()`:
  - idle (no pending request): close now;
  - busy: close right after the last pending response is written;
  - upgraded to WebSocket: close now (the WebSocket handler sends no close frame today; out of scope).
- New `stop()` order:
  1. Close the server channel.
  2. Call `drain()` on every tracked connection (on its event loop), then wait until all are closed or the shutdown timeout's quiet window ends.
  3. Force-close any connection still open (e.g. an endless SSE stream).
  4. Shut down the handler group, SSE executor, worker group, and boss group as today. No connections remain, so nothing is dispatched to a terminated executor.
- `shutdownQuietPeriodSeconds` / `shutdownTimeoutSeconds` keep their meaning: the drain wait is bounded by the same deadline.
- Tests: a regression test that stops a `withHandlerExecutorThreads(4)` server while a Java `HttpClient` holds a pooled connection, and asserts no `WARN` from `io.netty` (captured with a log handler) and that the server stops quickly. Another test checks that a slow in-flight request still gets its response during `stop()`.

## Alternatives and Why Not?

- **Suppress Netty's warnings in the two tests** — hides a real production problem.
- **Shut down the worker group before the handler group** — in-flight handlers could no longer write responses, which breaks graceful shutdown.
- **Close all connections immediately on stop** — simple, but cuts off in-flight responses.
- **Drop the per-handler executor and offload to a pool inside `NettyRequestHandler`** — fixes the root cause too, but it's a larger rewrite of request dispatch; Netty 4.2 deprecates per-handler executors, so it may be worth doing later as its own change.
