---
description: Typed Scala models and clients for the Anthropic Messages, OpenAI Responses and Chat Completions, Gemini (Google AI Studio and Vertex AI), and Amazon Bedrock Converse APIs, streaming and non-streaming, with converters that let one vendor's request be served by another.
---

# LLM APIs

The `wvlet.uni.ai` packages of `uni` give Scala code typed requests, responses, and streaming events for the LLM APIs that
apps call most:

| Package | API | Hosts |
|---------|-----|-------|
| `wvlet.uni.ai.anthropic` | Messages (`/v1/messages`) | Anthropic API, Claude on Vertex AI, Claude on Bedrock (InvokeModel) |
| `wvlet.uni.ai.openai` | Responses (`/v1/responses`) and Chat Completions | OpenAI, and OpenAI-compatible servers such as vLLM, Ollama, and Bedrock's OpenAI endpoint |
| `wvlet.uni.ai.gemini` | `generateContent` and `streamGenerateContent` | Google AI Studio, Vertex AI |
| `wvlet.uni.ai.bedrock` | Converse and ConverseStream | Amazon Bedrock |
| `wvlet.uni.ai` | A vendor-neutral model and converters | |

The types use Scala names, and their codecs write each vendor's exact JSON: `max_tokens`,
`type: "tool_use"`, Gemini's `{"functionCall": ...}` parts, no `null` for absent fields. Block,
item, and event types a release doesn't know yet decode to an `Unknown` case that keeps the raw
JSON, and unmodeled request fields go in `extra`, so a newer payload passes through unchanged.
It runs on the JVM, Scala.js, and Scala Native, and comes with the `uni` artifact
([Installation](../guide/installation)); code you don't call is removed when Scala.js and Scala
Native link.

## Calling a vendor API

Each vendor client returns an `Rx` of the response, or an `Rx` of stream events:

```scala
import wvlet.uni.ai.anthropic.*
import wvlet.uni.rx.Rx

val claude = AnthropicClient(AnthropicConfig.fromEnv) // ANTHROPIC_API_KEY

val request = MessagesRequest(
  model = "claude-sonnet-4-5",
  maxTokens = 1024,
  messages = Seq(Message.user("Name three Scala build tools"))
).withSystem("Answer in one line.")

val reply: Rx[MessagesResponse] = claude.messages(request)
reply.map(r => println(r.text))

// Streaming: one StreamEvent per SSE event
val text: Rx[String] = claude.stream(request).flatMap(e => Rx.fromSeq(e.textDelta.toSeq))
```

`MessageAccumulator` folds the events into the final `MessagesResponse`, joining text, thinking,
and tool-input deltas. The other vendors have the same pair of calls:

```scala
import wvlet.uni.ai.openai.*

val openai = OpenAIClient(OpenAIConfig.fromEnv) // OPENAI_API_KEY, OPENAI_BASE_URL

val res = openai.responses(ResponsesRequest("gpt-5.1", Seq(Item.user("Hello"))))
val events: Rx[ResponseStreamEvent] = openai.streamResponses(
  ResponsesRequest("gpt-5.1", Seq(Item.user("Hello")))
)

// Any OpenAI-compatible server
val local = OpenAIClient(OpenAIConfig(apiKey = None, baseUrl = "http://localhost:8000/v1"))
val chunks: Rx[ChatCompletionChunk] = local.streamChatCompletions(
  ChatCompletionRequest("Qwen/Qwen3-8B", Seq(ChatMessage.user("Hello")))
)
```

```scala
import wvlet.uni.ai.gemini.*

val gemini = GeminiClient(GeminiConfig.fromEnv) // GEMINI_API_KEY
val g      = gemini.generateContent(
  "gemini-2.5-flash",
  GenerateContentRequest(Seq(Content.user("Hello")))
)

// Vertex AI with Application Default Credentials
val vertex = GeminiClient(GeminiConfig(GeminiHost.Vertex("my-project", "global")))
```

```scala reset
import wvlet.uni.ai.BedrockAuth
import wvlet.uni.ai.bedrock.*
import wvlet.uni.rx.Rx

// AWS_BEARER_TOKEN_BEDROCK if set, else the default AWS credentials chain (SigV4)
val bedrock = BedrockClient(BedrockConfig("us-east-1"))
val stream: Rx[ConverseStreamEvent] = bedrock.converseStream(
  "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
  ConverseRequest(Seq(Message.user("Hello")))
)
```

Claude on Vertex AI and on Bedrock takes the Anthropic request body, so use `AnthropicClient`
with `AnthropicHost.Vertex(projectId, region)` or `AnthropicHost.Bedrock(region)`. The client
moves the model into the URL, adds the host's `anthropic_version`, and on Bedrock decodes the
event-stream frames back into `StreamEvent`s.

