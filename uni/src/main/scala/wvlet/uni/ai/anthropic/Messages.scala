package wvlet.uni.ai.anthropic

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonCodecException
import wvlet.uni.ai.JsonObj
import wvlet.uni.json.JSON.*

/**
  * Models of the Anthropic Messages API (`POST /v1/messages`), also served by Vertex AI
  * (`rawPredict`) and Amazon Bedrock (`/anthropic/v1/messages`). See
  * https://docs.anthropic.com/en/api/messages
  *
  * Field names follow Scala style; the codecs write the API's snake_case keys. Block, tool, and
  * event types this version does not model decode to an `Unknown` case that keeps the raw JSON, so
  * newer payloads round-trip unchanged.
  */
enum Role(val name: String):
  case User      extends Role("user")
  case Assistant extends Role("assistant")

object Role:
  def of(s: String): Role = values
    .find(_.name == s)
    .getOrElse(throw JsonCodecException(s"Unknown role: ${s}"))

  given JsonCodec[Role] = JsonCodec.of(
    r => JSONString(r.name),
    v => of(JsonCodec[String].decode(v))
  )

/** Prompt-caching breakpoint, `{"type": "ephemeral", "ttl": "5m" | "1h"}` */
case class CacheControl(`type`: String = "ephemeral", ttl: Option[String] = None)

object CacheControl:
  val ephemeral: CacheControl = CacheControl()

  given JsonCodec[CacheControl] = JsonCodec.obj(
    c => Seq(Json.field("type", c.`type`), Json.opt("ttl", c.ttl)),
    o => CacheControl(o.getOrElse("type", "ephemeral"), o.strOpt("ttl"))
  )

/** Where image and document bytes come from */
sealed trait Source

