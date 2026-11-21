# LLM vendor APIs

`wvlet.uni.ai` (in `uni`, all platforms) models the Anthropic Messages, OpenAI
Responses and Chat Completions, and Gemini `generateContent` APIs, with clients and converters
between them. Claude and OpenAI models on Amazon Bedrock use the Anthropic and OpenAI clients.
Full page: https://wvlet.org/uni/ai/

## Rules

- One package per wire protocol: `wvlet.uni.ai.anthropic`, `.openai`, `.gemini`.
  They share names (`Message`, `Tool`, `ContentBlock`), so import one vendor's `*` per file, or
  rename packages: `import wvlet.uni.ai.anthropic as an`.
- Encode and decode with `JsonCodec[A].toJson(a)` / `JsonCodec[A].fromJson(json)`, not Weaver
  derivation: the codecs write the vendor's exact keys and drop absent fields. `codec.weaver`
  adapts one to a `Weaver`.
- Unknown block, item, tool, and event types decode to `Unknown(raw)` and re-encode unchanged;
  unmodeled request fields are in `extra` (`req.withExtra("key", json)`).
- Clients return `Rx`; nothing is sent until the `Rx` runs. They never retry (a retry is billed
  again). Failures end the `Rx` with `wvlet.uni.ai.LlmApiException` (`statusCode`, `errorType`,
  `body`, `isRetryable`).
- To serve one vendor's request with another, go through the neutral model
  (`LlmRequest`, `LlmResponse`, `LlmStreamPart`): `XConverter.toLlmRequest`, then
  `YConverter.fromLlmRequest`. For streams, make one `StreamDecoder()` / `StreamEncoder()` per
  stream and call `finish()` at the end.

## Clients

```scala
import wvlet.uni.ai.anthropic.*
import wvlet.uni.rx.Rx

val claude  = AnthropicClient(AnthropicConfig.fromEnv) // ANTHROPIC_API_KEY
val request = MessagesRequest("claude-sonnet-4-5", 1024, Seq(Message.user("Hi")))
val reply: Rx[MessagesResponse] = claude.messages(request)
val text: Rx[String]            = claude.stream(request).flatMap(e => Rx.fromSeq(e.textDelta.toSeq))

// Claude on Vertex AI or Bedrock takes the same request (Bedrock model ids: us.anthropic.claude-sonnet-5)
val onVertex = AnthropicClient(AnthropicConfig(AnthropicHost.Vertex("my-project", "global")))
// Bedrock's Anthropic API; AWS_BEARER_TOKEN_BEDROCK, else SigV4 with the default AWS credentials chain
val onBedrock = AnthropicClient(AnthropicConfig(AnthropicHost.Bedrock("us-east-1")))
```

```scala
import wvlet.uni.ai.openai.*

val openai = OpenAIClient(OpenAIConfig.fromEnv) // OPENAI_API_KEY, OPENAI_BASE_URL
openai.responses(ResponsesRequest("gpt-5.1", Seq(Item.user("Hi"))))
openai.streamChatCompletions(ChatCompletionRequest("gpt-4.1", Seq(ChatMessage.user("Hi"))))
// vLLM, Ollama, or another OpenAI-compatible server
val vllm = OpenAIClient(OpenAIConfig(apiKey = None, baseUrl = "http://localhost:8000/v1"))
// OpenAI models on Amazon Bedrock (Responses and Chat Completions), with Bedrock auth
val gptOnBedrock = OpenAIClient(OpenAIConfig.bedrock("us-east-1"))
```

```scala reset
import wvlet.uni.ai.gemini.*

val gemini = GeminiClient(GeminiConfig.fromEnv) // GEMINI_API_KEY; or GeminiHost.Vertex(project, region)
gemini.streamGenerateContent("gemini-2.5-flash", GenerateContentRequest(Seq(Content.user("Hi"))))
```

## Converting between vendors

```scala reset
import wvlet.uni.ai.*
import wvlet.uni.ai.anthropic.*
import wvlet.uni.ai.openai.{OpenAIClient, ResponsesConverter}
import wvlet.uni.rx.Rx

// An Anthropic (Claude Code) request served by an OpenAI model, streamed back as Anthropic events
def proxy(body: String, openai: OpenAIClient): Rx[StreamEvent] =
  val llm     = AnthropicConverter.toLlmRequest(JsonCodec[MessagesRequest].fromJson(body)).withModel("gpt-5.1")
  val encoder = AnthropicConverter.StreamEncoder()
  openai.stream(llm).flatMap(p => Rx.fromSeq(encoder.encode(p))).concat(Rx.fromSeq(encoder.finish()))

// Non-streaming: neutral request to a vendor request, vendor response back to neutral
def toOpenAI(req: MessagesRequest) = ResponsesConverter.fromLlmRequest(AnthropicConverter.toLlmRequest(req))
```

Send stream events as SSE with `Response.eventStream(events.map(_.toServerSentEvent))`; end Chat
Completions streams with `ChatCompletionChunk.done`.

What the converters handle, so don't redo it: usage normalization (Anthropic input
excludes cache tokens; OpenAI and Gemini include them), stop reasons derived from tool calls,
usage that arrives after the finish chunk, deterministic tool-id rewrites, and reasoning carried
across vendors in thinking signatures (`wvlet.uni.ai:` prefix).
