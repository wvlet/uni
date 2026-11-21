package wvlet.uni.ai.openai

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonCodecException
import wvlet.uni.ai.JsonObj
import wvlet.uni.http.ServerSentEvent
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

/**
  * Models of the OpenAI Chat Completions API (`POST /v1/chat/completions`), which most
  * OpenAI-compatible servers (vLLM, Ollama, Bedrock's OpenAI endpoint, ...) also serve. See
  * https://platform.openai.com/docs/api-reference/chat
  */
sealed trait ChatContentPart

object ChatContentPart:
  case class Text(text: String) extends ChatContentPart

  /** An image by http(s) or `data:` URL */
  case class ImageUrl(url: String, detail: Option[String] = None) extends ChatContentPart

  /** A file by id or as a `data:` URL in `fileData` */
  case class File(fileId: Option[String] = None, fileData: Option[String] = None, filename: Option[String] = None)
      extends ChatContentPart

  case class Refusal(refusal: String) extends ChatContentPart
  case class Unknown(raw: JSONObject) extends ChatContentPart

  given JsonCodec[ChatContentPart] = JsonCodec.of(
    {
      case Text(t) =>
        Json.obj(Json.field("type", "text"), Json.field("text", t))
      case ImageUrl(url, detail) =>
        Json.obj(
          Json.field("type", "image_url"),
          "image_url" -> Some(Json.obj(Json.field("url", url), Json.opt("detail", detail)))
        )
      case File(id, data, name) =>
        Json.obj(
          Json.field("type", "file"),
          "file" ->
            Some(Json.obj(Json.opt("file_id", id), Json.opt("file_data", data), Json.opt("filename", name)))
        )
      case Refusal(r) =>
        Json.obj(Json.field("type", "refusal"), Json.field("refusal", r))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOrEmpty("type") match
        case "text" =>
          Text(o.str("text"))
        case "image_url" =>
          val img = o.obj("image_url")
          ImageUrl(img.str("url"), img.strOpt("detail"))
        case "file" =>
          val f = o.obj("file")
          File(f.strOpt("file_id"), f.strOpt("file_data"), f.strOpt("filename"))
        case "refusal" =>
          Refusal(o.str("refusal"))
        case _ =>
          Unknown(o.value)
  )

end ChatContentPart

/** A tool call of an assistant message: `{"id", "type": "function", "function": {name, arguments}}` */
case class ChatToolCall(id: String, name: String, arguments: String)

object ChatToolCall:
  given JsonCodec[ChatToolCall] = JsonCodec.obj(
    c =>
      Seq(
        Json.field("id", c.id),
        Json.field("type", "function"),
        "function" -> Some(Json.obj(Json.field("name", c.name), Json.field("arguments", c.arguments)))
      ),
    o =>
      val f = o.obj("function")
      ChatToolCall(o.strOrEmpty("id"), f.strOrEmpty("name"), f.strOrEmpty("arguments"))
  )

/**
  * A chat message. Roles: `system`, `developer`, `user`, `assistant`, `tool`. `content` is a string
  * ([[ChatMessage.Content.Text]]) or an array of parts.
  *
  * @param reasoningContent
  *   `reasoning_content`, which vLLM, DeepSeek, and other compatible servers use for reasoning text
  */
case class ChatMessage(
    role: String,
    content: Option[ChatMessage.Content] = None,
    name: Option[String] = None,
    toolCalls: Seq[ChatToolCall] = Nil,
    toolCallId: Option[String] = None,
    refusal: Option[String] = None,
    reasoningContent: Option[String] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def text: String =
    content match
      case Some(ChatMessage.Content.Text(t)) =>
        t
      case Some(ChatMessage.Content.Parts(ps)) =>
        ps.collect { case ChatContentPart.Text(t) =>
            t
          }
          .mkString
      case None =>
        ""

object ChatMessage:
  enum Content:
    case Text(text: String)
    case Parts(parts: Seq[ChatContentPart])

  def system(text: String): ChatMessage    = ChatMessage("system", Some(Content.Text(text)))
  def user(text: String): ChatMessage      = ChatMessage("user", Some(Content.Text(text)))
  def assistant(text: String): ChatMessage = ChatMessage("assistant", Some(Content.Text(text)))

  def tool(toolCallId: String, text: String): ChatMessage = ChatMessage(
    "tool",
    Some(Content.Text(text)),
    toolCallId = Some(toolCallId)
  )

  private val known = Set(
    "role",
    "content",
    "name",
    "tool_calls",
    "tool_call_id",
    "refusal",
    "reasoning_content"
  )

  private def contentJson(c: Content): JSONValue =
    c match
      case Content.Text(t) =>
        JSONString(t)
      case Content.Parts(ps) =>
        JsonCodec[Seq[ChatContentPart]].encode(ps)

  private[openai] def contentOf(v: Option[JSONValue]): Option[Content] =
    v match
      case Some(JSONString(s)) =>
        Some(Content.Text(s))
      case Some(arr: JSONArray) =>
        Some(Content.Parts(JsonCodec[Seq[ChatContentPart]].decode(arr)))
      case Some(other) =>
        throw JsonCodecException(s"Expected string or array content, got ${other.toJSON}")
      case None =>
        None

  given JsonCodec[ChatMessage] = JsonCodec.obj(
    m =>
      Seq(
        Json.field("role", m.role),
        // Assistant messages with tool calls send "content": null
        "content" ->
          m.content
            .map(contentJson)
            .orElse(
              if m.role == "assistant" then
                Some(JSONNull())
              else
                None
            ),
        Json.opt("name", m.name),
        Json.nonEmpty("tool_calls", m.toolCalls),
        Json.opt("tool_call_id", m.toolCallId),
        Json.opt("refusal", m.refusal),
        Json.opt("reasoning_content", m.reasoningContent)
      ) ++ Json.extra(m.extra),
    o =>
      ChatMessage(
        role = o.str("role"),
        content = contentOf(o.raw("content")),
        name = o.strOpt("name"),
        toolCalls = o.seq[ChatToolCall]("tool_calls"),
        toolCallId = o.strOpt("tool_call_id"),
        refusal = o.strOpt("refusal"),
        reasoningContent = o.strOpt("reasoning_content").orElse(o.strOpt("reasoning")),
        extra = o.extra(known + "reasoning")
      )
  )

end ChatMessage

/** A function tool: `{"type": "function", "function": {name, description, parameters, strict}}` */
case class ChatTool(
    name: String,
    parameters: Option[JSONValue] = None,
    description: Option[String] = None,
    strict: Option[Boolean] = None
)

object ChatTool:
  given JsonCodec[ChatTool] = JsonCodec.obj(
    t =>
      Seq(
        Json.field("type", "function"),
        "function" ->
          Some(
            Json.obj(
              Json.field("name", t.name),
              Json.opt("description", t.description),
              Json.opt("parameters", t.parameters),
              Json.opt("strict", t.strict)
            )
          )
      ),
    o =>
      val f = o.obj("function")
      ChatTool(f.str("name"), f.opt[JSONValue]("parameters"), f.strOpt("description"), f.opt[Boolean]("strict"))
  )

/**
  * A Chat Completions request.
  *
  * @param extra
  *   request fields this version does not model (`response_format`, `seed`, `logprobs`, ...)
  */
case class ChatCompletionRequest(
    model: String,
    messages: Seq[ChatMessage],
    tools: Seq[ChatTool] = Nil,
    toolChoice: Option[ToolChoice] = None,
    parallelToolCalls: Option[Boolean] = None,
    maxCompletionTokens: Option[Int] = None,
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stop: Seq[String] = Nil,
    stream: Option[Boolean] = None,
    includeUsage: Option[Boolean] = None,
    reasoningEffort: Option[String] = None,
    user: Option[String] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def withModel(m: String): ChatCompletionRequest                 = copy(model = m)
  def withMessages(m: Seq[ChatMessage]): ChatCompletionRequest    = copy(messages = m)
  def withTools(t: Seq[ChatTool]): ChatCompletionRequest          = copy(tools = t)
  def withToolChoice(c: ToolChoice): ChatCompletionRequest        = copy(toolChoice = Some(c))
  def withMaxCompletionTokens(n: Int): ChatCompletionRequest      = copy(maxCompletionTokens = Some(n))
  def withTemperature(t: Double): ChatCompletionRequest           = copy(temperature = Some(t))
  def withReasoningEffort(e: String): ChatCompletionRequest       = copy(reasoningEffort = Some(e))

  /** Stream, and ask for a final usage chunk (`stream_options.include_usage`) */
  def withStream(enabled: Boolean): ChatCompletionRequest = copy(
    stream = Some(enabled),
    includeUsage =
      if enabled then
        Some(true)
      else
        None
  )

  def withExtra(key: String, v: JSONValue): ChatCompletionRequest = copy(extra = extra + (key -> v))

object ChatCompletionRequest:
  private val known = Set(
    "model",
    "messages",
    "tools",
    "tool_choice",
    "parallel_tool_calls",
    "max_completion_tokens",
    "max_tokens",
    "temperature",
    "top_p",
    "stop",
    "stream",
    "stream_options",
    "reasoning_effort",
    "user"
  )

  given JsonCodec[ChatCompletionRequest] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("model", r.model),
        Json.field("messages", r.messages),
        Json.nonEmpty("tools", r.tools),
        "tool_choice" ->
          r.toolChoice
            .map {
              case ToolChoice.Function(name) =>
                Json.obj(
                  Json.field("type", "function"),
                  "function" -> Some(Json.obj(Json.field("name", name)))
                )
              case other =>
                JsonCodec[ToolChoice].encode(other)
            },
        Json.opt("parallel_tool_calls", r.parallelToolCalls),
        Json.opt("max_completion_tokens", r.maxCompletionTokens),
        Json.opt("max_tokens", r.maxTokens),
        Json.opt("temperature", r.temperature),
        Json.opt("top_p", r.topP),
        Json.nonEmpty("stop", r.stop),
        Json.opt("stream", r.stream),
        "stream_options" -> r.includeUsage.map(b => Json.obj(Json.field("include_usage", b))),
        Json.opt("reasoning_effort", r.reasoningEffort),
        Json.opt("user", r.user)
      ) ++ Json.extra(r.extra),
    o =>
      ChatCompletionRequest(
        model = o.strOrEmpty("model"),
        messages = o.seq[ChatMessage]("messages"),
        tools = o.seq[ChatTool]("tools"),
        toolChoice = o.opt[ToolChoice]("tool_choice"),
        parallelToolCalls = o.opt[Boolean]("parallel_tool_calls"),
        maxCompletionTokens = o.opt[Int]("max_completion_tokens"),
        maxTokens = o.opt[Int]("max_tokens"),
        temperature = o.opt[Double]("temperature"),
        topP = o.opt[Double]("top_p"),
        stop =
          o.raw("stop") match
            case Some(JSONString(s)) =>
              Seq(s)
            case _ =>
              o.seq[String]("stop")
        ,
        stream = o.opt[Boolean]("stream"),
        includeUsage = o.objOpt("stream_options").flatMap(_.opt[Boolean]("include_usage")),
        reasoningEffort = o.strOpt("reasoning_effort"),
        user = o.strOpt("user"),
        extra = o.extra(known)
      )
  )

end ChatCompletionRequest

/** Token usage. `promptTokens` includes `cachedTokens`; `completionTokens` includes reasoning. */
case class ChatUsage(
    promptTokens: Long = 0,
    completionTokens: Long = 0,
    totalTokens: Long = 0,
    cachedTokens: Option[Long] = None,
    reasoningTokens: Option[Long] = None
)

object ChatUsage:
  given JsonCodec[ChatUsage] = JsonCodec.obj(
    u =>
      Seq(
        Json.field("prompt_tokens", u.promptTokens),
        Json.field("completion_tokens", u.completionTokens),
        Json.field("total_tokens", u.totalTokens),
        "prompt_tokens_details" -> u.cachedTokens.map(c => Json.obj(Json.field("cached_tokens", c))),
        "completion_tokens_details" ->
          u.reasoningTokens.map(r => Json.obj(Json.field("reasoning_tokens", r)))
      ),
    o =>
      ChatUsage(
        o.getOrElse[Long]("prompt_tokens", 0L),
        o.getOrElse[Long]("completion_tokens", 0L),
        o.getOrElse[Long]("total_tokens", 0L),
        o.objOpt("prompt_tokens_details").flatMap(_.opt[Long]("cached_tokens")),
        o.objOpt("completion_tokens_details").flatMap(_.opt[Long]("reasoning_tokens"))
      )
  )

end ChatUsage

/** Values of `finish_reason` */
object FinishReason:
  val Stop: String          = "stop"
  val Length: String        = "length"
  val ToolCalls: String     = "tool_calls"
  val ContentFilter: String = "content_filter"

case class ChatChoice(index: Int, message: ChatMessage, finishReason: Option[String] = None)

object ChatChoice:
  given JsonCodec[ChatChoice] = JsonCodec.obj(
    c =>
      Seq(
        Json.field("index", c.index),
        Json.field("message", c.message),
        "finish_reason" -> Some(c.finishReason.map(JSONString(_)).getOrElse(JSONNull()))
      ),
    o => ChatChoice(o.getOrElse[Int]("index", 0), o.get[ChatMessage]("message"), o.strOpt("finish_reason"))
  )

case class ChatCompletion(
    id: String,
    model: String,
    choices: Seq[ChatChoice],
    created: Long = 0,
    usage: Option[ChatUsage] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def message: Option[ChatMessage] = choices.headOption.map(_.message)
  def text: String                 = message.map(_.text).getOrElse("")

object ChatCompletion:
  private val known = Set("id", "object", "created", "model", "choices", "usage")

  given JsonCodec[ChatCompletion] = JsonCodec.obj(
    c =>
      Seq(
        Json.field("id", c.id),
        Json.field("object", "chat.completion"),
        Json.field("created", c.created),
        Json.field("model", c.model),
        Json.field("choices", c.choices),
        Json.opt("usage", c.usage)
      ) ++ Json.extra(c.extra),
    o =>
      ChatCompletion(
        o.strOrEmpty("id"),
        o.strOrEmpty("model"),
        o.seq[ChatChoice]("choices"),
        o.getOrElse[Long]("created", 0L),
        o.opt[ChatUsage]("usage"),
        o.extra(known)
      )
  )

end ChatCompletion

/**
  * A fragment of a tool call in a stream. `id` and `name` come with the first fragment of each
  * `index`; later fragments carry only `arguments`.
  */
case class ChatToolCallDelta(
    index: Int,
    id: Option[String] = None,
    name: Option[String] = None,
    arguments: Option[String] = None
)

object ChatToolCallDelta:
  given JsonCodec[ChatToolCallDelta] = JsonCodec.obj(
    d =>
      Seq(
        Json.field("index", d.index),
        Json.opt("id", d.id),
        "type" -> d.id.map(_ => JSONString("function")),
        "function" -> Some(Json.obj(Json.opt("name", d.name), Json.opt("arguments", d.arguments)))
      ),
    o =>
      val f = o.objOpt("function")
      ChatToolCallDelta(
        o.getOrElse[Int]("index", 0),
        o.strOpt("id"),
        f.flatMap(_.strOpt("name")),
        f.flatMap(_.strOpt("arguments"))
      )
  )

case class ChatDelta(
    role: Option[String] = None,
    content: Option[String] = None,
    reasoningContent: Option[String] = None,
    refusal: Option[String] = None,
    toolCalls: Seq[ChatToolCallDelta] = Nil
)

object ChatDelta:
  given JsonCodec[ChatDelta] = JsonCodec.obj(
    d =>
      Seq(
        Json.opt("role", d.role),
        Json.opt("content", d.content),
        Json.opt("reasoning_content", d.reasoningContent),
        Json.opt("refusal", d.refusal),
        Json.nonEmpty("tool_calls", d.toolCalls)
      ),
    o =>
      ChatDelta(
        o.strOpt("role"),
        o.strOpt("content"),
        o.strOpt("reasoning_content").orElse(o.strOpt("reasoning")),
        o.strOpt("refusal"),
        o.seq[ChatToolCallDelta]("tool_calls")
      )
  )

case class ChatChunkChoice(index: Int, delta: ChatDelta, finishReason: Option[String] = None)

object ChatChunkChoice:
  given JsonCodec[ChatChunkChoice] = JsonCodec.obj(
    c =>
      Seq(
        Json.field("index", c.index),
        Json.field("delta", c.delta),
        "finish_reason" -> Some(c.finishReason.map(JSONString(_)).getOrElse(JSONNull()))
      ),
    o =>
      ChatChunkChoice(
        o.getOrElse[Int]("index", 0),
        o.getOrElse[ChatDelta]("delta", ChatDelta()),
        o.strOpt("finish_reason")
      )
  )

/**
  * A streamed `chat.completion.chunk`. With `stream_options.include_usage`, the last chunk has no
  * choices and carries `usage`. The stream ends with `data: [DONE]`.
  */
case class ChatCompletionChunk(
    id: String,
    model: String,
    choices: Seq[ChatChunkChoice] = Nil,
    created: Long = 0,
    usage: Option[ChatUsage] = None
):
  def toServerSentEvent: ServerSentEvent = ServerSentEvent(data = JsonCodec[ChatCompletionChunk].toJson(this))

object ChatCompletionChunk:
  given JsonCodec[ChatCompletionChunk] = JsonCodec.obj(
    c =>
      Seq(
        Json.field("id", c.id),
        Json.field("object", "chat.completion.chunk"),
        Json.field("created", c.created),
        Json.field("model", c.model),
        Json.field("choices", c.choices),
        Json.opt("usage", c.usage)
      ),
    o =>
      ChatCompletionChunk(
        o.strOrEmpty("id"),
        o.strOrEmpty("model"),
        o.seq[ChatChunkChoice]("choices"),
        o.getOrElse[Long]("created", 0L),
        o.opt[ChatUsage]("usage")
      )
  )

  /** The SSE event that ends a Chat Completions stream */
  val done: ServerSentEvent = ServerSentEvent(data = "[DONE]")

  def isDone(e: ServerSentEvent): Boolean = e.data.trim == "[DONE]"

  def fromServerSentEvent(e: ServerSentEvent): ChatCompletionChunk = JsonCodec[ChatCompletionChunk]
    .decode(JSON.parseAny(e.data))

end ChatCompletionChunk
