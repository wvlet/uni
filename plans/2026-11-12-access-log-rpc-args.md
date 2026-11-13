# Record rpc_args with @secret masking in http_server access logs

## Goals

- `HttpAccessLogFilter` entries gain `rpc_interface`, `rpc_class`, `rpc_method`, and `rpc_args` (the decoded method arguments) for both `@Endpoint` routes and RPC calls.
- `@secret` parameters and fields are dropped from `rpc_args` the way Airframe drops them. td-llm-proxy keeps today's log contents (`model`, `max_tokens`, … logged; `messages`, `system`, `tools` hidden) after it leaves Airframe.

## Background

- Airframe stores the decoded arguments in a thread-local and walks them by `Surface` when writing the entry (`HttpLogs.extractRpcArgLog`). It drops `isSecret` entirely (no key, no placeholder), recurses into case classes, `Seq`, and `Option`, skips `Request`/`HttpContext`, and wraps each field in `Try`.
- Uni logs no arguments today. `extraEntries` sees only the raw `Request`, so it cannot reach the decoded arguments.
- The filter already puts a mutable `LogContext` on the request as an attribute, and that attribute flows through every inner filter to the handler. `addEntry` uses it today.
- Two places decode arguments: `RouterHandler` (uni-netty, `@Endpoint` routes) and `RPCDispatcher` (Netty RPC, Native/JS servers, MCP, Electron).
- `AnyWeaver` falls back to `toString` for values it doesn't know. An object we don't walk would be logged through its `toString`, secrets included, so the walk must cover every container a secret can hide in.

## Design

Lane: Standard. It adds a public config API and changes what every access log entry contains.

- **Carrier.** `RouterHandler` and `RPCDispatcher` record the route's method surface, controller surface, and arguments in the existing `LogContext`, through a `private[http]` hook. There is no thread-local and no new field on `Response`. Without an access log filter, the hook does nothing.
- **When.** They record right after the route resolves and again after the arguments decode, before the method is invoked. A throwing controller still gets `rpc_args`. A request whose decoding fails still gets `rpc_method` and no `rpc_args`.
- **Rendering.** The filter turns the recorded call into fields when it writes the entry. The `SSE` stream entry reuses the same fields.
- **Masking rules** (ported from Airframe):
  - Drop `isSecret` parameters and fields.
  - Recurse into case classes, `Seq`, `Option`, and also `Map` values and `Array` elements, which Airframe misses.
  - Skip `Request` and `RequestAttributes` arguments.
  - Keep `ULID` as its value.
  - A failure on one field drops only that field.
- **Opaque values.** A value whose surface has no fields and is not a scalar or a known container, such as a sealed trait with a `@secret` field in a subtype, is logged as its class name rather than its `toString`. This fails closed and is stricter than Airframe.
- **Field names.** For RPC, `rpc_interface` is the RPC trait and `rpc_class` is the implementation class. For `@Endpoint`, both are the controller class (Airframe's convention). `rpc_args` is left out when it is empty.
- **Switch.** On by default for Airframe parity, with `HttpAccessLogConfig.noRpcArgs()` / `withRpcArgs()` to turn it off or on. The `rpc_*` name fields stay even when `rpc_args` is off.
- **Tests:**
  - Nested `@secret` inside `Seq`, `Option`, and `Map`.
  - A `Request` argument is skipped.
  - Arguments are logged when the controller throws, and the entry survives a decode failure.
  - Both the RPC path and the `@Endpoint` path (Netty) are covered.
  - `noRpcArgs()` turns logging off.
- **Docs.** Update `docs/http/server.md`. `for-ai-agents.md` and the uni skill don't cover the access log, and no API is renamed or removed, so they stay as they are.

## Alternatives and Why Not?

- **Thread-local, as Airframe does.** Breaks with async `Rx` handlers and on JS. The request attribute already carries per-request state.
- **A field on `Response`.** Lost when the controller throws, and it makes every response carry log-only state.
- **Pass the arguments to `extraEntries` and let the app mask them.** Every app would have to reimplement the `@secret` walk, and the default would log nothing.
- **Opt-in by default.** Safer for existing Uni users, but td-llm-proxy and other Airframe migrants would silently lose fields. The field names already mirror Airframe's `HttpLogger`.
