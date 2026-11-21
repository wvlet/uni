package wvlet.uni.ai.anthropic

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonObj
import wvlet.uni.http.ServerSentEvent
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/** A `delta` of a `content_block_delta` event */
sealed trait Delta

object Delta:
  case class TextDelta(text: String)               extends Delta
  case class InputJsonDelta(partialJson: String)   extends Delta
  case class ThinkingDelta(thinking: String)       extends Delta
  case class SignatureDelta(signature: String)     extends Delta
  case class CitationsDelta(citation: JSONValue)   extends Delta
  case class Unknown(raw: JSONObject)              extends Delta

  given JsonCodec[Delta] = JsonCodec.of(
    {
      case TextDelta(t) =>
        Json.obj(Json.field("type", "text_delta"), Json.field("text", t))
      case InputJsonDelta(p) =>
        Json.obj(Json.field("type", "input_json_delta"), Json.field("partial_json", p))
      case ThinkingDelta(t) =>
        Json.obj(Json.field("type", "thinking_delta"), Json.field("thinking", t))
      case SignatureDelta(s) =>
        Json.obj(Json.field("type", "signature_delta"), Json.field("signature", s))
      case CitationsDelta(c) =>
        Json.obj(Json.field("type", "citations_delta"), Json.field("citation", c))
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      o.strOrEmpty("type") match
        case "text_delta" =>
          TextDelta(o.strOrEmpty("text"))
        case "input_json_delta" =>
          InputJsonDelta(o.strOrEmpty("partial_json"))
        case "thinking_delta" =>
          ThinkingDelta(o.strOrEmpty("thinking"))
        case "signature_delta" =>
          SignatureDelta(o.strOrEmpty("signature"))
        case "citations_delta" =>
          CitationsDelta(o.get[JSONValue]("citation"))
        case _ =>
          Unknown(o.value)
  )

end Delta

/**
  * A server-sent event of a streaming Messages response. The SSE `event:` name equals
  * [[eventType]].
  */
sealed trait StreamEvent:
  def eventType: String

  def toServerSentEvent: ServerSentEvent = ServerSentEvent(
    event = Some(eventType),
    data = StreamEvent.codec.toJson(this)
  )

object StreamEvent:
  case class MessageStart(message: MessagesResponse) extends StreamEvent:
    def eventType = "message_start"

  case class ContentBlockStart(index: Int, contentBlock: ContentBlock) extends StreamEvent:
    def eventType = "content_block_start"

  case class ContentBlockDelta(index: Int, delta: Delta) extends StreamEvent:
    def eventType = "content_block_delta"

  case class ContentBlockStop(index: Int) extends StreamEvent:
    def eventType = "content_block_stop"

  /** Final stop reason and the cumulative usage */
  case class MessageDelta(
      stopReason: Option[String],
      stopSequence: Option[String] = None,
      usage: Usage = Usage(),
      extra: Map[String, JSONValue] = Map.empty
  ) extends StreamEvent:
    def eventType = "message_delta"

  case object MessageStop extends StreamEvent:
    def eventType = "message_stop"

  case object Ping extends StreamEvent:
    def eventType = "ping"

  case class Error(error: ErrorDetail) extends StreamEvent:
    def eventType = "error"

  case class Unknown(raw: JSONObject) extends StreamEvent:
    def eventType = JsonObj(raw).strOrEmpty("type")

  given codec: JsonCodec[StreamEvent] = JsonCodec.of(encode, decode)

  private def typed(t: String, fields: (String, Option[JSONValue])*): JSONValue = Json.obj(
    (Json.field("type", t) +: fields)*
  )

  private def nullable(s: Option[String]): Option[JSONValue] = Some(
    s.map(JSONString(_)).getOrElse(JSONNull())
  )

  private def encode(e: StreamEvent): JSONValue =
    e match
      case MessageStart(m) =>
        typed("message_start", Json.field("message", m))
      case ContentBlockStart(i, b) =>
        typed("content_block_start", Json.field("index", i), Json.field("content_block", b))
      case ContentBlockDelta(i, d) =>
        typed("content_block_delta", Json.field("index", i), Json.field("delta", d))
      case ContentBlockStop(i) =>
        typed("content_block_stop", Json.field("index", i))
      case MessageDelta(reason, seq, usage, extra) =>
        typed(
          "message_delta",
          "delta" ->
            Some(
              Json.obj(
                (Seq("stop_reason" -> nullable(reason), "stop_sequence" -> nullable(seq)) ++
                  Json.extra(extra))*
              )
            ),
          Json.field("usage", usage)
        )
      case MessageStop =>
        typed("message_stop")
      case Ping =>
        typed("ping")
      case Error(err) =>
        typed("error", Json.field("error", err))
      case Unknown(raw) =>
        raw

  private def decode(v: JSONValue): StreamEvent =
    val o = JsonObj(v)
    o.strOrEmpty("type") match
      case "message_start" =>
        MessageStart(o.get[MessagesResponse]("message"))
      case "content_block_start" =>
        ContentBlockStart(o.get[Int]("index"), o.get[ContentBlock]("content_block"))
      case "content_block_delta" =>
        ContentBlockDelta(o.get[Int]("index"), o.get[Delta]("delta"))
      case "content_block_stop" =>
        ContentBlockStop(o.get[Int]("index"))
      case "message_delta" =>
        val d = o.objOpt("delta")
        MessageDelta(
          d.flatMap(_.strOpt("stop_reason")),
          d.flatMap(_.strOpt("stop_sequence")),
          o.getOrElse[Usage]("usage", Usage()),
          d.map(_.extra(Set("stop_reason", "stop_sequence"))).getOrElse(Map.empty)
        )
      case "message_stop" =>
        MessageStop
      case "ping" =>
        Ping
      case "error" =>
        Error(o.get[ErrorDetail]("error"))
      case _ =>
        Unknown(o.value)

  /** Decode an SSE event of a Messages stream */
  def fromServerSentEvent(e: ServerSentEvent): StreamEvent = codec.decode(JSON.parseAny(e.data))

end StreamEvent

/**
  * Folds the events of a Messages stream into the final [[MessagesResponse]], joining text,
  * thinking, and tool-input deltas per block index.
  */
class MessageAccumulator:
  private var start: Option[MessagesResponse]     = None
  private val blocks                              = mutable.TreeMap.empty[Int, ContentBlock]
  private val partialJson                         = mutable.Map.empty[Int, StringBuilder]
  private var stopReason: Option[String]          = None
  private var stopSequence: Option[String]        = None
  private var usage: Option[Usage]                = None

  def add(e: StreamEvent): Unit =
    import StreamEvent.*
    e match
      case MessageStart(m) =>
        start = Some(m)
        usage = Some(m.usage)
      case ContentBlockStart(i, b) =>
        blocks(i) = b
      case ContentBlockDelta(i, d) =>
        (blocks.get(i), d) match
          case (Some(t: ContentBlock.Text), Delta.TextDelta(s)) =>
            blocks(i) = t.copy(text = t.text + s)
          case (Some(t: ContentBlock.Text), Delta.CitationsDelta(c)) =>
            blocks(i) = t.copy(citations = t.citations :+ c)
          case (Some(t: ContentBlock.Thinking), Delta.ThinkingDelta(s)) =>
            blocks(i) = t.copy(thinking = t.thinking + s)
          case (Some(t: ContentBlock.Thinking), Delta.SignatureDelta(s)) =>
            blocks(i) = t.copy(signature = t.signature + s)
          case (_, Delta.InputJsonDelta(p)) =>
            partialJson.getOrElseUpdate(i, StringBuilder()).append(p)
          case _ =>
        end match
      case ContentBlockStop(i) =>
        partialJson
          .remove(i)
          .foreach { sb =>
            val input =
              if sb.isEmpty then
                JSONObject.empty
              else
                Json.parseOrString(sb.result())
            blocks.get(i) match
              case Some(t: ContentBlock.ToolUse) =>
                blocks(i) = t.copy(input = input)
              case Some(t: ContentBlock.ServerToolUse) =>
                blocks(i) = t.copy(input = input)
              case _ =>
          }
      case MessageDelta(reason, seq, u, _) =>
        stopReason = reason.orElse(stopReason)
        stopSequence = seq.orElse(stopSequence)
        usage = Some(mergeUsage(usage.getOrElse(Usage()), u))
      case _ =>
    end match

  end add

  // message_delta usage is cumulative; keep the start's input counts when it leaves them out
  private def mergeUsage(base: Usage, u: Usage): Usage = Usage(
    inputTokens =
      if u.inputTokens > 0 then
        u.inputTokens
      else
        base.inputTokens,
    outputTokens = u.outputTokens,
    cacheCreationInputTokens = u.cacheCreationInputTokens.orElse(base.cacheCreationInputTokens),
    cacheReadInputTokens = u.cacheReadInputTokens.orElse(base.cacheReadInputTokens),
    extra = base.extra ++ u.extra
  )

  def result: MessagesResponse =
    val base = start.getOrElse(MessagesResponse(id = "", model = "", content = Nil))
    base.copy(
      content = blocks.values.toSeq,
      stopReason = stopReason,
      stopSequence = stopSequence,
      usage = usage.getOrElse(base.usage)
    )

end MessageAccumulator
