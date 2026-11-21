# MCP and LLM call tracing

## Goals

- Users who turned on Uni's telemetry (#1088, #1092) see their MCP tool calls and LLM calls as named spans, and their token usage and latency as metrics. They write no tracing code, and it works on every platform, including MCP over stdio, where no HTTP filter runs.
- Today they see only HTTP client spans named `POST`. They have no model, no token counts, no tool name, and no stdio MCP spans at all.
- Telemetry turned off still costs nothing. Prompts, messages, completions, and tool arguments are never recorded.

## Background

- The OpenTelemetry GenAI and MCP conventions now live in `open-telemetry/semantic-conventions-genai`. Its first release is v0.1.0 (2026-11-21), and it is still marked Development. Names checked against `main` today:
  - MCP spans: named `{mcp.method.name} {gen_ai.tool.name}` (e.g. `tools/call get_weather`), else just the method name. Kind CLIENT or SERVER. Attributes: `mcp.method.name`, `jsonrpc.request.id`, `mcp.protocol.version`, `gen_ai.operation.name = execute_tool` for tool calls, `gen_ai.tool.name`, `network.transport` (`pipe` for stdio, `tcp` for HTTP), and `rpc.status_code` plus `error.type` on errors (`tool_error` for an `isError` result). Trace context travels in `params._meta.traceparent`, whatever the transport. Metrics: `mcp.client.operation.duration` and `mcp.server.operation.duration`.
  - Inference spans: named `{gen_ai.operation.name} {gen_ai.request.model}`, kind CLIENT. Attributes: `gen_ai.provider.name` (this replaced `gen_ai.system`, which is deprecated), `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.response.id`, `gen_ai.response.finish_reasons`, `gen_ai.usage.input_tokens` / `output_tokens` / `cache_read.input_tokens` / `cache_write.input_tokens`, `gen_ai.request.max_tokens` / `temperature`, `server.address`, and `error.type`.
  - Inference metrics in v0.1.0: `gen_ai.client.inference.duration` (histogram, s) and the `gen_ai.client.inference.operation.input_tokens` / `output_tokens` histograms. Those two replace `gen_ai.client.token.usage` and its `gen_ai.token.type` attribute. Inference no longer reports `gen_ai.client.operation.duration`.
  - Message content (`gen_ai.input.messages`, `gen_ai.output.messages`, `gen_ai.system_instructions`, tool arguments and results) is Opt-In, because it carries user data.
- Uni's telemetry has histograms only, with no counters. The thread-local `Telemetry.currentSpan` covers the synchronous part of a call. The async HTTP client takes its parent span when `send` is called.
- Each LLM vendor client (Anthropic, OpenAI, Gemini) already takes its `HttpAsyncClient` as a constructor argument. That client's `HttpClientConfig` carries its `Telemetry`. The ADR says "an LLM vendor client sends `traceparent` only if it is configured with telemetry", so the opt-in already exists per client.
- `MCPServer.handleMessage` is the one transport-independent entry point for both stdio and HTTP. `MCPClient` sends everything through one `call`/`notify` pair over its `HttpSyncClient`.

## Design

Lane: Standard. This adds new API (`MCPServer.withTelemetry`) and new spans and metrics that users see in their backends.

User flow:
- A service that already builds `val telemetry = Telemetry().withOtlp(...)` passes it in three places:
  - the LLM client: `AnthropicClient(config, Http.client.noRetry.withTelemetry(telemetry).newAsyncClient)`. The docs and the skill keep `noRetry` and say why: the default client has it, and retries re-bill a call. Leaving it out of a hand-built client turns retries back on.
  - the MCP client: `MCP.client.withTelemetry(telemetry)`
  - the MCP server: `MCPServer().withTools(...).withTelemetry(telemetry)`
- In Datadog or Jaeger they see `chat claude-sonnet-5-5` with token counts above its `POST` HTTP span. A remote `tools/call search` client span is the parent of the server's `tools/call search` span. Each tool's own outgoing calls nest under it.
- To turn it off, they drop the call or use `noTelemetry`. Nothing else changes.

