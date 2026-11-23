# Shared server settings and streaming request bodies

## Goals

- Let apps tune the server through `Http.server`, so the same settings work on every backend. Today, tuning the JVM built-in server means adding uni-netty.
- Let a handler read a large upload (GBs, piped to disk or an `ObjectStore`) as a stream. Today the whole body is buffered, up to `maxContentLength` (1 MiB by default).
- Ship as two PRs: A (settings), then B (streaming), which builds on A's settings.

## Background

- `HttpServerConfig` already shares a few settings: `maxContentLength`, `webSocketMaxFrameSize`, and `webSocketPingIntervalMillis`. Each is an `Option`, and `None` means the backend's default. Each backend's `startServer` copies them over the backend's options, so a shared setting wins.
- Every other limit is backend-only: `Native.options` (Native), `Netty.options` (uni-netty), or fixed defaults (the JVM built-in server, ADR `2026-11-18-jvm-builtin-http-server`). Node.js takes only the shared settings.
- The socket server already has these limits internally (`SocketServerLimits`), shared by the JVM built-in server and Native. Only the config plumbing is missing.
- Request bodies: the socket reader frames the body (Content-Length or chunked), decompresses it, then builds `Request` with `ByteContent`. Netty aggregates the same way (`HttpObjectAggregator`). Node.js collects the chunks.
- Breaking changes are fine at this stage (no deprecation aliases).

## Design — PR A: shared settings

Lane: Standard (new public API on every backend).

- **User flow:** `Http.server.withMaxHeaderSize(16 * 1024).withIdleTimeoutMillis(60000)` works whether the app runs on the JVM built-in server, Native, Netty, or Node. When the app adds or drops uni-netty, its settings carry over unchanged.
- **New shared settings** on `HttpServerConfig`. Each is `Option` (`None` = backend default) and follows the `withXXX` / `noXXX` convention:
  - `maxInitialLineLength`, `maxHeaderSize`
  - `requestReadTimeoutMillis` (whole-request deadline), `writeTimeoutMillis`, `idleTimeoutMillis` (keep-alive idle)
  - `requestDecompression` (on/off), `maxDecompressedRequestSize`
  - `maxStreamingConnections` (SSE and WebSocket)
  - `shutdownTimeoutMillis`: how long `stop()` waits for in-flight requests
- **Zero means "disabled"** for the request, write, and shutdown timeouts, set with `noXXX()`. The idle timeout must stay positive, as on Native today.
- **Each backend applies the settings it can,** and the Scaladoc of each setting names the backends that ignore it. This replaces the rule in ADR `2026-10-31-http-server-entry-point` ("a setting joins only when all three backends support it"). PR A amends that ADR and the "add uni-netty to tune" line in `2026-11-18-socket-server-netty-parity`.
  - JVM built-in and Native apply all of them.
  - Netty applies all of them. PR A wires up the write and idle timeouts with Netty's `WriteTimeoutHandler` and `IdleStateHandler`, which are already in `netty-handler`. `shutdownTimeoutMillis` maps to Netty's timeout in seconds, rounded up. `withGracefulShutdown`'s quiet period stays Netty-only.
  - `maxStreamingConnections` caps SSE and WebSockets on the socket server, but only SSE on Netty. The Scaladoc states the difference.
  - Node.js maps what it has: `headersTimeout`/`requestTimeout`, `keepAliveTimeout`, and `maxHeaderSize`. It ignores the rest.
- **Remove the duplicates** from `Native.options` and `Netty.options`. Only truly backend-specific options stay:
  - Native: worker threads, handler timeout, backlog, per-read timeout.
  - Netty: native transport, handler executor threads, shutdown quiet period and hook.
  - The backend configs (`NativeServerConfig`, `NettyServerConfig`) keep every field, for code that builds them directly.
- **The built-in JVM server takes no options of its own.** It reads only the shared settings, falling back to today's defaults, so its behavior doesn't change when nothing is set.
- Tests: each shared setting reaches the backend config on JVM built-in, Netty, and Native, plus one end-to-end check per limit on the built-in server.

