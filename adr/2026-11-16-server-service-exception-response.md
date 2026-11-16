# Servers render a thrown ServiceException as its mapped response

- Status: Accepted
- Date: 2026-11-16
- Plan: [`plans/2026-11-16-router-service-exception.md`](../plans/2026-11-16-router-service-exception.md)

## Context

After [`ServiceStatus`](2026-11-16-service-status-name.md) became a general server error vocabulary, only `RPCDispatcher` turned a thrown `ServiceException` into its HTTP response. A `Router` endpoint (served by Netty's `RouterHandler`) or any other `Http.server` handler that threw `NOT_FOUND_U5` answered a generic 500, and the server logged it as a failure with a stack trace.

## Decision

- **The mapping lives in `HttpServerSecurity.errorResponse`**, the one renderer that Netty, NativeHttpServer, NodeHttpServer, NodeWebSocket, and `CorsFilter` use for an exception no filter recovered from. A `ServiceException` becomes `toResponse(detailedErrors)`: `status.httpStatus`, the `X-RPC-Status` header, and the `ServiceErrorMessage` JSON body. Every other exception stays a generic 500. One change covers routers, plain handlers, and every platform; catching in `RouterHandler` would have covered only Netty routers and hidden the exception from user filters.
- **`RouterHandler` still propagates the original exception**, so a user `RxHttpFilter` can `recover` from it before the server renders it.
- **`detailedErrors` is the server's existing setting** (`Http.server.withDetailedErrors`, `NettyServerConfig.withDetailedErrors`), with no router-level flag. Stack traces follow [the RPC opt-in rule](2026-10-30-rpc-stack-traces-opt-in.md) unchanged. `CorsFilter` renders with `detailedErrors = false`, as it does for 500s.
- **A 4xx `ServiceException` is logged at debug**, a 5xx still at `warn`. The check (`HttpServerSecurity.isClientError`) sits at each call site, not in a logging helper, so log records keep the caller's source location.
- **`HttpException` is not mapped.** It is the HTTP client's error type and may carry an upstream response, which the server must not echo. Server code that wants a status throws a `ServiceException` or returns a `Response`.

## Consequences

- Behavior change: endpoints that threw a `ServiceException` and relied on a 500 now answer with its mapped status.
- Browser clients on another origin read `X-RPC-Status` only when the CORS policy exposes it (`withExposedHeaders("X-RPC-Status")`); it is not added automatically.
