# Report stack traces in the RPC error body

## Goals

- Make `RPCException.shouldReportStackTrace` / `noStackTrace` take effect: today they are read by nothing, so the RPC error body never carries a stack trace (wvlet/uni#751).
- Match Airframe's wire shape so services migrating from Airframe (td-llm-proxy first) keep the same error bodies, and clients can see where a server-side failure happened.
- Settle the security policy: which errors expose internal frames to clients by default.

## Background

- Uni's `RPCException` was ported from Airframe with the stack-trace flag but without the field that uses it. `RPCErrorMessage` has only `code`, `codeName`, `message`, `appErrorCode`.
- Airframe's `RPCErrorMessage` adds `stackTrace: Option[Seq[GenericStackTraceElement]]` (`className`, `methodName`, `fileName`, `lineNumber`) and `cause`. `toMessage` fills both only when `shouldReportStackTrace` is true. `RPCStatus.shouldReportStackTrace` is true for every status except `UNAUTHENTICATED_U13` and `PERMISSION_DENIED_U14`. The client restores the frames with `setStackTrace`.
- Observed for `UNAVAILABLE_I2("probe message")`: Airframe 2026.2.5 sends ~20 frames; Uni 2026.1.32 sends `{"code":2002,"codeName":"UNAVAILABLE_I2","message":"probe message"}`.
- Uni servers already have a `detailedErrors` setting (default `false` on JVM, JS, Native). `RPCDispatcher` uses it to replace an unexpected exception's message with "Internal error" before wrapping it as `INTERNAL_ERROR_I0`.
- Stack frames reveal class names, file names, and line numbers, so this is a security boundary.

## Design

Lane: **Standard**. It changes the RPC wire format and decides what internal details reach clients (always-gate: security boundary).

1. Add `RPCStackTraceElement(className, methodName, fileName: Option[String], lineNumber)` in `wvlet.uni.http.rpc`, with `fromJava` / `toJava` helpers. Field names match Airframe's `GenericStackTraceElement`, so the JSON is the same.
2. Add `stackTrace: Option[Seq[RPCStackTraceElement]] = None` to `RPCErrorMessage`. It is placed after `message`, like Airframe, and is `None` (omitted from JSON) when not reported.
3. `RPCException.toMessage` sets `stackTrace` to the exception's frames when `shouldReportStackTrace` is true, and to `None` otherwise.
4. `RPCException.fromJson` / `fromMsgPack` restore the frames with `setStackTrace` when present, so client logs show the server's frames, as in Airframe.
5. `RPCDispatcher.errorResponse`: when `detailedErrors` is false, the wrapped `INTERNAL_ERROR_I0` for an unexpected exception gets `.noStackTrace`. Its frames would point at dispatcher internals anyway, and the server has asked to hide details.
6. Tests: the JSON contains `stackTrace` for `UNAVAILABLE_I2`, has no `stackTrace` for `UNAUTHENTICATED_U13`, `PERMISSION_DENIED_U14`, or `noStackTrace`, frames round-trip through JSON and MsgPack, and the dispatcher hides frames when `detailedErrors=false`.
7. ADR recording the policy, plus a short docs note on `noStackTrace`.

Not included: serializing `cause`. It needs a generic Throwable model (Airframe's `GenericException`) and exposes more. It can follow separately if needed.

## Alternatives and Why Not?

- **Gate every stack trace on the server's `detailedErrors`** (safer by default, differs from Airframe): explicitly thrown RPCExceptions would send no frames unless the server opts in. Migrating services would need to set `withDetailedErrors(true)` to keep Airframe's bodies. This is a reasonable choice if hidden by default matters more than parity. See the open question.
- **Remove the flag and never send frames**: simplest and safest, but it drops the Airframe behaviour the migration relies on.
- **Reuse a generic Throwable codec for `cause` now**: a larger surface and a bigger exposure. Deferred.
