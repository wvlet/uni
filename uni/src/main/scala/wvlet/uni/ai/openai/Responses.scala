package wvlet.uni.ai.openai

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonCodecException
import wvlet.uni.ai.JsonObj
import wvlet.uni.http.ServerSentEvent
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

/**
  * Models of the OpenAI Responses API (`POST /v1/responses`). See
  * https://platform.openai.com/docs/api-reference/responses
  *
  * Item, content, tool, and event types this version does not model decode to an `Unknown` case
  * that keeps the raw JSON.
  */
sealed trait ContentPart

object ContentPart:
  case class InputText(text: String) extends ContentPart

  /** An image by URL (http(s) or `data:` URL) or uploaded file id */
  case class InputImage(
      imageUrl: Option[String] = None,
      fileId: Option[String] = None,
      detail: Option[String] = None
  ) extends ContentPart

  case class InputFile(
      fileId: Option[String] = None,
      fileData: Option[String] = None,
      filename: Option[String] = None,
      fileUrl: Option[String] = None
  ) extends ContentPart

  case class OutputText(text: String, annotations: Seq[JSONValue] = Nil) extends ContentPart
  case class Refusal(refusal: String)                                    extends ContentPart
  case class Unknown(raw: JSONObject)                                    extends ContentPart

  given JsonCodec[ContentPart] = JsonCodec.of(
    {
      case InputText(t) =>
        Json.obj(Json.field("type", "input_text"), Json.field("text", t))
      case InputImage(url, fid, detail) =>
        Json.obj(
          Json.field("type", "input_image"),
          Json.opt("image_url", url),
          Json.opt("file_id", fid),
          Json.opt("detail", detail)
        )
      case InputFile(fid, data, name, url) =>
        Json.obj(
          Json.field("type", "input_file"),
          Json.opt("file_id", fid),
          Json.opt("file_data", data),
          Json.opt("filename", name),
          Json.opt("file_url", url)
        )
      case OutputText(t, ann) =>
        Json.obj(
          Json.field("type", "output_text"),
          Json.field("text", t),
          Json.field("annotations", ann)
        )
      case Refusal(r) =>
        Json.obj(Json.field("type", "refusal"), Json.field("refusal", r))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOrEmpty("type") match
        case "input_text" =>
          InputText(o.str("text"))
        case "input_image" =>
          InputImage(o.strOpt("image_url"), o.strOpt("file_id"), o.strOpt("detail"))
        case "input_file" =>
          InputFile(
            o.strOpt("file_id"),
            o.strOpt("file_data"),
            o.strOpt("filename"),
            o.strOpt("file_url")
          )
        case "output_text" =>
          OutputText(o.str("text"), o.seq[JSONValue]("annotations"))
        case "refusal" =>
          Refusal(o.str("refusal"))
        case _ =>
          Unknown(o.value)
  )

  /** Content given as a string or an array of parts */
  private[openai] def partsOf(
      v: Option[JSONValue],
      textPart: String => ContentPart
  ): Seq[ContentPart] =
    v match
      case None =>
        Nil
      case Some(JSONString(s)) =>
        Seq(textPart(s))
      case Some(arr: JSONArray) =>
        JsonCodec[Seq[ContentPart]].decode(arr)
      case Some(other) =>
        throw JsonCodecException(s"Expected a string or an array of parts, got ${other.toJSON}")

end ContentPart

/** A reasoning summary or reasoning text part, `{"type": "summary_text", "text": ...}` */
case class ReasoningText(text: String, `type`: String = "summary_text")

object ReasoningText:
  given JsonCodec[ReasoningText] = JsonCodec.obj(
    r => Seq(Json.field("type", r.`type`), Json.field("text", r.text)),
    o => ReasoningText(o.strOrEmpty("text"), o.getOrElse("type", "summary_text"))
  )

/**
  * An input or output item of a response: a message, a function call, its output, or reasoning.
  * `Unknown` keeps built-in tool calls (`web_search_call`, `mcp_call`, `custom_tool_call`, ...) and
  * `item_reference`.
  */
sealed trait Item

object Item:
  /**
    * A message. Roles: `user`, `assistant`, `system`, `developer`. Input messages hold `input_*`
    * parts; assistant output holds `output_text` and `refusal`.
    */
  case class Message(
      role: String,
      content: Seq[ContentPart],
      id: Option[String] = None,
      status: Option[String] = None
  ) extends Item:
    def text: String =
      content
        .collect {
          case ContentPart.OutputText(t, _) =>
            t
          case ContentPart.InputText(t) =>
            t
        }
        .mkString

  case class FunctionCall(
      callId: String,
      name: String,
      arguments: String,
      id: Option[String] = None,
      status: Option[String] = None
  ) extends Item

  /** The output of a function call: a string, or an array of input parts */
  case class FunctionCallOutput(
      callId: String,
      output: JSONValue,
      id: Option[String] = None,
      status: Option[String] = None
  ) extends Item

  /**
    * Model reasoning. `encryptedContent` is returned with
    * `include: ["reasoning.encrypted_content"]` and must be sent back unchanged for stateless
    * multi-turn use.
    */
  case class Reasoning(
      id: String,
      summary: Seq[ReasoningText] = Nil,
      content: Seq[ReasoningText] = Nil,
      encryptedContent: Option[String] = None,
      status: Option[String] = None
  ) extends Item

  case class Unknown(raw: JSONObject) extends Item

  def user(text: String): Message = Message("user", Seq(ContentPart.InputText(text)))

  given codec: JsonCodec[Item] = JsonCodec.of(encode, decode)

  private def encode(i: Item): JSONValue =
    i match
      case Message(role, content, id, status) =>
        Json.obj(
          Json.field("type", "message"),
          Json.opt("id", id),
          Json.field("role", role),
          Json.field("content", content),
          Json.opt("status", status)
        )
      case FunctionCall(callId, name, args, id, status) =>
        Json.obj(
          Json.field("type", "function_call"),
          Json.opt("id", id),
          Json.field("call_id", callId),
          Json.field("name", name),
          Json.field("arguments", args),
          Json.opt("status", status)
        )
      case FunctionCallOutput(callId, output, id, status) =>
        Json.obj(
          Json.field("type", "function_call_output"),
          Json.opt("id", id),
          Json.field("call_id", callId),
          Json.field("output", output),
          Json.opt("status", status)
        )
      case Reasoning(id, summary, content, enc, status) =>
        Json.obj(
          Json.field("type", "reasoning"),
          Json.field("id", id),
          Json.field("summary", summary),
          Json.nonEmpty("content", content),
          Json.opt("encrypted_content", enc),
          Json.opt("status", status)
        )
      case Unknown(raw) =>
        raw

  private def decode(v: JSONValue): Item =
    val o = JsonObj(v)
    o.strOpt("type") match
      case Some("message") | None if o.has("role") =>
        val role = o.str("role")
        Message(
          role,
          ContentPart.partsOf(
            o.raw("content"),
            t =>
              if role == "assistant" then
                ContentPart.OutputText(t)
              else
                ContentPart.InputText(t)
          ),
          o.strOpt("id"),
          o.strOpt("status")
        )
      case Some("function_call") =>
        FunctionCall(
          o.str("call_id"),
          o.str("name"),
          o.strOrEmpty("arguments"),
          o.strOpt("id"),
          o.strOpt("status")
        )
      case Some("function_call_output") =>
        FunctionCallOutput(
          o.str("call_id"),
          o.getOrElse[JSONValue]("output", JSONString("")),
          o.strOpt("id"),
          o.strOpt("status")
        )
      case Some("reasoning") =>
        Reasoning(
          o.strOrEmpty("id"),
          o.seq[ReasoningText]("summary"),
          o.seq[ReasoningText]("content"),
          o.strOpt("encrypted_content"),
          o.strOpt("status")
        )
      case _ =>
        Unknown(o.value)
    end match

  end decode

end Item

/** A tool. `Function` is a client function; `Unknown` keeps built-in tools (`web_search`, ...) */
sealed trait Tool

object Tool:
  case class Function(
      name: String,
      parameters: Option[JSONValue] = None,
      description: Option[String] = None,
      strict: Option[Boolean] = None
  ) extends Tool

  case class Unknown(raw: JSONObject) extends Tool

  given JsonCodec[Tool] = JsonCodec.of(
    {
      case Function(name, params, desc, strict) =>
        Json.obj(
          Json.field("type", "function"),
          Json.field("name", name),
          Json.opt("description", desc),
          Json.opt("parameters", params),
          Json.opt("strict", strict)
        )
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOrEmpty("type") match
        case "function" =>
          Function(
            o.str("name"),
            o.opt[JSONValue]("parameters"),
            o.strOpt("description"),
            o.opt[Boolean]("strict")
          )
        case _ =>
          Unknown(o.value)
  )

end Tool

/** `tool_choice`: `"auto"`, `"none"`, `"required"`, or a specific function */
enum ToolChoice:
  case Auto
  case NoTool
  case Required
  case Function(name: String)
  case Unknown(raw: JSONValue)

object ToolChoice:
  given JsonCodec[ToolChoice] = JsonCodec.of(
    {
      case Auto =>
        JSONString("auto")
      case NoTool =>
        JSONString("none")
      case Required =>
        JSONString("required")
      case Function(name) =>
        Json.obj(Json.field("type", "function"), Json.field("name", name))
      case Unknown(raw) =>
        raw
    },
    {
      case JSONString("auto") =>
        Auto
      case JSONString("none") =>
        NoTool
      case JSONString("required") =>
        Required
      case v @ JsonObj(o) if o.strOpt("type").contains("function") =>
        // Chat Completions nests the name under "function"
        Function(o.strOpt("name").orElse(o.objOpt("function").map(_.str("name"))).getOrElse(""))
      case other =>
        Unknown(other)
    }
  )

end ToolChoice

/** `reasoning`: effort (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`) and summary mode */
case class ReasoningConfig(effort: Option[String] = None, summary: Option[String] = None)

object ReasoningConfig:
  given JsonCodec[ReasoningConfig] = JsonCodec.obj(
    r => Seq(Json.opt("effort", r.effort), Json.opt("summary", r.summary)),
    o => ReasoningConfig(o.strOpt("effort"), o.strOpt("summary"))
  )

/**
  * A Responses API request. `input` given as a string decodes to one user message.
  *
  * @param extra
  *   request fields this version does not model (`text`, `truncation`, `prompt`, ...), sent as they
  *   are
  */
case class ResponsesRequest(
    model: String,
    input: Seq[Item],
    instructions: Option[String] = None,
    tools: Seq[Tool] = Nil,
    toolChoice: Option[ToolChoice] = None,
    parallelToolCalls: Option[Boolean] = None,
    maxOutputTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    reasoning: Option[ReasoningConfig] = None,
    stream: Option[Boolean] = None,
    store: Option[Boolean] = None,
    previousResponseId: Option[String] = None,
    include: Seq[String] = Nil,
    metadata: Option[JSONValue] = None,
    serviceTier: Option[String] = None,
    promptCacheKey: Option[String] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def withModel(m: String): ResponsesRequest                 = copy(model = m)
  def withInput(i: Seq[Item]): ResponsesRequest              = copy(input = i)
  def withInstructions(s: String): ResponsesRequest          = copy(instructions = Some(s))
  def noInstructions(): ResponsesRequest                     = copy(instructions = None)
  def withTools(t: Seq[Tool]): ResponsesRequest              = copy(tools = t)
  def withToolChoice(c: ToolChoice): ResponsesRequest        = copy(toolChoice = Some(c))
  def withMaxOutputTokens(n: Int): ResponsesRequest          = copy(maxOutputTokens = Some(n))
  def withReasoning(r: ReasoningConfig): ResponsesRequest    = copy(reasoning = Some(r))
  def withStream(enabled: Boolean): ResponsesRequest         = copy(stream = Some(enabled))
  def withStore(enabled: Boolean): ResponsesRequest          = copy(store = Some(enabled))
  def withInclude(i: Seq[String]): ResponsesRequest          = copy(include = i)
  def withExtra(key: String, v: JSONValue): ResponsesRequest = copy(extra = extra + (key -> v))

end ResponsesRequest

object ResponsesRequest:
  private val known = Set(
    "model",
    "input",
    "instructions",
    "tools",
    "tool_choice",
    "parallel_tool_calls",
    "max_output_tokens",
    "temperature",
    "top_p",
    "reasoning",
    "stream",
    "store",
    "previous_response_id",
    "include",
    "metadata",
    "service_tier",
    "prompt_cache_key"
  )

  given JsonCodec[ResponsesRequest] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("model", r.model),
        Json.field("input", r.input),
        Json.opt("instructions", r.instructions),
        Json.nonEmpty("tools", r.tools),
        Json.opt("tool_choice", r.toolChoice),
        Json.opt("parallel_tool_calls", r.parallelToolCalls),
        Json.opt("max_output_tokens", r.maxOutputTokens),
        Json.opt("temperature", r.temperature),
        Json.opt("top_p", r.topP),
        Json.opt("reasoning", r.reasoning),
        Json.opt("stream", r.stream),
        Json.opt("store", r.store),
        Json.opt("previous_response_id", r.previousResponseId),
        Json.nonEmpty("include", r.include),
        Json.opt("metadata", r.metadata),
        Json.opt("service_tier", r.serviceTier),
        Json.opt("prompt_cache_key", r.promptCacheKey)
      ) ++ Json.extra(r.extra),
    o =>
      ResponsesRequest(
        model = o.strOrEmpty("model"),
        input =
          o.raw("input") match
            case Some(JSONString(s)) =>
              Seq(Item.user(s))
            case _ =>
              o.seq[Item]("input")
        ,
        instructions = o.strOpt("instructions"),
        tools = o.seq[Tool]("tools"),
        toolChoice = o.opt[ToolChoice]("tool_choice"),
        parallelToolCalls = o.opt[Boolean]("parallel_tool_calls"),
        maxOutputTokens = o.opt[Int]("max_output_tokens"),
        temperature = o.opt[Double]("temperature"),
        topP = o.opt[Double]("top_p"),
        reasoning = o.opt[ReasoningConfig]("reasoning"),
        stream = o.opt[Boolean]("stream"),
        store = o.opt[Boolean]("store"),
        previousResponseId = o.strOpt("previous_response_id"),
        include = o.seq[String]("include"),
        metadata = o.opt[JSONValue]("metadata"),
        serviceTier = o.strOpt("service_tier"),
        promptCacheKey = o.strOpt("prompt_cache_key"),
        extra = o.extra(known)
      )
  )

end ResponsesRequest

/** Token usage of a response */
case class ResponseUsage(
    inputTokens: Long = 0,
    outputTokens: Long = 0,
    totalTokens: Long = 0,
    cachedTokens: Option[Long] = None,
    reasoningTokens: Option[Long] = None
)

object ResponseUsage:
  given JsonCodec[ResponseUsage] = JsonCodec.obj(
    u =>
      Seq(
        Json.field("input_tokens", u.inputTokens),
        "input_tokens_details" ->
          Some(Json.obj(Json.field("cached_tokens", u.cachedTokens.getOrElse(0L)))),
        Json.field("output_tokens", u.outputTokens),
        "output_tokens_details" ->
          Some(Json.obj(Json.field("reasoning_tokens", u.reasoningTokens.getOrElse(0L)))),
        Json.field("total_tokens", u.totalTokens)
      ),
    o =>
      ResponseUsage(
        o.getOrElse[Long]("input_tokens", 0L),
        o.getOrElse[Long]("output_tokens", 0L),
        o.getOrElse[Long]("total_tokens", 0L),
        o.objOpt("input_tokens_details").flatMap(_.opt[Long]("cached_tokens")),
        o.objOpt("output_tokens_details").flatMap(_.opt[Long]("reasoning_tokens"))
      )
  )

end ResponseUsage

/** Values of `status` */
object ResponseStatus:
  val Completed: String  = "completed"
  val Incomplete: String = "incomplete"
  val Failed: String     = "failed"
  val InProgress: String = "in_progress"
  val Queued: String     = "queued"
  val Cancelled: String  = "cancelled"

/** A response object, `{"object": "response", ...}` */
case class Response(
    id: String,
    model: String,
    output: Seq[Item] = Nil,
    status: String = ResponseStatus.Completed,
    createdAt: Long = 0,
    usage: Option[ResponseUsage] = None,
    incompleteReason: Option[String] = None,
    error: Option[ResponseError] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  /** The concatenated `output_text` of the assistant messages */
  def outputText: String =
    output
      .collect { case m: Item.Message =>
        m.text
      }
      .mkString

  def functionCalls: Seq[Item.FunctionCall] = output.collect { case f: Item.FunctionCall =>
    f
  }

object Response:
  private val known = Set(
    "id",
    "object",
    "model",
    "output",
    "status",
    "created_at",
    "usage",
    "incomplete_details",
    "error"
  )

  given JsonCodec[Response] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("id", r.id),
        Json.field("object", "response"),
        Json.field("created_at", r.createdAt),
        Json.field("status", r.status),
        Json.field("model", r.model),
        Json.field("output", r.output),
        Json.opt("usage", r.usage),
        "incomplete_details" ->
          Some(
            r.incompleteReason.map(x => Json.obj(Json.field("reason", x))).getOrElse(JSONNull())
          ),
        "error" -> Some(r.error.map(JsonCodec[ResponseError].encode).getOrElse(JSONNull()))
      ) ++ Json.extra(r.extra),
    o =>
      Response(
        id = o.strOrEmpty("id"),
        model = o.strOrEmpty("model"),
        output = o.seq[Item]("output"),
        status = o.getOrElse("status", ResponseStatus.Completed),
        createdAt = o.getOrElse[Long]("created_at", 0L),
        usage = o.opt[ResponseUsage]("usage"),
        incompleteReason = o.objOpt("incomplete_details").flatMap(_.strOpt("reason")),
        error = o.opt[ResponseError]("error"),
        extra = o.extra(known)
      )
  )