object Source:
  case class Base64(mediaType: String, data: String)              extends Source
  case class Url(url: String)                                     extends Source
  case class File(fileId: String)                                 extends Source
  case class Text(data: String, mediaType: String = "text/plain") extends Source
  case class Unknown(raw: JSONObject)                             extends Source

  given JsonCodec[Source] = JsonCodec.of(
    {
      case Base64(m, d) =>
        Json.obj(Json.field("type", "base64"), Json.field("media_type", m), Json.field("data", d))
      case Url(u) =>
        Json.obj(Json.field("type", "url"), Json.field("url", u))
      case File(id) =>
        Json.obj(Json.field("type", "file"), Json.field("file_id", id))
      case Text(d, m) =>
        Json.obj(Json.field("type", "text"), Json.field("media_type", m), Json.field("data", d))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOrEmpty("type") match
        case "base64" =>
          Base64(o.str("media_type"), o.str("data"))
        case "url" =>
          Url(o.str("url"))
        case "file" =>
          File(o.str("file_id"))
        case "text" =>
          Text(o.str("data"), o.getOrElse("media_type", "text/plain"))
        case _ =>
          Unknown(o.value)
  )

end Source

/**
  * A content block of a message. `Unknown` keeps blocks such as `web_search_tool_result`,
  * `mcp_tool_use`, or `container_upload` as raw JSON.
  */
sealed trait ContentBlock:
  def blockType: String

object ContentBlock:
  case class Text(
      text: String,
      citations: Seq[JSONValue] = Nil,
      cacheControl: Option[CacheControl] = None
  ) extends ContentBlock:
    def blockType = "text"

  case class Image(source: Source, cacheControl: Option[CacheControl] = None) extends ContentBlock:
    def blockType = "image"

  case class Document(
      source: Source,
      title: Option[String] = None,
      context: Option[String] = None,
      citations: Option[JSONValue] = None,
      cacheControl: Option[CacheControl] = None
  ) extends ContentBlock:
    def blockType = "document"

  case class ToolUse(
      id: String,
      name: String,
      input: JSONValue = JSONObject.empty,
      cacheControl: Option[CacheControl] = None
  ) extends ContentBlock:
    def blockType = "tool_use"

  /**
    * The result of a client tool call. A string `content` decodes to one Text block.
    */
  case class ToolResult(
      toolUseId: String,
      content: Seq[ContentBlock] = Nil,
      isError: Option[Boolean] = None,
      cacheControl: Option[CacheControl] = None
  ) extends ContentBlock:
    def blockType = "tool_result"

  case class Thinking(thinking: String, signature: String) extends ContentBlock:
    def blockType = "thinking"

  case class RedactedThinking(data: String) extends ContentBlock:
    def blockType = "redacted_thinking"

  /** A call of a tool that Anthropic runs, such as `web_search` or `code_execution` */
  case class ServerToolUse(
      id: String,
      name: String,
      input: JSONValue = JSONObject.empty,
      cacheControl: Option[CacheControl] = None
  ) extends ContentBlock:
    def blockType = "server_tool_use"

  case class Unknown(raw: JSONObject) extends ContentBlock:
    def blockType = JsonObj(raw).strOrEmpty("type")

  def text(s: String): ContentBlock = Text(s)

  private def cache(c: Option[CacheControl]) = Json.opt("cache_control", c)

  given codec: JsonCodec[ContentBlock] = JsonCodec.of(encode, decode)

  private def encode(b: ContentBlock): JSONValue =
    b match
      case Text(t, cit, c) =>
        Json.obj(
          Json.field("type", "text"),
          Json.field("text", t),
          Json.nonEmpty("citations", cit),
          cache(c)
        )
      case Image(s, c) =>
        Json.obj(Json.field("type", "image"), Json.field("source", s), cache(c))
      case Document(s, title, ctx, cit, c) =>
        Json.obj(
          Json.field("type", "document"),
          Json.field("source", s),
          Json.opt("title", title),
          Json.opt("context", ctx),
          Json.opt("citations", cit),
          cache(c)
        )
      case ToolUse(id, name, input, c) =>
        Json.obj(
          Json.field("type", "tool_use"),
          Json.field("id", id),
          Json.field("name", name),
          Json.field("input", input),
          cache(c)
        )
      case ToolResult(id, content, isError, c) =>
        Json.obj(
          Json.field("type", "tool_result"),
          Json.field("tool_use_id", id),
          Json.nonEmpty("content", content),
          Json.opt("is_error", isError),
          cache(c)
        )
      case Thinking(t, sig) =>
        Json.obj(
          Json.field("type", "thinking"),
          Json.field("thinking", t),
          Json.field("signature", sig)
        )
      case RedactedThinking(d) =>
        Json.obj(Json.field("type", "redacted_thinking"), Json.field("data", d))
      case ServerToolUse(id, name, input, c) =>
        Json.obj(
          Json.field("type", "server_tool_use"),
          Json.field("id", id),
          Json.field("name", name),
          Json.field("input", input),
          cache(c)
        )
      case Unknown(raw) =>
        raw

  private def decode(v: JSONValue): ContentBlock =
    val o = JsonObj(v)
    o.strOrEmpty("type") match
      case "text" =>
        Text(o.str("text"), o.seq[JSONValue]("citations"), o.opt[CacheControl]("cache_control"))
      case "image" =>
        Image(o.get[Source]("source"), o.opt[CacheControl]("cache_control"))
      case "document" =>
        Document(
          o.get[Source]("source"),
          o.strOpt("title"),
          o.strOpt("context"),
          o.opt[JSONValue]("citations"),
          o.opt[CacheControl]("cache_control")
        )
      case "tool_use" =>
        ToolUse(
          o.str("id"),
          o.str("name"),
          o.getOrElse[JSONValue]("input", JSONObject.empty),
          o.opt[CacheControl]("cache_control")
        )
      case "tool_result" =>
        ToolResult(
          o.str("tool_use_id"),
          blocksOf(o.raw("content")),
          o.opt[Boolean]("is_error"),
          o.opt[CacheControl]("cache_control")
        )
      case "thinking" =>
        Thinking(o.strOrEmpty("thinking"), o.strOrEmpty("signature"))
      case "redacted_thinking" =>
        RedactedThinking(o.str("data"))
      case "server_tool_use" =>
        ServerToolUse(
          o.str("id"),
          o.str("name"),
          o.getOrElse[JSONValue]("input", JSONObject.empty),
          o.opt[CacheControl]("cache_control")
        )
      case _ =>
        Unknown(o.value)
    end match

  end decode

  /** Content that the API accepts either as a string or as an array of blocks */
  private[anthropic] def blocksOf(v: Option[JSONValue]): Seq[ContentBlock] =
    v match
      case None =>
        Nil
      case Some(JSONString(s)) =>
        Seq(Text(s))
      case Some(JSONArray(xs)) =>
        xs.map(decode)
      case Some(other) =>
        throw JsonCodecException(s"Expected a string or an array of blocks, got ${other.toJSON}")

end ContentBlock

case class Message(role: Role, content: Seq[ContentBlock]):
  /** The concatenated text of the Text blocks */
  def text: String =
    content
      .collect { case ContentBlock.Text(t, _, _) =>
        t
      }
      .mkString

object Message:
  def user(text: String): Message      = Message(Role.User, Seq(ContentBlock.Text(text)))
  def assistant(text: String): Message = Message(Role.Assistant, Seq(ContentBlock.Text(text)))

  given JsonCodec[Message] = JsonCodec.obj(
    m => Seq(Json.field("role", m.role), Json.field("content", m.content)),
    o => Message(o.get[Role]("role"), ContentBlock.blocksOf(o.raw("content")))
  )

/**
  * A tool definition. `Custom` is a client tool described by a JSON Schema; `Unknown` keeps tools
  * that Anthropic runs or defines (`web_search_20250305`, `bash_20250124`, `mcp_toolset`, ...).
  */
sealed trait Tool

object Tool:
  case class Custom(
      name: String,
      inputSchema: JSONValue,
      description: Option[String] = None,
      cacheControl: Option[CacheControl] = None
  ) extends Tool

  case class Unknown(raw: JSONObject) extends Tool

  given JsonCodec[Tool] = JsonCodec.of(
    {
      case Custom(name, schema, desc, c) =>
        Json.obj(
          Json.field("name", name),
          Json.opt("description", desc),
          Json.field("input_schema", schema),
          Json.opt("cache_control", c)
        )
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOpt("type") match
        case None | Some("custom") if o.has("input_schema") =>
          Custom(
            o.str("name"),
            o.get[JSONValue]("input_schema"),
            o.strOpt("description"),
            o.opt[CacheControl]("cache_control")
          )
        case _ =>
          Unknown(o.value)
  )

end Tool

enum ToolChoice:
  case Auto(disableParallelToolUse: Option[Boolean] = None)
  case AnyTool(disableParallelToolUse: Option[Boolean] = None)
  case Tool(name: String, disableParallelToolUse: Option[Boolean] = None)
  case NoTool

object ToolChoice:
  given JsonCodec[ToolChoice] = JsonCodec.of(
    c =>
      def p(x: Option[Boolean]) = Json.opt("disable_parallel_tool_use", x)
      c match
        case Auto(x) =>
          Json.obj(Json.field("type", "auto"), p(x))
        case AnyTool(x) =>
          Json.obj(Json.field("type", "any"), p(x))
        case Tool(n, x) =>
          Json.obj(Json.field("type", "tool"), Json.field("name", n), p(x))
        case NoTool =>
          Json.obj(Json.field("type", "none"))
    ,
    v =>
      val o = JsonObj(v)
      val p = o.opt[Boolean]("disable_parallel_tool_use")
      o.str("type") match
        case "auto" =>
          Auto(p)
        case "any" =>
          AnyTool(p)
        case "tool" =>
          Tool(o.str("name"), p)
        case "none" =>
          NoTool
        case other =>
          throw JsonCodecException(s"Unknown tool_choice type: ${other}")
  )

end ToolChoice

/**
  * Extended thinking: `{"type": "enabled", "budget_tokens": N}`, `{"type": "disabled"}`, or
  * `{"type": "adaptive"}`
  */
case class ThinkingConfig(`type`: String, budgetTokens: Option[Int] = None)

object ThinkingConfig:
  def enabled(budgetTokens: Int): ThinkingConfig = ThinkingConfig("enabled", Some(budgetTokens))
  val disabled: ThinkingConfig                   = ThinkingConfig("disabled")
  val adaptive: ThinkingConfig                   = ThinkingConfig("adaptive")

  given JsonCodec[ThinkingConfig] = JsonCodec.obj(
    t => Seq(Json.field("type", t.`type`), Json.opt("budget_tokens", t.budgetTokens)),
    o => ThinkingConfig(o.str("type"), o.opt[Int]("budget_tokens"))
  )

/**
  * A Messages API request.
  *
  * @param extra
  *   request fields this version does not model (e.g. `context_management`, `mcp_servers`,
  *   `container`), sent as they are
  */
case class MessagesRequest(
    model: String,
    maxTokens: Int,
    messages: Seq[Message],
    system: Seq[ContentBlock] = Nil,
    tools: Seq[Tool] = Nil,
    toolChoice: Option[ToolChoice] = None,
    thinking: Option[ThinkingConfig] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    topK: Option[Int] = None,
    stopSequences: Seq[String] = Nil,
    stream: Option[Boolean] = None,
    metadata: Option[JSONValue] = None,
    serviceTier: Option[String] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def withModel(m: String): MessagesRequest            = copy(model = m)
  def withMaxTokens(n: Int): MessagesRequest           = copy(maxTokens = n)
  def withMessages(m: Seq[Message]): MessagesRequest   = copy(messages = m)
  def withSystem(text: String): MessagesRequest        = copy(system = Seq(ContentBlock.Text(text)))
  def withTools(t: Seq[Tool]): MessagesRequest         = copy(tools = t)
  def withToolChoice(c: ToolChoice): MessagesRequest   = copy(toolChoice = Some(c))
  def noToolChoice(): MessagesRequest                  = copy(toolChoice = None)
  def withThinking(t: ThinkingConfig): MessagesRequest = copy(thinking = Some(t))
  def noThinking(): MessagesRequest                    = copy(thinking = None)
  def withTemperature(t: Double): MessagesRequest      = copy(temperature = Some(t))
  def noTemperature(): MessagesRequest                 = copy(temperature = None)
  def withStream(enabled: Boolean): MessagesRequest    = copy(stream = Some(enabled))
  def withStopSequences(s: Seq[String]): MessagesRequest    = copy(stopSequences = s)
  def withExtra(key: String, v: JSONValue): MessagesRequest = copy(extra = extra + (key -> v))

end MessagesRequest

object MessagesRequest:
  private val known = Set(
    "model",
    "max_tokens",
    "messages",
    "system",
    "tools",
    "tool_choice",
    "thinking",
    "temperature",
    "top_p",
    "top_k",
    "stop_sequences",
    "stream",
    "metadata",
    "service_tier"
  )

  given JsonCodec[MessagesRequest] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("model", r.model),
        Json.field("max_tokens", r.maxTokens),
        Json.field("messages", r.messages),
        Json.nonEmpty("system", r.system),
        Json.nonEmpty("tools", r.tools),
        Json.opt("tool_choice", r.toolChoice),
        Json.opt("thinking", r.thinking),
        Json.opt("temperature", r.temperature),
        Json.opt("top_p", r.topP),
        Json.opt("top_k", r.topK),
        Json.nonEmpty("stop_sequences", r.stopSequences),
        Json.opt("stream", r.stream),
        Json.opt("metadata", r.metadata),
        Json.opt("service_tier", r.serviceTier)
      ) ++ Json.extra(r.extra),
    o =>
      MessagesRequest(
        model = o.strOrEmpty("model"),
        maxTokens = o.getOrElse[Int]("max_tokens", 0),
        messages = o.seq[Message]("messages"),
        system = ContentBlock.blocksOf(o.raw("system")),
        tools = o.seq[Tool]("tools"),
        toolChoice = o.opt[ToolChoice]("tool_choice"),
        thinking = o.opt[ThinkingConfig]("thinking"),
        temperature = o.opt[Double]("temperature"),
        topP = o.opt[Double]("top_p"),
        topK = o.opt[Int]("top_k"),
        stopSequences = o.seq[String]("stop_sequences"),
        stream = o.opt[Boolean]("stream"),
        metadata = o.opt[JSONValue]("metadata"),
        serviceTier = o.strOpt("service_tier"),
        extra = o.extra(known)
      )
  )

end MessagesRequest

/** Values of `stop_reason` */
object StopReason:
  val EndTurn: String                    = "end_turn"
  val MaxTokens: String                  = "max_tokens"
  val StopSequence: String               = "stop_sequence"
  val ToolUse: String                    = "tool_use"
  val PauseTurn: String                  = "pause_turn"
  val Refusal: String                    = "refusal"
  val ModelContextWindowExceeded: String = "model_context_window_exceeded"

/**
  * Token usage. `extra` keeps fields such as `server_tool_use`, `cache_creation`, and
  * `service_tier`.
  */
case class Usage(
    inputTokens: Long = 0,
    outputTokens: Long = 0,
    cacheCreationInputTokens: Option[Long] = None,
    cacheReadInputTokens: Option[Long] = None,
    extra: Map[String, JSONValue] = Map.empty
)

object Usage:
  private val known = Set(
    "input_tokens",
    "output_tokens",
    "cache_creation_input_tokens",
    "cache_read_input_tokens"
  )

  given JsonCodec[Usage] = JsonCodec.obj(
    u =>
      Seq(
        Json.field("input_tokens", u.inputTokens),
        Json.field("output_tokens", u.outputTokens),
        Json.opt("cache_creation_input_tokens", u.cacheCreationInputTokens),
        Json.opt("cache_read_input_tokens", u.cacheReadInputTokens)
      ) ++ Json.extra(u.extra),
    o =>
      Usage(
        o.getOrElse[Long]("input_tokens", 0L),
        o.getOrElse[Long]("output_tokens", 0L),
        o.opt[Long]("cache_creation_input_tokens"),
        o.opt[Long]("cache_read_input_tokens"),
        o.extra(known)
      )
  )

end Usage

/** A Messages API response, `{"type": "message", ...}` */
case class MessagesResponse(
    id: String,
    model: String,
    content: Seq[ContentBlock],
    stopReason: Option[String] = None,
    stopSequence: Option[String] = None,
    usage: Usage = Usage(),
    role: Role = Role.Assistant,
    extra: Map[String, JSONValue] = Map.empty
):
  /** The concatenated text of the Text blocks */
  def text: String = Message(role, content).text

  def toolUses: Seq[ContentBlock.ToolUse] = content.collect { case t: ContentBlock.ToolUse =>
    t
  }

object MessagesResponse:
  private val known = Set(
    "id",
    "type",
    "role",
    "model",
    "content",
    "stop_reason",
    "stop_sequence",
    "usage"
  )

  given JsonCodec[MessagesResponse] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("id", r.id),
        Json.field("type", "message"),
        Json.field("role", r.role),
        Json.field("model", r.model),
        Json.field("content", r.content),
        // The API always writes these two keys, as null while unknown
        "stop_reason"   -> Some(r.stopReason.map(JSONString(_)).getOrElse(JSONNull())),
        "stop_sequence" -> Some(r.stopSequence.map(JSONString(_)).getOrElse(JSONNull())),
        Json.field("usage", r.usage)
      ) ++ Json.extra(r.extra),
    o =>
      MessagesResponse(
        id = o.strOrEmpty("id"),
        model = o.strOrEmpty("model"),
        content = o.seq[ContentBlock]("content"),
        stopReason = o.strOpt("stop_reason"),
        stopSequence = o.strOpt("stop_sequence"),
        usage = o.getOrElse[Usage]("usage", Usage()),
        role = o.getOrElse[Role]("role", Role.Assistant),
        extra = o.extra(known)
      )
  )

