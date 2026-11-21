# Claude on Bedrock through the Anthropic Messages API

## Goals

- Call Claude on Amazon Bedrock with the same Anthropic Messages request, response, and SSE stream that `AnthropicClient` already speaks, with no Bedrock-specific wire format.
- Drop Uni's Bedrock Converse model (`wvlet.uni.ai.bedrock`, about 1.5k lines) and the InvokeModel event-stream path, which exist only to reach Claude on Bedrock.
- Direction approved by the user ("yes in a new session") after the proposal: add the new Bedrock host, remove the `wvlet.uni.ai.bedrock` package and the InvokeModel path.

## Background

- Bedrock now serves the Anthropic Messages API at `/anthropic/v1/messages` on `bedrock-runtime.{region}.amazonaws.com` ([AWS docs](https://docs.aws.amazon.com/bedrock/latest/userguide/inference-messages-api.html)). It is also on `bedrock-mantle.{region}.api.aws`, but AWS is deprecating mantle, so Uni does not support it (user decision).
  - The body is a standard Messages request with `model` in it (e.g. `us.anthropic.claude-sonnet-5`), plus the `anthropic-version: 2023-06-01` header.
  - Auth is a Bedrock API key in `x-api-key`, or SigV4 with service `bedrock`.
  - Streaming is not described on the page, but the Anthropic SDK streams from it with a plain `base_url` change. That means standard Anthropic SSE.
  - `count_tokens` is documented on mantle only, so bedrock-runtime has no documented Anthropic `count_tokens`.
- Bedrock also serves an OpenAI-compatible API at `bedrock-runtime.{region}.amazonaws.com/openai/v1` for its OpenAI models. `OpenAIClient(baseUrl = ...)` already reaches it.
- PR #1057 added Converse because it was the only way to reach every Bedrock model with one shape. A precedent: `plans/2026-06-20-remove-agent-bedrock.md` dropped Converse once before because Bedrock work was moving to Mantle.

## Design

Lane: Standard. It removes a public package and changes Bedrock auth headers. The user already approved the direction (see Goals).

- `AnthropicHost.Bedrock(region, auth = BedrockAuth.default, endpoint = None)` posts Messages requests to `/anthropic/v1/messages`. The Anthropic codecs, `stream` (SSE), and converters are unchanged, so Bedrock behaves like the Direct host.
  - `endpoint` overrides the host, for VPC endpoints and tests. There is no mantle option, because AWS is deprecating mantle.
  - `BedrockAuth.ApiKey` goes in `x-api-key`. `BedrockAuth.Credentials` signs with SigV4 through the shared Bedrock request helper.
  - `anthropic-beta` stays a header, as on the Anthropic API.
- `countTokens` stays Anthropic-API only. On Bedrock it fails at once with a clear message instead of sending an undocumented request.
- Remove the InvokeModel path (event-stream `chunk` decoding) from `AnthropicClient`.
- Remove `wvlet.uni.ai.bedrock` (Converse model, converter, client, event-stream decoder) and its tests. Also remove `ProviderMetadata.Bedrock`, the `cache_control` ↔ `cachePoint` mapping, and the Bedrock tool-id sanitizer. Keep `BedrockAuth`.
- For a non-Anthropic Bedrock model, use `OpenAIClient` with `baseUrl = https://bedrock-runtime.<region>.amazonaws.com/openai/v1` and the Bedrock API key as the bearer token.
- Docs, ADR, and the `uni` skill: the Bedrock row becomes "Claude on Bedrock via the Anthropic API". Amend the 2026-11-21 LLM vendor APIs ADR, merged today, rather than add a second one. The ADR records what is lost:
  - Converse-only features, such as the Guardrails config and `additionalModelRequestFields`.
  - Bedrock models that offer neither an Anthropic nor an OpenAI API (e.g. some Llama, Mistral, and Titan models).
  - Serving Converse to clients from other vendors (proxying).
- Tests: a JVM loopback case for the new host. It checks the path, `x-api-key`, SigV4 headers (service `bedrock`), and an SSE stream.

## Alternatives and Why Not?

- Keep Converse alongside the new host: about 1.5k lines and a fourth wire model to maintain, only for models that the OpenAI endpoint mostly covers.
- Keep InvokeModel as a fallback: it needs the AWS event-stream decoder for the same Claude models that the new route serves with plain SSE.
