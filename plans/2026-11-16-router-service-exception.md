# Router endpoints map a thrown ServiceException to its HTTP response

## Goals

- A controller method in a `Router` that throws `ServiceStatus.NOT_FOUND_U5.newException("...")` should answer 404 with the `X-RPC-Status` header and the `ServiceErrorMessage` JSON body, as RPC endpoints already do.
- Today it answers a generic 500 ("Internal Server Error"), so the status vocabulary renamed in #922 is useless outside RPC.

## Background

- `RouterHandler` (uni-netty, the only router dispatcher) returns controller and filter exceptions as a failed `Rx`, so an enclosing `RxHttpFilter` can `recover`. Only `HttpRequestMappingException` (400/415) is mapped there.
- An exception no filter recovers reaches the server, which renders it with `HttpServerSecurity.errorResponse(e, detailedErrors)`. Netty, NativeHttpServer, NodeHttpServer, NodeWebSocket, and `Cors` all call this one function.
- `detailedErrors` is already a server setting: `HttpServerConfig.withDetailedErrors` / `NettyServerConfig.withDetailedErrors`. `RouterHandler.withDetailedErrors` is deprecated and unused.
- ADR 2026-10-30: frames are omitted unless `detailedErrors`; auth statuses (U13/U14) never report them; an explicit `noStackTrace` wins.

## Design

Lane: **Standard** — changes the response that existing HTTP endpoints send for a thrown exception.

- **User flow:** a server author throws a `ServiceException` from a router endpoint (or any `RxHttpHandler`/filter); the client gets `status.httpStatus`, `X-RPC-Status: <code>`, and the JSON error body, and `ServiceException.fromResponse` on the client restores it.
- **Mapping lives in `HttpServerSecurity.errorResponse`:** a `ServiceException` becomes `e.toResponse(detailedErrors)`; every other exception keeps the generic 500. One change covers Netty, Native, and Node servers and plain `withHandler` handlers too, not just routers.
- **Filters still see the original exception:** `RouterHandler` keeps propagating it, so a user filter can `recover` before the server renders it.
- **`detailedErrors` comes from the server config**, the same flag that already governs 500 messages. No new router-level setting; the deprecated `RouterHandler.withDetailedErrors` stays unused.
- **Stack traces follow the RPC ADR unchanged:** omitted by default, included only with `detailedErrors` and an eligible status, `noStackTrace` always hides them.
- **`HttpException` with a status is out of scope.** It is the HTTP client's error type, and it carries the upstream response; mapping it on the server would leak that response. A server author who wants a status throws a `ServiceException` (or returns a `Response`).
- **Logging (open):** the servers `warn` with a stack trace on every exception that reaches them, so a thrown `NOT_FOUND_U5` would log as a server error. Proposed: log a `ServiceException` whose status is a 4xx at debug, through one shared helper next to `errorResponse`; 5xx keeps `warn`.
- Tests: a router endpoint throwing `ServiceException` over Netty (status, header, body, no frames by default, frames with `detailedErrors`, none for U13), plus a unit test of `errorResponse`.
- An ADR records the decision; `docs/` for the router and the for-ai-agents page mention throwing `ServiceException` for an error status.

## Alternatives and Why Not?

- **Catch in `RouterHandler`:** covers only Netty routers, needs its own `detailedErrors` (the deprecated setter), and would hide the exception from user filters that recover from it.
- **A shared `RxHttpFilter` added by default:** same effect as the server-level change but adds an ordering question with user filters and is skipped by servers that bypass the filter chain (WebSocket upgrade).
- **Map `HttpException` too:** see above; can be a separate change if requested.
