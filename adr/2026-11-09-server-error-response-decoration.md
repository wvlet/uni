# Filters decorate the server's generic 500 through a per-request holder

## Context

When a handler fails and no filter recovers, the server renders a generic 500 itself, after every filter has returned (`HttpServerSecurity.errorResponse`; see `2026-10-14-http-server-secure-defaults.md`). A filter that adds headers with `map` never sees that response. For `CorsFilter` this meant a cross-origin browser app got a CORS error instead of the 500 (a known limitation of #886).

## Decision

- **The server owns a per-request holder.** Netty, Node.js, and Native each create a `private[http]` `ServerErrorResponse` and attach it to the request under a `RequestAttribute` before calling the handler. A filter registers a `Response => Response` decoration on it. The server applies the decorations wherever it renders a handler failure: a synchronous throw from `handle`, or an `OnError`. This is the same pattern `HttpAccessLogFilter` uses with `LogContext` to pass data out of inner filters.
- **The failure passes through unchanged.** The server log and an outer `HttpAccessLogFilter` keep the handler's exception (`exception_class`), and `detailedErrors` still decides the body.
- **`CorsFilter` registers exactly what it adds to a successful response.** An allowed origin gets `Allow-Origin`, credentials, `Expose-Headers`, and `Vary: Origin`. Any other request gets only `Vary: Origin`. The 500 therefore opens no access that a successful response doesn't already give.
- Rejected: wrapping the exception in a type that carries headers (outer filters and the access log would see the wrapper), recovering inside `CorsFilter` (it hides the exception and ignores `detailedErrors`), and `addSuppressed` (it mutates the user's exception and does nothing when suppression is disabled).

## Consequences

- The holder is `private[http]`, so this adds no public API. Exposing it to user filters is a later, separate decision.
- Not covered: a 500 for a response with invalid (CR/LF) headers, a failure inside an SSE stream after the headers are sent, the Native handler-timeout 503, and WebSocket upgrades. CORS doesn't apply to WebSockets.
- A new server backend must attach the holder and apply it on handler failures, or `CorsFilter` errors silently lose their CORS headers on that backend.
