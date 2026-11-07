# JSON Schema discriminator name

## Goals

- The schema MCP and OpenAPI publish for a sum type names the same discriminator field the wire format uses.
- Close the limitation #880 listed: `JsonSchema` hard-codes the default name (`"@type"`).

## Background

- `JsonSchema.enumCasesSchema` reads `WeaverConfig().discriminatorFieldName`, so it always emits `"@type"` in each case's `properties` and `required`.
- **No server takes a WeaverConfig today.** `MCPServer`, `RPCRouter`/`MethodCodec`, `HttpRequestMapper`, and `ResponseConverter.toResponse(result)` all encode and decode with the default `WeaverConfig()`. A custom name can only come from code that calls `Weaver.toJson(v, config)` itself.
- So today the published schema already matches what every built-in server puts on the wire. The mismatch #880 described can't happen yet; it would appear once a server lets users set a WeaverConfig.
- OpenAPI output has no `discriminator.propertyName` yet. Enum cases are inline `oneOf` variants, and an OpenAPI discriminator only maps to `$ref`'d component schemas.

## Design

Lane: **Standard**. The brief assumed a server-level WeaverConfig that doesn't exist, so the scope needs your call.

Recommended (A): internal plumbing, no change in behaviour.
- Add `discriminatorFieldName` to `JsonSchema.Options` (it is `private[uni]`), defaulting to `WeaverConfig().discriminatorFieldName`.
- MCP and OpenAPI keep the default, so their output stays the same.
- Tests: the default produces `"@type"`, and a custom name appears in each case's `properties` and `required`.
- Update the ADR's follow-ups: the schema follows the server's config once servers accept one.

Larger option (B): server-level WeaverConfig.
- Add `withWeaverConfig` to `MCPServer` and the RPC/HTTP router and server, and use that config for request decoding, response encoding, the RPC client, and schema generation.
- This adds public API and changes encoding paths. It deserves its own PR and ADR.

## Alternatives and Why Not?

- Add `OpenAPIConfig.withDiscriminatorFieldName` alone: servers would keep sending `"@type"`, so the published document would be wrong.
- Emit `discriminator.propertyName` on inline `oneOf`: tools can't map inline variants. That needs each case as a `$ref` component, which is a separate change.
