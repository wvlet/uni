# RPC error bodies report stack traces

## Context

`RPCException` was ported from Airframe with `noStackTrace` and `shouldReportStackTrace`, but `RPCErrorMessage` had no field to carry frames, so the flag had no effect (#751). Airframe's error body carries a `stackTrace` array (`className`, `methodName`, `fileName`, `lineNumber`) for every status except `UNAUTHENTICATED_U13` and `PERMISSION_DENIED_U14`, and its client restores the frames. Services migrating from Airframe (td-llm-proxy) depend on that body. Stack frames expose class names, file names, and line numbers to clients, so this is a security decision.

## Decision

- `RPCErrorMessage.stackTrace: Option[Seq[RPCStackTraceElement]]`, filled by `RPCException.toMessage` only when `shouldReportStackTrace` is true. The field names match Airframe's `GenericStackTraceElement`.
- Airframe parity for explicitly thrown `RPCException`s: frames are sent by default per `RPCStatus.shouldReportStackTrace`, and not gated on the server's `detailedErrors`. Use `noStackTrace` to hide them for one error.
- Unexpected (non-`RPCException`) exceptions wrapped by `RPCDispatcher` (and Electron's dispatch fallback) get `noStackTrace` unless `detailedErrors` is on. This keeps the secure default from `2026-10-14-http-server-secure-defaults.md`: no message and no frames. With `detailedErrors` on, the wrapper carries the handler exception's frames, since `cause` is not sent.
- `RPCException.fromJson` / `fromMsgPack` / `fromResponse` restore frames with `setStackTrace`.
- `RPCErrorMessage` serializes with `omitNoneFields`, so unset `stackTrace` / `appErrorCode` keys are omitted rather than written as `null`, matching Airframe.
- `cause` is not serialized. It needs a generic Throwable model and exposes more, so it is deferred.

Rejected: gating all frames on `detailedErrors` (safer, but migrating services would need to opt in to keep Airframe's bodies), and removing the flag entirely (drops behaviour the migration relies on).

## Consequences

- RPC errors thrown by handlers now reveal server code locations to clients by default. Handlers that face untrusted clients should use `noStackTrace` where that matters.
- Error bodies no longer contain `"appErrorCode":null`. Readers treat an absent field as None, so existing uni clients are unaffected.
