# Netty server closes its connections before stopping its executors

## Context

`NettyHttpServer.stop()` used to close the server channel and then call `shutdownGracefully` on the handler executor group (`withHandlerExecutorThreads`), the SSE executor, the worker group, and the boss group, in that order. Accepted connections were never closed explicitly. A keep-alive connection that was idle when the handler group terminated stayed open. When the client later sent bytes or closed it, the event loop dispatched `channelRead` / `exceptionCaught` / `channelInactive` to the `"handler"` pipeline entry bound to the terminated group, and Netty logged `RejectedExecutionException: event executor terminated` three times with stack traces. HTTP clients keep pooled connections by default, so this happened on most shutdowns of a server with handler threads, and on every run of the tests that use them.

## Decision

- The server tracks accepted channels in a `DefaultChannelGroup`. A per-connection `ConnectionDrainHandler`, placed right after the HTTP codec, counts requests received minus responses written (`LastHttpContent`, as `RequestReadTimeoutHandler` does). Interim 1xx replies such as the aggregator's `100 Continue` are not counted as responses.
- `stop()` first closes the server channel. It then asks every connection to drain: an idle or WebSocket-upgraded connection closes now, and a busy one closes right after its last pending response is written. It waits for all of them, bounded by the shutdown deadline, and force-closes any left (e.g. an endless SSE stream).
- It then submits a no-op to every worker event loop and waits. A closed channel fires `channelInactive` in a later event-loop task, so this makes those events reach the handler group before the group shuts down. Graceful shutdown still runs tasks already queued.
- Past the deadline, the forced close and the event-loop flush still wait at most 1s each, so a handler blocking an event loop cannot make `stop()` hang.
- `stop()` called from a server thread (an event loop or a handler thread, e.g. a `/shutdown` endpoint) runs the shutdown on a new thread and returns at once. Waiting there would block on work only that thread can do: its own request, or its event loop.
- Only then are the handler group, SSE executor, worker group, and boss group shut down, as before.

## Why not

- **Shut down the worker group before the handler group:** in-flight handlers write responses through the event loop, so they would fail.
- **Close every connection immediately:** this cuts off in-flight responses, which graceful shutdown exists to deliver.
- **Suppress Netty's warnings:** this hides a production problem.
- **Drop per-handler executors** (deprecated in Netty 4.2) in favour of offloading inside `NettyRequestHandler`: this also removes the root cause, but rewrites request dispatch. It's left as a possible separate change.

## Consequences

- A request whose bytes are still arriving when `stop()` starts counts as idle, so its connection is closed. Clients see a connection reset and may retry.
- WebSocket connections are closed without a close frame, as before.
- `stop()` from a server thread returns before the server has stopped; `isRunning` is already false.
- `NettyServerShutdownTest` covers: no `io.netty` warnings when a client still holds a pooled connection, the server closing an idle keep-alive connection, in-flight requests (plain and `Expect: 100-continue`) still getting their response during `stop()`, and `stop()` from a request handler.
