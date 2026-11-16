# RPCStatus becomes ServiceStatus in wvlet.uni.http

- Status: Accepted
- Date: 2026-11-16
- Plan: [`plans/2026-11-16-service-status-rename.md`](../plans/2026-11-16-service-status-rename.md)

## Context

`RPCStatus` (from Airframe RPC, modeled on gRPC's `Code`) is a general server error vocabulary: a retry class (S/U/I/R), stable integer codes, and a mapping to and from `HttpStatus`. Electron IPC and MCP already used it, but the `RPC` prefix and the `wvlet.uni.http.rpc` package suggested it only fits RPC endpoints. It had already shipped in v2026.1.x.

## Decision

- **Name: `ServiceStatus`**, with `ServiceStatusType`, `ServiceException`, `ServiceErrorMessage`, and `ServiceStackTraceElement`, so `ServiceStatus.NOT_FOUND_U5.newException("...")` reads as the outcome of a service call on any transport. `ErrorCode` clashes with `HttpErrorCode` and MessagePack's `ErrorCode` (and `ErrorCode.SUCCESS_S0` reads oddly), `StatusCode` is easy to confuse with the HTTP status code, and `Status` is too generic under wildcard imports.
- **The whole family moves together.** Keeping `RPCException` would make non-RPC servers throw an RPC-named exception, and `ServiceErrorMessage` refers to the stack trace element, so it moves too and `wvlet.uni.http` doesn't depend on `http.rpc`.
- **Package: `wvlet.uni.http`**, next to `HttpStatus`, which it maps to. Only RPC-specific types (`RPCRouter`, `RPCClient`, `RPCDispatcher`) stay in `http.rpc`.
- **Old names stay as `@deprecated` type and val aliases** in `wvlet.uni.http.rpc` (since 2026.1.42). Imports, constructors, companion calls, and `case RPCStatus.X =>` patterns keep compiling.
- **The wire format is unchanged:** the `X-RPC-Status` header, codes, `_U1`-style code names, and JSON/MessagePack fields. Older Uni and Airframe RPC peers keep interoperating.

## Consequences

- Source compatibility only: a library compiled against the old `wvlet.uni.http.rpc.RPCException` class must be recompiled, since type aliases don't restore the old class files.
- The HTTP router didn't yet turn a thrown `ServiceException` into its mapped response; [a follow-up](2026-11-16-server-service-exception-response.md) added that for every server.
