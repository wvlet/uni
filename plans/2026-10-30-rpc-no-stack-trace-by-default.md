# Hide RPC stack traces from clients by default

## Goals

- RPC error responses carry no `stackTrace` unless the developer opts in, on every platform and transport.
- One switch to opt in: the existing `detailedErrors` (`RPCDispatcher.withDetailedErrors`, netty `RPCHandler`).
- Clients keep decoding error bodies that do carry `stackTrace` (older servers, Airframe RPC).

## Background

- `RPCException.toMessage` includes frames when `includeStackTrace.getOrElse(status.shouldReportStackTrace)`, and every status except `UNAUTHENTICATED_U13`/`PERMISSION_DENIED_U14` defaults to true (inherited from Airframe RPC).
- So a malformed request (`INVALID_REQUEST_U1` from `MethodCodec.decodeParams`) returns decoder and server frames to any client, even with `detailedErrors = false`.
- Every transport serializes through `RPCException` (`RPCDispatcher` → netty `RPCHandler`, Electron, user-wired NativeServer/Node handlers), so fixing the serializer covers all of them. MCP only forwards `message`, never frames.

## Design

Lane: Standard — changes a default at a security boundary; direction pre-approved in the request.

- `RPCException.shouldReportStackTrace` defaults to `false`: `toJson`, `toMsgPack`, `toResponse` omit frames unless asked. Code that calls `toResponse` directly (custom Native/Node handlers) is safe too.
- `RPCDispatcher` with `detailedErrors = true` includes frames for `RPCException`s, still following `RPCStatus.shouldReportStackTrace` (auth errors stay hidden) and `noStackTrace`.
- `RPCStatus.shouldReportStackTrace` keeps its meaning but now applies only in detailed mode.
- An explicit `includeStackTrace = Some(true)` on an exception still includes frames everywhere; documented as a deliberate per-error choice. `noStackTrace` always wins.
- Client decoding is unchanged: `stackTrace` stays optional and is restored when present.
- Tests (JVM, no mocks): default client/server errors through the dispatcher omit `stackTrace`; detailed mode includes it; auth errors stay hidden; a literal old-server body with `stackTrace` parses. Run the Native/JS RPC suites.
- ADR records the default change; docs (`docs/http/rpc.md`, skill `references/rpc.md`, plugin version bump).

## Alternatives and Why Not?

- **Flip only `RPCStatus.shouldReportStackTrace` to false.** Leaves no opt-in path through `detailedErrors`; status-based defaults are what caused this.
- **Strip frames only in `RPCDispatcher`.** Direct `e.toResponse` calls in user handlers would still leak.
- **Add a public `withStackTrace`.** `copy` creates a new Throwable with a new trace; the constructor's `includeStackTrace` already covers the rare case.
