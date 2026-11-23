# Server limits and timeouts are shared `Http.server` settings

## Context

`HttpServerConfig` shared only the settings every backend had: `maxContentLength`, `webSocketMaxFrameSize`, and `webSocketPingIntervalMillis` (`2026-10-31-http-server-entry-point.md`: "a setting joins this list only when all three backends support it"). Every other limit was backend-only: `Native.options` on Scala Native, `Netty.options` in uni-netty, and fixed defaults on the built-in JVM server (`2026-11-18-jvm-builtin-http-server.md`, `2026-11-18-socket-server-netty-parity.md`). An app on the built-in JVM server that needed a larger header limit or a longer idle timeout had to add uni-netty. An app that switched JVM backends, or built for Native too, had to move each setting into another options object.

## Decision

- **The limits and timeouts become `Http.server` settings:** `maxInitialLineLength`, `maxHeaderSize`, `requestReadTimeoutMillis`, `writeTimeoutMillis`, `idleTimeoutMillis`, `requestDecompression`, `maxDecompressedRequestSize`, `maxStreamingConnections`, and `shutdownTimeoutMillis`. Each is an `Option`, and `None` keeps the backend's default, so an app that sets nothing behaves as before.
- **Each backend applies the settings it can; the setter's Scaladoc names the backends that ignore it.** This replaces the all-three-backends rule. A rule that every platform must support a setting kept the JVM built-in server, which supports them all, without any.
  - The built-in JVM server and Native apply all of them.
  - Netty applies all of them. Two needed new wiring: `writeTimeoutMillis` uses Netty's `WriteTimeoutHandler`, which times each write as a whole rather than each 64 KiB piece, and is off by default as before. `idleTimeoutMillis` gives `RequestReadTimeoutHandler` a separate idle clock. A probe in front of the codec reports a request's first bytes, so the request clock then starts with them, as on the socket server. Without an idle timeout, the request clock still covers the wait, as before. `shutdownTimeoutMillis` is rounded up to whole seconds, at least 1. `maxStreamingConnections` counts only SSE streams on Netty, while the socket servers count WebSockets too.
  - Node.js maps `maxHeaderSize`, `requestReadTimeoutMillis` (`requestTimeout`, with `headersTimeout` kept at or below it), and `idleTimeoutMillis` (`keepAliveTimeout`), and ignores the rest.
- **Shared settings win over backend options**, as `maxContentLength` already did.
- **`Native.options` and `Netty.options` keep only backend-specific options.** Native keeps `backlog`, `workerThreads`, `handlerTimeoutMillis`, and the per-read `readTimeoutMillis`. Netty keeps the native transport, handler executor threads, and the shutdown quiet period and hook. The moved setters are removed without deprecation aliases, as Uni does before 1.0. `NativeServerConfig` and `NettyServerConfig` keep every field for code that builds them directly. Both gain the new fields: `shutdownTimeoutMillis` on Native, and `idleTimeoutMillis` and `writeTimeoutMillis` on Netty.
- **The built-in JVM server still has no options type of its own.** Everything it can tune is a shared setting.

## Alternatives

- **A `JvmOptions` for the built-in server:** it would split the settings again, so moving between the two JVM backends would mean rewriting them.
- **Keep the all-three-backends rule:** Node.js lacks write timeouts, decompression, and stream caps, so the built-in server would stay untunable.
- **Fail at `start()` when a backend can't apply a setting:** the same config would then start on one platform and fail on another, which defeats a shared config. The Scaladoc states the gaps instead.

## Consequences

- The HTTP Garden echo app sets `noRequestDecompression` once in shared code, so the built-in JVM server no longer needs its Content-Encoding exception in the Garden run.
- Code that called the removed `Native.options` or `Netty.options` setters moves the call to `Http.server`.
