# RPC error responses omit stack traces unless detailedErrors is on

- Status: Accepted
- Date: 2026-10-30
- Plan: [`plans/2026-10-30-rpc-no-stack-trace-by-default.md`](../plans/2026-10-30-rpc-no-stack-trace-by-default.md)

## Context

Uni RPC inherited Airframe RPC's rule that an `RPCException` reports its stack trace for every status except `UNAUTHENTICATED_U13` and `PERMISSION_DENIED_U14`. A malformed request therefore returned `MethodCodec`, `RPCDispatcher`, and server-loop frames to any client, even with `RPCDispatcher`'s `detailedErrors = false`, which hid details only for unexpected (non-`RPCException`) exceptions. Clients can't use the frames, and they reveal class names, file names, and line numbers.

## Decision

- **Serialization omits frames by default.** `RPCException.shouldReportStackTrace` is `includeStackTrace.getOrElse(false)`, so `toMessage`, `toJson`, `toMsgPack`, and `toResponse` leave out `stackTrace`. The default lives in the exception, not the dispatcher, so every transport (Netty `RPCHandler`, Electron IPC, NativeServer/Node handlers that call `toResponse` themselves) is covered.
- **`detailedErrors` is the single opt-in.** `RPCDispatcher` serializes errors with a package-private `toResponse(detailedErrors)`. In detailed mode it includes frames when the status allows them (`RPCStatus.shouldReportStackTrace`, false for auth errors) and the exception does not say otherwise.
- **An explicit `includeStackTrace` wins in both modes.** `noStackTrace` (`Some(false)`) always hides frames; `Some(true)` always includes them, as a deliberate per-error choice. No public `withStackTrace` was added: `copy` builds a new `Throwable` with the caller's stack, so it would report the wrong frames.
- **The wire format is unchanged.** `stackTrace` stays an optional field, and the client still restores it when present, so bodies from older Uni servers and Airframe RPC decode as before.

## Consequences

- Behavior change: clients that read `getStackTrace` from a server error now get the client-side frames (where `fromJson`/`fromResponse` ran) unless the server enables `withDetailedErrors(true)`.
- `RPCStatus.shouldReportStackTrace` now means "eligible in detailed mode". MCP's internal dispatcher keeps `detailedErrors = true` for messages, but MCP forwards only the message, so no frames reach MCP clients.
