# uni-ai: wire-exact LLM vendor models and a neutral model for conversion

## Context

Scala apps call the Anthropic Messages, OpenAI Responses and Chat Completions, Gemini `generateContent`, and Bedrock Converse APIs, and proxies serve one vendor's API with another vendor's models (Claude Code on OpenAI models, OpenAI clients on Claude). Each API has its own JSON shape and adds block and event types every few months. The old `uni-agent` chat abstraction was removed in 2026-06; it was a client abstraction, not a wire model.

## Decision

- **Separate module `uni-ai`** (JVM, Scala.js, Native), depending only on `uni`, so the fast-changing surface stays out of the main artifact.
- **One package per wire protocol**, not per host: `wvlet.uni.ai.anthropic` (Anthropic API, Vertex `rawPredict`, Bedrock InvokeModel), `.openai` (Responses, Chat Completions, compatible servers), `.gemini` (AI Studio and Vertex), `.bedrock` (Converse). Shared code and the neutral model sit in `wvlet.uni.ai`.
- **Hand-written `JsonCodec`s on `JSONValue`, not derived Weavers.** Derived codecs write Scala field names and class-name discriminators. They cannot express snake_case keys, key-tagged unions (`{"functionCall": ...}`), omitted absent fields, or unknown-type passthrough. `JsonCodec.weaver` adapts a codec for routers and RPC.
- **Forward compatibility**: every union has `Unknown(raw)`, and top-level requests and responses keep unmodeled fields in `extra`, so a proxy forwards what it does not model.
- **A neutral model in the shape of the Vercel AI SDK provider spec**: typed parts with `providerMetadata` keyed by vendor, stream parts that open, grow, and close blocks by id, `FinishReason(kind, raw)`, and `LlmUsage` in one convention (input includes cache tokens; output includes reasoning). LiteLLM, claude-code-router, and Bifrost instead use Chat Completions as the hub and bolt on side channels for signatures and block order. N vendors need 2N converters, not N².
- **Stateful stream converters, one per stream**: `StreamDecoder` (vendor → neutral) and `StreamEncoder` (neutral → vendor), each with `finish()`. The Chat Completions and Bedrock decoders emit `Finish` only at the end, because usage arrives after the finish chunk or `messageStop`.
- **Cross-vendor reasoning carriers**: OpenAI encrypted reasoning and Gemini thought signatures ride in Anthropic thinking `signature`s, and Anthropic signatures ride in OpenAI `encrypted_content`, with a `uni-ai:<vendor>:` prefix. Clients return these blocks verbatim, so the next turn recovers the data. Gemini function calls in history without a signature get Gemini's documented `skip_thought_signature_validator`.
- **Clients never retry**; a retried completion is billed twice. Errors become `LlmApiException`, which unwraps `HttpMaxRetryException`. Bedrock auth is an API key (bearer) or SigV4 through `wvlet.uni.storage.SigV4`. The model id is encoded once in the URL and twice in the canonical request. Vertex auth uses `GoogleCredentialsProvider` with the cloud-platform scope.
- **Bedrock event-stream** is decoded in pure Scala (prelude, string headers, CRC-32 checks) so it runs on every platform.

## Consequences

- New vendor fields need a codec change to become typed, but they pass through as `extra` or `Unknown` until then.
- Carrier signatures are opaque to clients, but a client that inspects or drops thinking blocks loses cross-vendor reasoning; the next turn then runs without it.
- Not modeled yet: Gemini `partialArgs` streaming (`streamFunctionCallArguments`), OpenAI built-in tool events (kept as `Unknown`), and Anthropic Files/Batches endpoints.