A failed call ends the `Rx` with an `LlmApiException` carrying the HTTP status, the vendor's error
type (`overloaded_error`, `rate_limit_error`, `RESOURCE_EXHAUSTED`, `ThrottlingException`), and the
body. The clients don't retry: a retried completion is billed twice, so retry where you know that
is safe, with [Retry](../control/retry).

## Converting between vendors

`wvlet.uni.ai` has a vendor-neutral model: `LlmRequest`, `LlmResponse`, and a stream of
`LlmStreamPart`s that open, grow, and close text, reasoning, and tool-input blocks. Each vendor
package has a converter to and from it, so any vendor's request can be served by any other
vendor. Every client also implements `LlmClient`, which takes an `LlmRequest`:

```scala reset
import wvlet.uni.ai.*
import wvlet.uni.ai.anthropic.*
import wvlet.uni.ai.openai.{OpenAIClient, OpenAIConfig}
import wvlet.uni.rx.Rx

// A Claude Code request, served by an OpenAI model
def serveWithOpenAI(body: String, openai: OpenAIClient): Rx[StreamEvent] =
  val request = JsonCodec[MessagesRequest].fromJson(body)
  val neutral = AnthropicConverter.toLlmRequest(request).withModel("gpt-5.1")
  val encoder = AnthropicConverter.StreamEncoder()
  openai
    .stream(neutral)
    .flatMap(part => Rx.fromSeq(encoder.encode(part)))
    .concat(Rx.fromSeq(encoder.finish()))
```

The encoder adds the framing Anthropic clients expect: `message_start` first, a start and stop
around every block, and `message_delta` with the stop reason and usage at the end. Send the events
with `Response.eventStream(events.map(_.toServerSentEvent))` from a [REST server](../http/server).
The other directions work the same way, with `ResponsesConverter`, `ChatCompletionsConverter`,
`GeminiConverter`, and `BedrockConverter`, each with a `StreamDecoder` and a `StreamEncoder`.

What the converters take care of:

- **Usage**: Anthropic and Bedrock count input tokens without cache reads and writes; OpenAI and
  Gemini include them. Gemini counts thoughts apart from output. `LlmUsage` always includes both,
  and each converter adjusts.
- **Stop reasons**: Gemini says `STOP` and Responses says `completed` even when the model called a
  tool, so the converters derive `tool_use` / `tool_calls` from the content. Chat Completions sends
  usage after the finish chunk, and Bedrock sends `metadata` after `messageStop`, so the decoders
  wait for both before the final part.
- **Reasoning across vendors**: Claude Code returns thinking blocks unchanged, signatures included.
  The Anthropic encoder puts OpenAI encrypted reasoning and Gemini thought signatures in the
  thinking `signature`, with a `wvlet.uni.ai:` prefix, and the decoder restores them on the next turn. The
  OpenAI encoder carries Anthropic signatures in `encrypted_content` the same way. Gemini function
  calls in history without a signature get Gemini's documented placeholder.
- **Tool ids and schemas**: ids are rewritten deterministically where a vendor restricts them
  (Anthropic, Bedrock). Gemini gets the JSON Schema unchanged through `parametersJsonSchema`.
- **Prompt caching**: Anthropic `cache_control` and Bedrock `cachePoint` map to each other.
- **Vendor-only blocks**: server tool results, built-in tools, and unknown blocks travel as
  `LlmPart.Raw` and `LlmTool.Provider`, and only reach the vendor they came from.

Options only one vendor understands go in `providerOptions`, keyed by vendor name; the converter
for that vendor merges them into the request body:

```scala
import wvlet.uni.json.JSON.JSONObject

val withOptions = LlmRequest("gpt-5.1", Seq(LlmMessage.user("Hi"))).copy(
  providerOptions = ProviderMetadata.of(ProviderMetadata.OpenAI, Json.field("service_tier", "flex"))
)
```

## Serving an API

The types also describe what a server sends. To stream Anthropic events from a handler, map them to
SSE events:

```scala
import wvlet.uni.http.{Http, Request, Response}

def messagesHandler(openai: OpenAIClient)(req: Request): Rx[Response] =
  Rx.single(Response.eventStream(serveWithOpenAI(req.content.toContentString, openai).map(_.toServerSentEvent)))
```

Chat Completions streams end with `ChatCompletionChunk.done` (`data: [DONE]`), and Bedrock streams
are event-stream frames: `BedrockConverter.toFrame(event)` encodes one, and
`EventStreamDecoder` reads them.

Every model type has a `JsonCodec` with `toJson` and `fromJson`, and `codec.weaver` gives a
[Weaver](../core/weaver) for returning the types from routers and RPC handlers.