## Design — PR B: streaming request bodies

Lane: Standard (new API, request handling path).

- **User flow:** an app serving `PUT /files/{name}` turns streaming on for that route. The handler reads `request.content` as a `java.io.InputStream` and copies it to a file or an `ObjectStore` multipart upload. If something goes wrong, the handler returns an error response. The server then closes the connection, because the rest of the body was not read.
- **Opt in on the request head:** `Http.server.withStreamingRequests(request => Boolean)`, evaluated after the head is parsed and the host check passes, before any body bytes are read. Every other request is buffered as today, so filters that read the body (such as RPC argument logging) keep working.
- **New body type `HttpContent.StreamContent`:**
  - `openStream: InputStream`; `length` is the `Content-Length`, or -1 when the body is chunked.
  - `isEmpty` is true only for a known length of 0.
  - `asBytes` and `toContentBytes` read the rest of the stream once and cache it, bounded by the size limit. A filter that calls them therefore doesn't take the body away from the handler: a later `openStream` reads the cached bytes.
  - `contentHash` hashes the identity, not the bytes, as `FileContent` does.
- **Filters run before the body is read.** For `Expect: 100-continue`, the server sends `100 Continue` only on the first read. A filter that rejects the request (for example on auth) therefore stops the client before it uploads anything.
- **Limits, so a slow client can't hold a worker or a virtual thread forever:**
  - `maxStreamingRequestSize`, 1 GiB by default; larger bodies get 413.
  - A body deadline, `streamingRequestTimeoutMillis` (1 hour by default), plus the per-read timeout: 30 s with no bytes closes the connection.
  - Each streaming upload takes a slot from `maxStreamingConnections`, like SSE and WebSockets. On Native, uploads therefore can't use up the workers that ordinary requests need.
  - The handler timeout (Native, 30 s) doesn't apply to streaming requests; the deadlines above bound them instead.
- **Threads:** any thread may read the stream (for example inside an `Rx`) until the response is written. A read after that, or after a cancel or timeout, throws, and the connection closes.
- **Framing is unchanged:** Content-Length and chunked framing use the same strict rules as the Lean model, decoded incrementally.
- **Compressed bodies are not decoded in a stream.** The handler sees `Content-Encoding` and the bytes as sent.
- **After the response:**
  - The stream is valid until the response is written; later reads fail.
  - An unread remainder of up to 64 KiB is drained, so the connection stays alive. Beyond that, the connection closes.
- **Backends:**
  - The JVM built-in server and Native stream for real. Each handler blocks on its own virtual thread (JVM) or worker (Native).
  - Netty and Node.js still buffer the body, then hand the handler a `StreamContent` over the buffered bytes. The handler code is the same, with these differences, all documented:
    - The buffered body is capped by `maxContentLength`, not `maxStreamingRequestSize`.
    - Netty sends `100 Continue` before filters run.
    - Netty skips request decompression for streaming routes, so its handlers also see the bytes as sent.
  - Scala.js can't block on a read, so streaming there is limited to this buffered fallback.
- Tests:
  - multi-MiB uploads with both Content-Length and chunked bodies, on the JVM built-in server and Native;
  - a handler that returns early (drain vs close);
  - `100 Continue` sent only on the first read;
  - size-limit and timeout errors;
  - a small streaming request on Netty, to cover the fallback.

## Alternatives and Why Not?

- **A Netty-style `ServerOptions` for the built-in JVM server:** it splits the settings again, so they wouldn't carry over between JVM backends.
- **Streaming opt-in per route through a Router annotation:** cleaner for `@Endpoint` users, but it needs Router and RPC changes. Can come later on top of the predicate.
- **Streaming every body lazily:** this breaks filters and loggers that read the body, and buffered handlers would lose the `maxContentLength` protection.
- **`Rx[Array[Byte]]` instead of `InputStream`:** a better fit for JS and Rx, but every handler on the JVM and Native would need back-pressure plumbing. The direct-style `InputStream` matches the virtual-thread design.
