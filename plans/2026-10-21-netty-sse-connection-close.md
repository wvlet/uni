# Netty SSE: advertise `Connection: close`

## Goals

- Stop pooling HTTP clients from reusing a connection that uni-netty is about to close after an SSE stream.
- Port the server half of airframe#4274 (airframe-http-netty) to uni-netty.

## Background

- `NettyRequestHandler.sendSseResponse` sends `Connection: keep-alive`, but every stream ends with
  `LastHttpContent` + `ChannelFutureListener.CLOSE`: on completion, on error, and on executor failure.
- A pooling client (e.g. Java `HttpClient`) can return the connection to its pool before the FIN arrives;
  the next request on it fails with `IOException: connection closed locally`. airframe hit this
  intermittently (~1 in 1.7k iterations of a finite SSE request followed by a normal request).
- Other uni servers are unaffected:
  - Native (`NativeHttpServer`) ends the chunked stream and keeps the connection, so `keep-alive` is accurate.
  - Node.js (`NodeHttpServer`) relies on Node's own connection management after `res.end()`.
- The client half of airframe#4274 (`sendSafe` returning a synthetic 500 that hides the cause, and `rpc()` mapping it
  to `DATA_LOSS_I8`) has no counterpart: uni's `HttpClient` has no `sendSafe`/`rpc`, and transport errors after retries
  throw `HttpMaxRetryException` with the original cause.

## Design

Lane: **Express**, a small server-only bug fix with a clear diagnosis that touches no always-gate area.

- Set `Connection: close` on the SSE response headers in uni-netty.
- Skip any handler-supplied `Connection` header when copying custom headers, so a handler can't advertise
  keep-alive on a connection that will be closed.
- Long-lived streams, cancellation, the stream limiter, the 503 paths, and keep-alive for non-SSE responses
  stay as they are.
- Test: extend the raw-socket SSE test in `NettyServerTest` to check for `connection: close` and that the
  server closes the socket (EOF, not a read timeout).

## Alternatives and Why Not?

- Keep the connection open after `LastHttpContent` (true keep-alive, as Native does): a larger change to the
  stream lifecycle and to the stream-limiter slot release, which depends on the channel closing. Not worth it for SSE,
  where streams are usually long-lived.
