# OpenAPI parity with sbt-airframe for td-llm-proxy

## Goals

- #743 added OpenAPI generation, but a comparison with sbt-airframe's generator (airframe-http-codegen `OpenAPIGenerator`) found gaps that would visibly change td-llm-proxy's `doc/openapi.yaml`. Close the ones uni can support without misdocumenting the server.
- MCP tool schemas share `mcp.JsonSchema` and must not change (tool schemas cost tokens).

## Background

- Airframe takes the operation `description` from the method's `@description`; the proxy's long endpoint descriptions live there. uni reads only `@Endpoint(description = ...)`.
- Airframe schemas carry `format` (`int32`, `int64`, `float`, `double`) and field `default` values; uni's do not.
- Airframe lists 400, 500, and 503 on every operation. uni's `OpenAPIConfig.withErrorResponses` can do that, but the sbt task has no setting for it.
- Airframe regenerates the document on `package`; `uniOpenAPI` runs only when invoked.
- Not closable here:
  - Airframe lists `application/x-msgpack` for responses because its server negotiates MessagePack responses. uni's `ResponseConverter` and `RouterHandler` only produce JSON, so documenting MessagePack responses would be wrong.
  - Airframe emits class-level `@description`. uni's Surface does not capture class annotations, so this needs a Surface change.

## Design

Lane: **Standard** (new public settings); scope approved in conversation.

One PR, `feature/openapi-airframe-parity`:

1. **Operation description:** `@Endpoint(description)` when non-empty, else the method's `@description`. The annotation is matched by simple name and its `value`, so both `wvlet.uni.mcp.description` and Airframe's `@description` (kept during the migration) work.
2. **Schema options:** `JsonSchema` takes options. OpenAPI turns on `format` for Int/Long/Float/Double and `default` for case-class fields whose default is a primitive or String. MCP keeps the current output.
3. **sbt:** `uniOpenAPIErrorResponses: Seq[Int]` (default `Seq(400, 500)`), passed as `--error-responses`.
4. **Generate on package:** `uniOpenAPI` returns `Seq[File]` and is a no-op when `uniOpenAPIRouters` is empty. `Compile / packageBin` depends on it, as in sbt-airframe.

## Alternatives and Why Not?

- **List MessagePack responses anyway**, to match Airframe's output: the document would promise a format the server never sends. It would need Accept-based response encoding in the router first.
- **Fall back to the method name for a missing description**, as Airframe does: it duplicates `summary`.
- **Always emit formats and defaults (MCP too):** it changes MCP tool schemas and their token cost for every uni MCP server.
