# MCP server (`wvlet.uni.mcp`): a thin JSON-RPC stdio layer over the existing RPC dispatcher

Date: 2026-07-20 · PR: [#659](https://github.com/wvlet/uni/pull/659)

## Context

Uni wants to be the easiest way to build MCP (Model Context Protocol) servers in Scala, with the
Scala Native single-binary and Node.js distribution stories as the differentiator. MCP is JSON-RPC
2.0 over stdio; a server must expose "tools" with JSON Schemas derived from method signatures. Uni
already had Surface (compile-time method metadata), Weaver (JSON codecs), and the transport-neutral
`RPCDispatcher` shared by HTTP and Electron IPC.

## Decisions

1. **Package inside `uni`, not a new module.** Module splits in this repo exist for dependency
   isolation (uni-netty ⇒ Netty). MCP adds zero dependencies, so it lives at `wvlet.uni.mcp` like
   `wvlet.uni.electron` does.

2. **`tools/call` dispatches by synthesizing a uni `Request`** (`POST /{service}/{method}`, body
   `{"request": arguments}`) and running it through the shared `RPCDispatcher` — the Electron IPC
   pattern. Param decoding (canonical names, defaults, `Option`, `Rx[A]` returns) and error
   encoding stay in one tested code path. The cost — one serialize + re-parse of the arguments per
   call — was accepted deliberately; do not "optimize" it by forking a parallel decode path.

3. **Error taxonomy**: `INVALID_REQUEST_U1`/`INVALID_ARGUMENT_U2` from the dispatcher map to
   JSON-RPC `-32602` (protocol error); every other failure becomes an `isError: true` *result*, per
   the MCP spec's distinction between protocol errors and tool execution failures.

4. **String results are unwrapped.** The RPC layer JSON-encodes a `String` result as a quoted
   string; MCP clients expect plain text, so `toolCallResponse` parses a top-level JSON string back
   to its raw value before building the text content. Structured results stay as JSON text.

5. **stdout is reserved and defended.** Protocol responses are written through the stdout captured
   at `serve` start; `System.out` is pointed at stderr for the server's lifetime (all three
   platforms), so `println` in tool code cannot corrupt the framing. On Scala.js the default log
   handler (`JSConsoleLogHandler` → `console.log` → stdout) is replaced with a stderr
   `ConsoleLogHandler` for the same reason. Responses are compact single-line JSON (`.toJSON`,
   never `JSON.format`).

6. **Descriptions come from a `@description` annotation**, read via Surface's compile-time
   annotation capture — Surface does not capture Scaladoc, so doc comments cannot feed tool
   descriptions.

7. **Enum schemas are plain `string`.** `EnumSurface` carries only a decode function, not the case
   names, and enum values cannot be enumerated reflectively on JS/Native. Extending `EnumSurface`
   to capture case names at compile time is the future fix if `enum` arrays are wanted.

8. **Fail fast at `withTools`**: tool-name collisions (names are flat across services), names
   outside `[a-zA-Z0-9_-]{1,128}`, and param/return types `Weaver.fromSurfaceOpt` cannot encode all
   throw at registration, not at call time. Note the case-class constructor bypasses this; the
   documented API is `withTools`/`withRouter`.

## Surface/RPC fixes the work surfaced

- **Default-argument getters leaked from `Surface.methodsOf`**: `f$default$1` neither starts with
  `$` nor carries the Synthetic flag, so it passed `localMethodsOf`'s filters and became an RPC
  route (and an eagerly-built codec in `RPCClient.build`). The exclusion now lives in
  `CompileTimeSurfaceFactory.localMethodsOf` (`!name.contains("$default$")`) so every `methodsOf`
  consumer is clean by construction — filter there, not in callers.
- **Trait method defaults need the owner instance**: they compile to instance methods, so
  `getDefaultValue` alone returns `None` for them. `MethodParameter.resolveDefaultValue(owner)`
  combines the static default with the instance-evaluated one; `MethodCodec.decodeParams` takes the
  service instance and `RPCDispatcher` passes it. Before this, omitting a defaulted RPC argument
  failed with "Missing required parameter".

## Consequences

- MCP behavior is contractually tied to RPC dispatcher behavior; changes to `MethodCodec`'s
  envelope or error statuses propagate to MCP clients.
- Per-call JSON round-trip overhead in `tools/call` (accepted; see decision 2).
- Enum tool parameters advertise no value list to clients until `EnumSurface` learns case names.
- Deferred, by scope: resources/prompts (natural fit: `wvlet.uni.plugin` extension points), HTTP
  transport, `structuredContent`/`outputSchema`, `listChanged`, pagination.

## Worked examples

- `MCPServerTest` / `JsonSchemaTest` (shared, run on all three platforms) pin the protocol shape;
  `StdioTransportTest` (JVM) runs a scripted stdio session through the real transport.
- Startup guide: `docs/mcp/index.md`.
