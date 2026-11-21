# Typed LLM vendor APIs and converters (uni-ai)

## Goals

- Give Scala apps typed request, response, and stream-event models for the LLM APIs they call most: Anthropic Messages, OpenAI Responses and Chat Completions, Gemini `generateContent` (Google AI Studio and Vertex AI), and Amazon Bedrock Converse.
- Cover both non-streaming calls and streaming (SSE, and AWS event-stream for Bedrock) with the same types.
- Ship thin clients on Uni's HTTP client so the types are usable without extra code.
- Convert between vendors, so one app can serve an Anthropic-compatible endpoint (e.g. for Claude Code) backed by OpenAI or Gemini models, or an OpenAI-compatible one backed by Claude.

## Background

- Uni had a `uni-agent` module with chat abstractions; it was removed with Bedrock in 2026-06 (`plans/2026-06-20-remove-agent-bedrock.md`). It was a client abstraction, not a wire-exact model, and is not revived.
- Weaver's derived codecs write Scala field names and class-name discriminators. Vendor APIs need exact names (`max_tokens`, `type: "tool_use"`, Gemini's key-based unions like `{"functionCall": ...}`), reject explicit nulls, and add new block types every few months. Derived codecs cannot express this, so the models use hand-written JSON codecs on `JSONValue`.
- Uni already has the pieces the clients need on all platforms: `HttpAsyncClient.sendSSE`/`sendStreaming`, `SigV4` + `AwsCredentialsProvider`, `GoogleCredentialsProvider`.
- Lessons from existing implementations (read from source, 2026-11):
  - Vercel AI SDK (provider spec v4) uses a neutral model: typed prompt parts, stream parts (`text-start/delta/end`, `reasoning-*`, `tool-input-*`, `finish`), `providerMetadata` per part, `finishReason {unified, raw}`, and usage split into no-cache / cache-read / cache-write and text / reasoning.
  - LiteLLM, claude-code-router, and Bifrost use OpenAI Chat Completions as the hub and bolt on side channels for thinking blocks and signatures; Chat cannot express block order, signatures, or cache breakpoints. We follow the AI SDK shape instead.
  - Usage totals differ: Anthropic and Bedrock input excludes cached tokens; OpenAI and Gemini input includes them. Gemini output excludes thoughts; OpenAI output includes reasoning.
  - Finish reasons: tool calls must be derived (Gemini says STOP, Responses has no stop field); OpenAI Chat sends usage after the finish chunk, so the Anthropic `message_delta` must wait for it; Bedrock sends `messageStop` before `metadata`.
  - Tool-call ids and names need deterministic rewrites (Anthropic `[a-zA-Z0-9_-]`, Bedrock ≤64 chars, OpenAI names ≤64 chars).
  - Gemini accepts full JSON Schema via `parametersJsonSchema`, so schemas pass through unchanged.
  - Cross-vendor reasoning: LiteLLM carries OpenAI `encrypted_content` inside the Anthropic thinking `signature` with a prefix, which Claude Code echoes back verbatim.

## Design

Lane: Standard (new module and public API). The user set the scope and package layout in the goal and allowed auto-merge, so the work proceeds without a separate direction card.

- **New cross-platform module `uni-ai`** (JVM, Scala.js, Native), depending only on `uni`. Keeps the main `uni` artifact small; no new third-party dependencies.
- **Packages by wire protocol**, not by host:
  - `wvlet.uni.ai.anthropic` — Messages API; hosts: Anthropic, Vertex AI (`rawPredict`), Bedrock InvokeModel.
  - `wvlet.uni.ai.openai` — Responses and Chat Completions; also fits OpenAI-compatible servers (vLLM, Bedrock's OpenAI endpoint).
  - `wvlet.uni.ai.gemini` — `generateContent`/`streamGenerateContent`; hosts: Google AI Studio and Vertex AI.
  - `wvlet.uni.ai.bedrock` — Converse / ConverseStream, with an AWS event-stream decoder.
  - `wvlet.uni.ai` — shared JSON codec helpers, the canonical model, and converters.
- **Forward compatible by default**: every content, item, and event union has an `Unknown(raw)` case; request and response objects keep unmodeled top-level fields in `extra`, so a proxy forwards what it does not understand.
- **Opaque vendor data stays attached**: thinking signatures, `redacted_thinking`, OpenAI `encrypted_content`, Gemini `thoughtSignature`, and `cache_control` are modeled, and survive same-vendor round trips. Across vendors the canonical model carries them in `providerMetadata`, keyed by vendor, as Vercel does.
- **Canonical model** (in `wvlet.uni.ai`): messages with typed parts, tools, usage, finish reason, and stream parts. Each vendor provides request/response/stream converters to and from it, so N vendors need 2N adapters, not N².
- **Stream conversion is stateful and per-request**: a converter object tracks open block indexes, buffers tool-argument deltas where a target needs whole JSON, and emits target-required framing (Anthropic `message_start` before content, `message_stop` at the end).
- **Clients**: `AnthropicClient`, `OpenAIClient`, `GeminiClient`, `BedrockClient`, each with `create(...)` returning `Rx[Response]` and `stream(...)` returning `Rx[Event]`. Auth: API keys, Google credentials (Vertex), SigV4 or Bedrock API key (bearer).
- **Tests** use recorded JSON and SSE payloads per vendor (round trip and conversion), plus client tests against an in-process Uni HTTP server on the JVM. No network or live keys in CI.

## Alternatives and Why Not?

- Derived Weaver codecs with snake_case field names in Scala: unidiomatic, still cannot do key-based unions or unknown passthrough.
- Put the models in the `uni` artifact: adds a large, fast-changing surface to every Uni user.
- Direct vendor-to-vendor converters: N² code paths; a canonical model is what mature proxies converge on.
- A full proxy server in Uni (routing, keys, quotas): out of scope; apps build that on these pieces.
