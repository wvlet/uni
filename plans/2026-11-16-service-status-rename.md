# Rename RPCStatus to a transport-neutral name

## Goals

- `RPCStatus` is a general server error vocabulary: retry class (S/U/I/R), stable integer codes, and a mapping to and from `HttpStatus`. The `RPC` prefix suggests it only fits RPC endpoints.
- Give it a name that HTTP endpoints, Electron IPC, and MCP can use without sounding like an RPC concept, while existing code keeps compiling.

## Background

- The enum lives in `wvlet.uni.http.rpc`. Only RPC code uses it today: the dispatcher, client, Netty `RPCHandler`, Electron channels, and `MCPServer`. The HTTP router ignores it.
- The design comes from Airframe's `RPCStatus` and gRPC's `Code`. The `_U1`-style suffix names and integer codes go over the wire (`X-RPC-Status` header, `RPCErrorMessage.code` and `codeName`), so their values must not change.
- It is already released (v2026.1.x), so a plain rename would break users. There is precedent for `@deprecated` aliases (for example `NodeServer` and `Weaver`).

## Design

Lane: Standard — a public API rename across the http and rpc packages that users will notice.

- **Name: `ServiceStatus`** (with `ServiceStatusType`). `ServiceStatus.NOT_FOUND_U5.newException("...")` reads as "the outcome of a service call", whatever the transport.
- **Exception and message follow it:** `RPCException` becomes `ServiceException` and `RPCErrorMessage` becomes `ServiceErrorMessage`. Otherwise users would throw an `RPCException` from a plain HTTP endpoint.
- **Package: `wvlet.uni.http`**, next to `HttpStatus`, which it maps to. RPC-only types (`RPCRouter`, `RPCClient`, `RPCStackTraceElement`, …) stay in `http.rpc`.
- **Compatibility:** `@deprecated` type and val aliases in `wvlet.uni.http.rpc` (`RPCStatus`, `RPCStatusType`, `RPCException`, `RPCErrorMessage`), so `import wvlet.uni.http.rpc.RPCStatus` and `case RPCStatus.NOT_FOUND_U5 =>` keep compiling, with a warning.
- **Wire format unchanged:** the same `X-RPC-Status` header, codes, code names, and JSON/MessagePack fields. Old clients and servers keep working with new ones.
- **Docs:** update `docs/http/rpc.md`, the book chapter, `for-ai-agents.md`, and the `uni` plugin skill (with a version bump) to the new names, and add an Airframe→Uni row.
- Tests: the existing RPC tests move to the new names, plus one test that the deprecated aliases still compile and pattern-match.
- Out of scope (follow-up): letting HTTP router endpoints turn a thrown `ServiceException` into its mapped response.

## Alternatives and Why Not?

- **`ErrorCode`:** clashes with `HttpErrorCode` and `msgpack.ErrorCode`, and `ErrorCode.SUCCESS_S0` reads oddly.
- **`StatusCode`:** easy to confuse with the HTTP status code it maps to.
- **`Status` (gRPC style):** too generic, and clashes with users' own `Status` types under wildcard imports.
- **`CallStatus` / `ResultStatus`:** neutral, but vaguer than "service" about where the error comes from.
- **Keep `RPCStatus` and only document general use:** costs nothing, but the name keeps steering people away from it.
