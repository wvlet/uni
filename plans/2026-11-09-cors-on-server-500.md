# CORS headers on the server's generic 500

## Goals

- When a handler fails without recovering, a cross-origin browser app should see the 500, not a CORS error. Today `CorsFilter` passes the failed `Rx` through, and the server renders its generic 500 with no `Access-Control-*` headers (known limitation in #886).
- Keep what #886 got right: the exception reaches the server and an outer `HttpAccessLogFilter` unchanged, so the server still logs it and the access log still records `exception_class`.

## Background

- All three backends render the generic 500 through `HttpServerSecurity.errorResponse(e, detailedErrors)`: Netty `NettyRequestHandler`, Native `NativeHttpServer.runHandler`/`awaitRx`, Node `NodeHttpServer.runResponse`/`handleBody`. The server holds the request at each of those call sites, but a filter only sees the request, so a filter can't add headers to that response.
- `CorsFilter` can't recover the failure into a 500 itself: it doesn't know `detailedErrors`, and an outer access log would then record a plain 500 with no exception.
- `HttpAccessLogFilter` already uses a mutable per-request holder in a `RequestAttribute` (`LogContext`) to pass data out of inner filters.

## Design

Lane: **Express**. A fix with a clear diagnosis, and no new public API. It doesn't change which origins are allowed: the 500 gets exactly the headers a successful response from the same request would get.

- Add a `private[http]` per-request holder, `ServerErrorResponse`, in a `RequestAttribute`. Filters register a `Response => Response` decoration on it, and the server applies them to its generic 500.
- Each backend creates the holder, attaches it to the request before calling the handler, and applies it wherever it renders a handler failure: a synchronous throw from `handle` or an `OnError`.
- `CorsFilter` registers the same decoration it applies to successful responses: `Allow-Origin`, credentials, `Expose-Headers`, and `Vary: Origin` for an allowed origin; only `Vary: Origin` for a disallowed origin or no `Origin`. The failure itself passes through unchanged.
- Not covered: a failure inside an SSE stream after the headers were sent, and WebSocket upgrades. CORS doesn't apply to WebSockets.
- Docs: drop the limitation from the `CorsFilter` scaladoc, `docs/http/server.md`, and the `uni` skill's http-server reference. Bump the plugin version.
- Tests: a Netty server test where a failing handler behind `CorsFilter` returns a 500 that carries `Access-Control-Allow-Origin`, and the access log still records the exception class.

## Alternatives and Why Not?

- Wrap the exception in a type that carries the headers: outer filters and the access log would see the wrapper instead of the handler's exception.
- `CorsFilter` recovers the failure into a 500: hides the exception from the access log and server log, and ignores `detailedErrors`.
- Carry the headers in an `addSuppressed` exception: mutates the user's exception, and does nothing when suppression is disabled.
