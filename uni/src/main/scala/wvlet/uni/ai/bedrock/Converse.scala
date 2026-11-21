package wvlet.uni.ai.bedrock

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonObj
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Models of the Amazon Bedrock Converse API (`POST /model/{modelId}/converse` and
  * `/converse-stream`). See
  * https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html
  *
  * Content blocks are key-tagged unions (`{"text": ...}`, `{"toolUse": ...}`).
  */
sealed trait ContentBlock

object ContentBlock:
  case class Text(text: String) extends ContentBlock

  /** Image bytes (base64) in `format` `png`, `jpeg`, `gif`, or `webp` */
  case class Image(format: String, bytes: String) extends ContentBlock

  /** Document bytes (base64); `format` `pdf`, `csv`, `docx`, `txt`, `md`, ... */
  case class Document(format: String, name: String, bytes: String) extends ContentBlock

  case class ToolUse(toolUseId: String, name: String, input: JSONValue = JSONObject.empty)
      extends ContentBlock

  /**
    * A tool result; each content entry is `{"text"}`, `{"json"}`, `{"image"}`, or `{"document"}`.
    * `status` is `success` or `error`.
    */
  case class ToolResult(toolUseId: String, content: Seq[JSONValue], status: Option[String] = None)
      extends ContentBlock

  /** Model reasoning with its signature, or redacted reasoning (base64) */
  case class Reasoning(
      text: Option[String] = None,
      signature: Option[String] = None,
      redactedContent: Option[String] = None
  ) extends ContentBlock

  /** A prompt-caching checkpoint */
  case object CachePoint extends ContentBlock

  /** `guardContent`, `video`, `citationsContent`, and other block kinds */
  case class Unknown(raw: JSONObject) extends ContentBlock

  given codec: JsonCodec[ContentBlock] = JsonCodec.of(
    {
      case Text(t) =>
        Json.obj(Json.field("text", t))
      case Image(f, b) =>
        Json.obj(
          "image" ->
            Some(
              Json.obj(Json.field("format", f), "source" -> Some(Json.obj(Json.field("bytes", b))))
            )
        )
      case Document(f, n, b) =>
        Json.obj(
          "document" ->
            Some(
              Json.obj(
                Json.field("format", f),
                Json.field("name", n),
                "source" -> Some(Json.obj(Json.field("bytes", b)))
              )
            )
        )
      case ToolUse(id, name, input) =>
        Json.obj(
          "toolUse" ->
            Some(
              Json.obj(
                Json.field("toolUseId", id),
                Json.field("name", name),
                Json.field("input", input)
              )
            )
        )
      case ToolResult(id, content, status) =>
        Json.obj(
          "toolResult" ->
            Some(
              Json.obj(
                Json.field("toolUseId", id),
                Json.field("content", content),
                Json.opt("status", status)
              )
            )
        )
      case Reasoning(text, sig, redacted) =>
        val body =
          redacted match
            case Some(r) =>
              Json.obj(Json.field("redactedContent", r))
            case None =>
              Json.obj(
                "reasoningText" ->
                  Some(Json.obj(Json.field("text", text.getOrElse("")), Json.opt("signature", sig)))
              )
        Json.obj("reasoningContent" -> Some(body))
      case CachePoint =>
        Json.obj("cachePoint" -> Some(Json.obj(Json.field("type", "default"))))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      if o.has("text") then
        Text(o.str("text"))
      else if o.has("image") then
        val i = o.obj("image")
        Image(i.strOrEmpty("format"), i.objOpt("source").flatMap(_.strOpt("bytes")).getOrElse(""))
      else if o.has("document") then
        val d = o.obj("document")
        Document(
          d.strOrEmpty("format"),
          d.strOrEmpty("name"),
          d.objOpt("source").flatMap(_.strOpt("bytes")).getOrElse("")
        )
      else if o.has("toolUse") then
        val t = o.obj("toolUse")
        ToolUse(
          t.str("toolUseId"),
          t.str("name"),
          t.getOrElse[JSONValue]("input", JSONObject.empty)
        )
      else if o.has("toolResult") then
        val t = o.obj("toolResult")
        ToolResult(t.str("toolUseId"), t.seq[JSONValue]("content"), t.strOpt("status"))
      else if o.has("reasoningContent") then
        val r = o.obj("reasoningContent")
        r.strOpt("redactedContent") match
          case Some(red) =>
            Reasoning(redactedContent = Some(red))
          case None =>
            val t = r.objOpt("reasoningText")
            Reasoning(t.flatMap(_.strOpt("text")), t.flatMap(_.strOpt("signature")))
      else if o.has("cachePoint") then
        CachePoint
      else
        Unknown(o.value)
      end if
  )

end ContentBlock

case class Message(role: String, content: Seq[ContentBlock]):
  def text: String =
    content
      .collect { case ContentBlock.Text(t) =>
        t
      }
      .mkString

object Message:
  def user(text: String): Message = Message("user", Seq(ContentBlock.Text(text)))

  given JsonCodec[Message] = JsonCodec.obj(
    m => Seq(Json.field("role", m.role), Json.field("content", m.content)),
    o => Message(o.str("role"), o.seq[ContentBlock]("content"))
  )

/** A tool: `{"toolSpec": {name, description, inputSchema: {json}}}`, or a cache point */
sealed trait Tool

object Tool:
  case class Spec(name: String, inputSchema: JSONValue, description: Option[String] = None)
      extends Tool

  case object CachePoint              extends Tool
  case class Unknown(raw: JSONObject) extends Tool

  given JsonCodec[Tool] = JsonCodec.of(
    {
      case Spec(name, schema, desc) =>
        Json.obj(
          "toolSpec" ->
            Some(
              Json.obj(
                Json.field("name", name),
                Json.opt("description", desc),
                "inputSchema" -> Some(Json.obj(Json.field("json", schema)))
              )
            )
        )
      case CachePoint =>
        Json.obj("cachePoint" -> Some(Json.obj(Json.field("type", "default"))))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.objOpt("toolSpec") match
        case Some(s) =>
          Spec(
            s.str("name"),
            s.objOpt("inputSchema").flatMap(_.opt[JSONValue]("json")).getOrElse(JSONObject.empty),
            s.strOpt("description")
          )
        case None if o.has("cachePoint") =>
          CachePoint
        case None =>
          Unknown(o.value)
  )

end Tool

/** `toolChoice`: `{"auto": {}}`, `{"any": {}}`, or `{"tool": {"name": ...}}` */
enum ToolChoice:
  case Auto
  case AnyTool
  case Tool(name: String)

object ToolChoice:
  given JsonCodec[ToolChoice] = JsonCodec.of(
    {
      case Auto =>
        Json.obj("auto" -> Some(JSONObject.empty))
      case AnyTool =>
        Json.obj("any" -> Some(JSONObject.empty))
      case Tool(n) =>
        Json.obj("tool" -> Some(Json.obj(Json.field("name", n))))
    },
    v =>
      val o = JsonObj(v)
      if o.has("any") then
        AnyTool
      else if o.has("tool") then
        Tool(o.obj("tool").str("name"))
      else
        Auto
  )

case class InferenceConfig(
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: Seq[String] = Nil
)

object InferenceConfig:
  given JsonCodec[InferenceConfig] = JsonCodec.obj(
    c =>
      Seq(
        Json.opt("maxTokens", c.maxTokens),
        Json.opt("temperature", c.temperature),
        Json.opt("topP", c.topP),
        Json.nonEmpty("stopSequences", c.stopSequences)
      ),
    o =>
      InferenceConfig(
        o.opt[Int]("maxTokens"),
        o.opt[Double]("temperature"),
        o.opt[Double]("topP"),
        o.seq[String]("stopSequences")
      )
  )

/**
  * A Converse request. The model id goes in the URL.
  *
  * @param additionalModelRequestFields
  *   model-specific fields, e.g. `{"thinking": {"type": "enabled", "budget_tokens": 2048}}` for
  *   Claude
  * @param extra
  *   `guardrailConfig`, `performanceConfig`, `requestMetadata`, and other fields
  */
case class ConverseRequest(
    messages: Seq[Message],
    system: Seq[ContentBlock] = Nil,
    inferenceConfig: Option[InferenceConfig] = None,
    tools: Seq[Tool] = Nil,
    toolChoice: Option[ToolChoice] = None,
    additionalModelRequestFields: Option[JSONValue] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def withMessages(m: Seq[Message]): ConverseRequest = copy(messages = m)
  def withSystem(text: String): ConverseRequest      = copy(system = Seq(ContentBlock.Text(text)))
  def withInferenceConfig(c: InferenceConfig): ConverseRequest = copy(inferenceConfig = Some(c))
  def withTools(t: Seq[Tool]): ConverseRequest                 = copy(tools = t)
  def withToolChoice(c: ToolChoice): ConverseRequest           = copy(toolChoice = Some(c))
  def withAdditionalModelRequestFields(v: JSONValue): ConverseRequest = copy(
    additionalModelRequestFields = Some(v)
  )

  def withExtra(key: String, v: JSONValue): ConverseRequest = copy(extra = extra + (key -> v))

object ConverseRequest:
  private val known = Set(
    "messages",
    "system",
    "inferenceConfig",
    "toolConfig",
    "additionalModelRequestFields"
  )

  given JsonCodec[ConverseRequest] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("messages", r.messages),
        Json.nonEmpty("system", r.system),
        Json.opt("inferenceConfig", r.inferenceConfig),
        "toolConfig" -> (
          if r.tools.isEmpty then
            None
          else
            Some(Json.obj(Json.field("tools", r.tools), Json.opt("toolChoice", r.toolChoice)))
        ),
        Json.opt("additionalModelRequestFields", r.additionalModelRequestFields)
      ) ++ Json.extra(r.extra),
    o =>
      val tc = o.objOpt("toolConfig")
      ConverseRequest(
        messages = o.seq[Message]("messages"),
        system = o.seq[ContentBlock]("system"),
        inferenceConfig = o.opt[InferenceConfig]("inferenceConfig"),
        tools = tc.map(_.seq[Tool]("tools")).getOrElse(Nil),
        toolChoice = tc.flatMap(_.opt[ToolChoice]("toolChoice")),
        additionalModelRequestFields = o.opt[JSONValue]("additionalModelRequestFields"),
        extra = o.extra(known)
      )
  )

end ConverseRequest

/** Values of `stopReason` */
object StopReason:
  val EndTurn: String                    = "end_turn"
  val ToolUse: String                    = "tool_use"
  val MaxTokens: String                  = "max_tokens"
  val StopSequence: String               = "stop_sequence"
  val GuardrailIntervened: String        = "guardrail_intervened"
  val ContentFiltered: String            = "content_filtered"
  val ModelContextWindowExceeded: String = "model_context_window_exceeded"

/** Token usage. `inputTokens` excludes the cache read and write counts. */
case class TokenUsage(
    inputTokens: Long = 0,
    outputTokens: Long = 0,
    totalTokens: Long = 0,
    cacheReadInputTokens: Option[Long] = None,
    cacheWriteInputTokens: Option[Long] = None
)

object TokenUsage:
  given JsonCodec[TokenUsage] = JsonCodec.obj(
    u =>
      Seq(
        Json.field("inputTokens", u.inputTokens),
        Json.field("outputTokens", u.outputTokens),
        Json.field("totalTokens", u.totalTokens),
        Json.opt("cacheReadInputTokens", u.cacheReadInputTokens),
        Json.opt("cacheWriteInputTokens", u.cacheWriteInputTokens)
      ),
    o =>
      TokenUsage(
        o.getOrElse[Long]("inputTokens", 0L),
        o.getOrElse[Long]("outputTokens", 0L),
        o.getOrElse[Long]("totalTokens", 0L),
        o.opt[Long]("cacheReadInputTokens"),
        o.opt[Long]("cacheWriteInputTokens")
      )
  )

end TokenUsage

/**
  * A Converse response.
  *
  * @param extra
  *   `metrics`, `additionalModelResponseFields`, `trace`, and other fields
  */
case class ConverseResponse(
    message: Message,
    stopReason: String,
    usage: TokenUsage = TokenUsage(),
    extra: Map[String, JSONValue] = Map.empty
):
  def text: String = message.text

object ConverseResponse:
  private val known = Set("output", "stopReason", "usage")

  given JsonCodec[ConverseResponse] = JsonCodec.obj(
    r =>
      Seq(
        "output" -> Some(Json.obj(Json.field("message", r.message))),
        Json.field("stopReason", r.stopReason),
        Json.field("usage", r.usage)
      ) ++ Json.extra(r.extra),
    o =>
      ConverseResponse(
        o.obj("output").get[Message]("message"),
        o.strOrEmpty("stopReason"),
        o.getOrElse[TokenUsage]("usage", TokenUsage()),
        o.extra(known)
      )
  )

/**
  * An event of ConverseStream. Bedrock sends these as AWS event-stream frames whose `:event-type`
  * header names the event; see [[EventStreamDecoder]].
  */
sealed trait ConverseStreamEvent

object ConverseStreamEvent:
  case class MessageStart(role: String) extends ConverseStreamEvent

  /** Starts a block; `toolUse` is set for tool calls (text blocks start with their first delta) */
  case class ContentBlockStart(contentBlockIndex: Int, toolUse: Option[(String, String)] = None)
      extends ConverseStreamEvent

  /**
    * One delta: `text`, `toolUseInput` (a JSON fragment), `reasoningText`, `reasoningSignature`, or
    * `redactedContent`
    */
  case class ContentBlockDelta(
      contentBlockIndex: Int,
      text: Option[String] = None,
      toolUseInput: Option[String] = None,
      reasoningText: Option[String] = None,
      reasoningSignature: Option[String] = None,
      redactedContent: Option[String] = None
  ) extends ConverseStreamEvent

  case class ContentBlockStop(contentBlockIndex: Int) extends ConverseStreamEvent

  case class MessageStop(
      stopReason: String,
      additionalModelResponseFields: Option[JSONValue] = None
  ) extends ConverseStreamEvent

  /** Usage and metrics; sent after [[MessageStop]] */
  case class Metadata(usage: TokenUsage, extra: Map[String, JSONValue] = Map.empty)
      extends ConverseStreamEvent

  /** An exception frame, e.g. `throttlingException` or `modelStreamErrorException` */
  case class Exception(exceptionType: String, message: String) extends ConverseStreamEvent

  case class Unknown(eventType: String, payload: JSONValue) extends ConverseStreamEvent

  /** The event-type name and JSON payload of an event, as Bedrock writes them in a frame */
  def encode(e: ConverseStreamEvent): (String, JSONValue) =
    e match
      case MessageStart(role) =>
        "messageStart" -> Json.obj(Json.field("role", role))
      case ContentBlockStart(i, tool) =>
        "contentBlockStart" ->
          Json.obj(
            Json.field("contentBlockIndex", i),
            "start" ->
              Some(
                Json.obj(
                  "toolUse" ->
                    tool.map((id, name) =>
                      Json.obj(Json.field("toolUseId", id), Json.field("name", name))
                    )
                )
              )
          )
      case d: ContentBlockDelta =>
        val delta =
          if d.text.isDefined then
            Json.obj(Json.opt("text", d.text))
          else if d.toolUseInput.isDefined then
            Json.obj("toolUse" -> Some(Json.obj(Json.opt("input", d.toolUseInput))))
          else
            Json.obj(
              "reasoningContent" ->
                Some(
                  Json.obj(
                    Json.opt("text", d.reasoningText),
                    Json.opt("signature", d.reasoningSignature),
                    Json.opt("redactedContent", d.redactedContent)
                  )
                )
            )
        "contentBlockDelta" ->
          Json.obj(Json.field("contentBlockIndex", d.contentBlockIndex), "delta" -> Some(delta))
      case ContentBlockStop(i) =>
        "contentBlockStop" -> Json.obj(Json.field("contentBlockIndex", i))
      case MessageStop(reason, fields) =>
        "messageStop" ->
          Json.obj(
            Json.field("stopReason", reason),
            Json.opt("additionalModelResponseFields", fields)
          )
      case Metadata(usage, extra) =>
        "metadata" -> Json.obj((Json.field("usage", usage) +: Json.extra(extra))*)
      case Exception(t, msg) =>
        t -> Json.obj(Json.field("message", msg))
      case Unknown(t, payload) =>
        t -> payload

  def decode(eventType: String, payload: JSONValue): ConverseStreamEvent =
    val o = JsonObj.unapply(payload).getOrElse(JsonObj(JSONObject.empty))
    eventType match
      case "messageStart" =>
        MessageStart(o.getOrElse("role", "assistant"))
      case "contentBlockStart" =>
        val tool = o
          .objOpt("start")
          .flatMap(_.objOpt("toolUse"))
          .map(t => (t.strOrEmpty("toolUseId"), t.strOrEmpty("name")))
        ContentBlockStart(o.getOrElse[Int]("contentBlockIndex", 0), tool)
      case "contentBlockDelta" =>
        val d = o.objOpt("delta").getOrElse(JsonObj(JSONObject.empty))
        val r = d.objOpt("reasoningContent")
        ContentBlockDelta(
          o.getOrElse[Int]("contentBlockIndex", 0),
          text = d.strOpt("text"),
          toolUseInput = d.objOpt("toolUse").flatMap(_.strOpt("input")),
          reasoningText = r.flatMap(_.strOpt("text")),
          reasoningSignature = r.flatMap(_.strOpt("signature")),
          redactedContent = r.flatMap(_.strOpt("redactedContent"))
        )
      case "contentBlockStop" =>
        ContentBlockStop(o.getOrElse[Int]("contentBlockIndex", 0))
      case "messageStop" =>
        MessageStop(o.strOrEmpty("stopReason"), o.opt[JSONValue]("additionalModelResponseFields"))
      case "metadata" =>
        Metadata(o.getOrElse[TokenUsage]("usage", TokenUsage()), o.extra(Set("usage")))
      case other =>
        Unknown(other, payload)

  end decode

  /** Decode an event-stream frame of ConverseStream */
  def fromFrame(frame: EventStreamFrame): ConverseStreamEvent =
    frame.messageType match
      case "exception" | "error" =>
        val payload = Json.parseOrString(frame.payloadString)
        val message = JsonObj
          .unapply(payload)
          .flatMap(_.strOpt("message"))
          .getOrElse(frame.payloadString)
        Exception(
          frame.exceptionType.orElse(frame.header(":error-code")).getOrElse("unknown"),
          message
        )
      case _ =>
        decode(frame.eventType.getOrElse(""), Json.parseOrString(frame.payloadString))

end ConverseStreamEvent

/** Folds ConverseStream events into a [[ConverseResponse]] */
class ConverseAccumulator:
  private var role       = "assistant"
  private val blocks     = mutable.TreeMap.empty[Int, ContentBlock]
  private val toolInput  = mutable.Map.empty[Int, StringBuilder]
  private var stopReason = ""
  private var usage      = TokenUsage()
  private var extra      = Map.empty[String, JSONValue]

  def add(e: ConverseStreamEvent): Unit =
    import ConverseStreamEvent.*
    e match
      case MessageStart(r) =>
        role = r
      case ContentBlockStart(i, Some((id, name))) =>
        blocks(i) = ContentBlock.ToolUse(id, name)
      case d: ContentBlockDelta =>
        val i = d.contentBlockIndex
        d.text
          .foreach { t =>
            blocks.get(i) match
              case Some(ContentBlock.Text(prev)) =>
                blocks(i) = ContentBlock.Text(prev + t)
              case _ =>
                blocks(i) = ContentBlock.Text(t)
          }
        d.toolUseInput.foreach(s => toolInput.getOrElseUpdate(i, StringBuilder()).append(s))
        if d.reasoningText.isDefined || d.reasoningSignature.isDefined ||
          d.redactedContent.isDefined
        then
          val prev =
            blocks.get(i) match
              case Some(r: ContentBlock.Reasoning) =>
                r
              case _ =>
                ContentBlock.Reasoning()
          blocks(i) = ContentBlock.Reasoning(
            text = d.reasoningText.map(t => prev.text.getOrElse("") + t).orElse(prev.text),
            signature = d.reasoningSignature.orElse(prev.signature),
            redactedContent = d.redactedContent.orElse(prev.redactedContent)
          )
      case ContentBlockStop(i) =>
        toolInput
          .remove(i)
          .foreach { sb =>
            blocks.get(i) match
              case Some(t: ContentBlock.ToolUse) =>
                blocks(i) = t.copy(input =
                  if sb.isEmpty then
                    JSONObject.empty
                  else
                    Json.parseOrString(sb.result())
                )
              case _ =>
          }
      case MessageStop(reason, fields) =>
        stopReason = reason
        fields.foreach(f => extra += "additionalModelResponseFields" -> f)
      case Metadata(u, ex) =>
        usage = u
        extra ++= ex
      case _ =>
    end match

  end add

  def result: ConverseResponse = ConverseResponse(
    Message(role, blocks.values.toSeq),
    stopReason,
    usage,
    extra
  )

end ConverseAccumulator