Decisions:
- **Opt-in source.** LLM clients read telemetry from the HTTP client they were given, so the existing switch also turns on GenAI spans. `MCPClientConfig.withTelemetry` / `noTelemetry` set it on its `httpClientConfig`. `MCPServer.withTelemetry` / `noTelemetry` is new, since a stdio server has no HTTP client to borrow it from.
- **MCP server span per JSON-RPC message**, in `handleMessage`, so stdio and HTTP both get it. Notifications get one too. The parent is `params._meta.traceparent` when present, else the current span: the HTTP server span under `TelemetryFilter`. Uni has no span links, so the ambient span is dropped when `_meta` wins. The span is current while the tool runs synchronously, so the tool's own calls nest under it. `network.transport` comes from a private overload of `handleMessage` that each transport calls with its own value; the public signature stays.
- **MCP client span per call**, joining the current span. It writes `traceparent`/`tracestate` into `params._meta`, as the MCP convention asks. The HTTP client span under it still sends the header.
- **LLM span per inference call**, started when the returned Rx runs (each run is a new call, as in the HTTP client). The caller's current span is captured at call time and becomes the parent. The LLM span is made current around `send`, so the HTTP span becomes its child. The span covers the typed methods: Anthropic `messages`/`stream`, OpenAI `responses`/`chatCompletions` and their streams, Gemini `generateContent`/`streamGenerateContent`. The vendor-neutral `LlmClient` goes through these, so both paths are covered. `countTokens` gets no GenAI span; its HTTP span stays.
  - `gen_ai.operation.name` is `chat` for Anthropic and OpenAI, `generate_content` for Gemini.
  - `gen_ai.provider.name` names the host:
    - `anthropic` and `gcp.gemini` for their own APIs;
    - `gcp.vertex_ai` and `aws.bedrock` for Vertex AI and Bedrock;
    - `openai` for `api.openai.com` and for any other OpenAI-compatible `baseUrl`, where the API family is all the client knows. `server.address` names the real host.
  - A small extractor per vendor reads model, id, usage, and finish reasons from the typed response, or from a stream's final events. It doesn't keep the content. A missing raw finish reason falls back to `FinishReason.Kind`. The same values fill the span and the metrics.
- **Streams end their span at the end of the stream.** That covers completion, failure, and cancellation, as SSE spans do today. Usage and the finish reason are read from the final events.
- **Metrics follow v0.1.0**, measured whether the span was sampled or not, as the HTTP metrics are. Uni records `gen_ai.client.inference.duration`, `gen_ai.client.inference.operation.input_tokens` / `output_tokens`, and `mcp.{client,server}.operation.duration`, with the spec's bucket advice and only low-cardinality attributes: provider, operation, request/response model, error type, and server address/port for LLM; method, tool name, error type, and transport for MCP. The `gen_ai.client.inference.usage.*` counters are left out, since Uni has no counters and the histogram sums give the same totals. These replace the `token.usage` and `operation.duration` metrics the request named.
- **No content capture in this PR.** Messages, completions, system prompts, and tool arguments and results are never recorded. An opt-in (`withContentCapture`) is a follow-up, if someone asks for it: span attributes are capped in size, and the spec is moving content to events and `_ref` external storage.
- **Disabled costs nothing.** Every path checks `telemetry.isEnabled` first, then calls straight through with no span, no clock read, and no `_meta` rewrite. Metrics-only mode records the durations and token counts, and starts no traces.
- Tests use `SpanExporter.inMemory` and `MetricExporter.inMemory`. They cover MCP over `handleMessage` (stdio path) and over HTTP with `_meta` propagation, a stub LLM server per vendor for non-streaming, streaming, and error calls, and disabled telemetry.
- Docs: a section in `docs/http/telemetry.md`, an ADR addendum, the `for-ai-agents` page, and the `uni` skill, with a version bump.

## Alternatives and Why Not?

- **`withTelemetry` on each vendor config (`AnthropicConfig`, ...).** It adds a second switch that can disagree with the HTTP client's. The HTTP client is where the ADR put the opt-in.
- **Pre-v0.1.0 metric names (`gen_ai.client.token.usage`, `gen_ai.client.operation.duration`).** Older dashboards know them, and your request named them, but v0.1.0 replaced them with the instruments above, and Uni follows the current conventions everywhere else. If we ship the old names, renaming them later breaks users.
- **Emit the deprecated `gen_ai.system` as well.** It doubles the attributes for backends that already read `gen_ai.provider.name`.
- **Trace only in `LlmClient.generate`/`stream`.** That misses everyone who calls the typed vendor APIs directly.
- **Derive MCP spans from the HTTP server span.** Stdio has no HTTP span, and the tool name lives in the JSON body.