end MessagesResponse

/** `POST /v1/messages/count_tokens` response */
case class CountTokensResponse(inputTokens: Long)

object CountTokensResponse:
  given JsonCodec[CountTokensResponse] = JsonCodec.obj(
    r => Seq(Json.field("input_tokens", r.inputTokens)),
    o => CountTokensResponse(o.get[Long]("input_tokens"))
  )

/**
  * An API error, `{"type": "error", "error": {"type": "overloaded_error", "message": ...}}`
  */
case class ErrorDetail(`type`: String, message: String)

object ErrorDetail:
  given JsonCodec[ErrorDetail] = JsonCodec.obj(
    e => Seq(Json.field("type", e.`type`), Json.field("message", e.message)),
    o => ErrorDetail(o.strOrEmpty("type"), o.strOrEmpty("message"))
  )

  /** Error types and the HTTP status each one is returned with */
  val InvalidRequest: String  = "invalid_request_error" // 400
  val Authentication: String  = "authentication_error"  // 401
  val Permission: String      = "permission_error"      // 403
  val NotFound: String        = "not_found_error"       // 404
  val RequestTooLarge: String = "request_too_large"     // 413
  val RateLimit: String       = "rate_limit_error"      // 429
  val Api: String             = "api_error"             // 500
  val Overloaded: String      = "overloaded_error"      // 529

  def httpStatusOf(errorType: String): Int =
    errorType match
      case InvalidRequest =>
        400
      case Authentication =>
        401
      case Permission =>
        403
      case NotFound =>
        404
      case RequestTooLarge =>
        413
      case RateLimit =>
        429
      case Overloaded =>
        529
      case _ =>
        500

end ErrorDetail

case class ErrorResponse(error: ErrorDetail, requestId: Option[String] = None)

object ErrorResponse:
  given JsonCodec[ErrorResponse] = JsonCodec.obj(
    e =>
      Seq(
        Json.field("type", "error"),
        Json.field("error", e.error),
        Json.opt("request_id", e.requestId)
      ),
    o => ErrorResponse(o.get[ErrorDetail]("error"), o.strOpt("request_id"))
  )
