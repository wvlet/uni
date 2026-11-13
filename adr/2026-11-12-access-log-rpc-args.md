# rpc_args in the HTTP access log

## Context

Airframe's `http_server` log records the decoded `@Endpoint`/RPC method arguments as `rpc_args` and leaves out every parameter or field annotated with `@secret`. Apps migrating from Airframe, such as td-llm-proxy, rely on this to log `model` and `max_tokens` while keeping prompts out. `HttpAccessLogFilter` logged no arguments, and its `extraEntries` callback sees only the raw `Request`, so it could not reach the decoded values. The log writer encodes values it doesn't know with `AnyWeaver`, and `AnyWeaver` falls back to `toString`, which prints every field of a case class, `@secret` ones included.

## Decision

- `RouterHandler` and `RPCDispatcher` record the call in the filter's existing request-scoped `LogContext`, the attribute behind `HttpAccessLogFilter.addEntry`, through `private[http] recordRPCCall`. They record once when the route resolves and again after the arguments decode, before the method runs. A throwing method still logs its arguments. A decode failure logs `rpc_method` without `rpc_args`. Without an access log filter, the hook does nothing.
- `RPCArgsLog` walks the arguments by Surface and rebuilds every container it logs. It drops `@secret` parameters and fields, and skips `Request` and `RequestAttributes`. It recurses into case classes, `Seq`, `Option`, `Map` values, and arrays; Airframe misses the last two.
- The walk fails closed. Only scalars pass through unchanged: strings, numbers, booleans, `ULID`, `UUID`, `Instant`, Java enums, and Scala enum cases without fields. Anything else is logged as its class name and never reaches `toString`. That includes a value typed as a sealed trait, a case class under `Any`, an enum case with fields, `Either`, tuples, and Java collections.
- `Array[Byte]` is logged as `[N bytes]`, because request bodies and uploads would otherwise fill the log line.
- `rpc_args` is on by default for Airframe parity; `HttpAccessLogConfig.noRpcArgs()` turns it off. The `rpc_*` name fields are always logged.

## Alternatives

- A thread-local, as Airframe does: it breaks with async `Rx` handlers and on Scala.js. The request attribute already carries per-request state.
- A field on `Response`: lost when the controller throws, and every response would carry log-only state.
- Passing the arguments to `extraEntries`: every app would have to reimplement the `@secret` walk, and the default would log nothing.
- Logging opaque values with `toString` (Airframe's behavior): a `@secret` field in a sealed-trait subtype would leak. Uni's Surface can't resolve the runtime subtype on every platform.