end Response

/** An error object, also the body of the `error` stream event */
case class ResponseError(message: String, code: Option[String] = None, param: Option[String] = None)

object ResponseError:
  given JsonCodec[ResponseError] = JsonCodec.obj(
    e =>
      Seq(Json.opt("code", e.code), Json.field("message", e.message), Json.opt("param", e.param)),
    o =>
      ResponseError(
        o.strOrEmpty("message"),
        o.strOpt("code").orElse(o.strOpt("type")),
        o.strOpt("param")
      )
  )

/**
  * An event of a streaming response. The SSE `event:` name equals the `type` field.
  */
sealed trait ResponseStreamEvent:
  def eventType: String
  def sequenceNumber: Long

  def toServerSentEvent: ServerSentEvent = ServerSentEvent(
    event = Some(eventType),
    data = ResponseStreamEvent.codec.toJson(this)
  )

object ResponseStreamEvent:
  /**
    * `response.created`, `response.in_progress`, `response.completed`, `response.incomplete`,
    * `response.failed`, or `response.queued`
    */
  case class Lifecycle(eventType: String, response: Response, sequenceNumber: Long = 0)
      extends ResponseStreamEvent

  /** `response.output_item.added` or `response.output_item.done` */
  case class OutputItem(eventType: String, outputIndex: Int, item: Item, sequenceNumber: Long = 0)
      extends ResponseStreamEvent

  /** `response.content_part.added` or `response.content_part.done` */
  case class ContentPartEvent(
      eventType: String,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      part: ContentPart,
      sequenceNumber: Long = 0
  ) extends ResponseStreamEvent

  /**
    * A text delta: `response.output_text.delta`, `response.refusal.delta`,
    * `response.function_call_arguments.delta`, `response.reasoning_summary_text.delta`, or
    * `response.reasoning_text.delta`. `index` is the content index, or the summary index for
    * reasoning summaries.
    */
  case class TextDelta(
      eventType: String,
      itemId: String,
      outputIndex: Int,
      index: Int,
      delta: String,
      sequenceNumber: Long = 0
  ) extends ResponseStreamEvent

  /** The `.done` counterpart of [[TextDelta]], carrying the full text (or arguments) */
  case class TextDone(
      eventType: String,
      itemId: String,
      outputIndex: Int,
      index: Int,
      text: String,
      sequenceNumber: Long = 0
  ) extends ResponseStreamEvent

  /** `response.reasoning_summary_part.added` or `.done` */
  case class ReasoningSummaryPart(
      eventType: String,
      itemId: String,
      outputIndex: Int,
      summaryIndex: Int,
      part: ReasoningText,
      sequenceNumber: Long = 0
  ) extends ResponseStreamEvent

  case class Error(error: ResponseError, sequenceNumber: Long = 0) extends ResponseStreamEvent:
    def eventType = "error"

  case class Unknown(raw: JSONObject) extends ResponseStreamEvent:
    def eventType      = JsonObj(raw).strOrEmpty("type")
    def sequenceNumber = JsonObj(raw).getOrElse[Long]("sequence_number", 0L)

  val OutputTextDelta: String        = "response.output_text.delta"
  val OutputTextDone: String         = "response.output_text.done"
  val RefusalDelta: String           = "response.refusal.delta"
  val RefusalDone: String            = "response.refusal.done"
  val FunctionArgumentsDelta: String = "response.function_call_arguments.delta"
  val FunctionArgumentsDone: String  = "response.function_call_arguments.done"
  val ReasoningSummaryDelta: String  = "response.reasoning_summary_text.delta"
  val ReasoningSummaryDone: String   = "response.reasoning_summary_text.done"
  val ReasoningTextDelta: String     = "response.reasoning_text.delta"
  val ReasoningTextDone: String      = "response.reasoning_text.done"

  private val lifecycleTypes = Set(
    "response.created",
    "response.in_progress",
    "response.completed",
    "response.incomplete",
    "response.failed",
    "response.queued"
  )

  private val deltaTypes = Set(
    OutputTextDelta,
    RefusalDelta,
    FunctionArgumentsDelta,
    ReasoningSummaryDelta,
    ReasoningTextDelta
  )

  private val doneTypes = Set(
    OutputTextDone,
    RefusalDone,
    FunctionArgumentsDone,
    ReasoningSummaryDone,
    ReasoningTextDone
  )

  // The key holding the index and the full text differ by event type
  private def indexKey(t: String): Option[String] =
    if t.startsWith("response.reasoning_summary") then
      Some("summary_index")
    else if t.startsWith("response.function_call_arguments") then
      None
    else
      Some("content_index")

  private def doneKey(t: String): String =
    if t == FunctionArgumentsDone then
      "arguments"
    else if t == RefusalDone then
      "refusal"
    else
      "text"

  given codec: JsonCodec[ResponseStreamEvent] = JsonCodec.of(encode, decode)

  private def encode(e: ResponseStreamEvent): JSONValue =
    def ev(fields: (String, Option[JSONValue])*): JSONValue = Json.obj(
      (
        (Json.field("type", e.eventType) +: fields) :+
          Json.field("sequence_number", e.sequenceNumber)
      )*
    )
    e match
      case Lifecycle(_, r, _) =>
        ev(Json.field("response", r))
      case OutputItem(_, idx, item, _) =>
        ev(Json.field("output_index", idx), Json.field("item", item))
      case ContentPartEvent(_, id, oi, ci, part, _) =>
        ev(
          Json.field("item_id", id),
          Json.field("output_index", oi),
          Json.field("content_index", ci),
          Json.field("part", part)
        )
      case TextDelta(t, id, oi, i, d, _) =>
        ev(
          (
            Seq(Json.field("item_id", id), Json.field("output_index", oi)) ++
              indexKey(t).map(k => Json.field(k, i)) :+ Json.field("delta", d)
          )*
        )
      case TextDone(t, id, oi, i, text, _) =>
        ev(
          (
            Seq(Json.field("item_id", id), Json.field("output_index", oi)) ++
              indexKey(t).map(k => Json.field(k, i)) :+ Json.field(doneKey(t), text)
          )*
        )
      case ReasoningSummaryPart(_, id, oi, si, part, _) =>
        ev(
          Json.field("item_id", id),
          Json.field("output_index", oi),
          Json.field("summary_index", si),
          Json.field("part", part)
        )
      case Error(err, _) =>
        ev(
          Json.opt("code", err.code),
          Json.field("message", err.message),
          Json.opt("param", err.param)
        )
      case Unknown(raw) =>
        raw
    end match

  end encode

  private def decode(v: JSONValue): ResponseStreamEvent =
    val o   = JsonObj(v)
    val t   = o.strOrEmpty("type")
    val seq = o.getOrElse[Long]("sequence_number", 0L)
    def idx = indexKey(t).flatMap(k => o.opt[Int](k)).getOrElse(0)
    if lifecycleTypes.contains(t) then
      Lifecycle(t, o.get[Response]("response"), seq)
    else if t == "response.output_item.added" || t == "response.output_item.done" then
      OutputItem(t, o.get[Int]("output_index"), o.get[Item]("item"), seq)
    else if t == "response.content_part.added" || t == "response.content_part.done" then
      ContentPartEvent(
        t,
        o.strOrEmpty("item_id"),
        o.get[Int]("output_index"),
        o.getOrElse[Int]("content_index", 0),
        o.get[ContentPart]("part"),
        seq
      )
    else if deltaTypes.contains(t) then
      TextDelta(
        t,
        o.strOrEmpty("item_id"),
        o.getOrElse[Int]("output_index", 0),
        idx,
        o.strOrEmpty("delta"),
        seq
      )
    else if doneTypes.contains(t) then
      TextDone(
        t,
        o.strOrEmpty("item_id"),
        o.getOrElse[Int]("output_index", 0),
        idx,
        o.strOrEmpty(doneKey(t)),
        seq
      )
    else if t == "response.reasoning_summary_part.added" ||
      t == "response.reasoning_summary_part.done"
    then
      ReasoningSummaryPart(
        t,
        o.strOrEmpty("item_id"),
        o.getOrElse[Int]("output_index", 0),
        o.getOrElse[Int]("summary_index", 0),
        o.get[ReasoningText]("part"),
        seq
      )
    else if t == "error" then
      Error(ResponseError(o.strOrEmpty("message"), o.strOpt("code"), o.strOpt("param")), seq)
    else
      Unknown(o.value)
    end if

  end decode

  def fromServerSentEvent(e: ServerSentEvent): ResponseStreamEvent = codec.decode(
    JSON.parseAny(e.data)
  )

end ResponseStreamEvent
