package wvlet.uni.ai.bedrock

import wvlet.uni.ai.*
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Converts Bedrock Converse types to and from the vendor-neutral model.
  *
  * A Bedrock `cachePoint` maps to an Anthropic `cache_control` breakpoint on the part before it,
  * and back, so prompt caching survives conversion between the two. Reasoning signatures from
  * Claude on Bedrock are Anthropic signatures and carry over unchanged.
  */
object BedrockConverter:
  private val vendor = ProviderMetadata.Bedrock

  private val cacheMeta: ProviderMetadata = Map(
    ProviderMetadata.Anthropic -> Json.obj("cacheControl" -> Some(Json.obj(Json.field("type", "ephemeral"))))
  )

  private def hasCache(md: ProviderMetadata): Boolean = md
    .field(ProviderMetadata.Anthropic, "cacheControl")
    .isDefined

  private def withCache(p: LlmPart): LlmPart =
    p match
      case t: LlmPart.Text =>
        t.copy(providerMetadata = t.providerMetadata.merge(cacheMeta))
      case f: LlmPart.File =>
        f.copy(providerMetadata = f.providerMetadata.merge(cacheMeta))
      case t: LlmPart.ToolCall =>
        t.copy(providerMetadata = t.providerMetadata.merge(cacheMeta))
      case t: LlmPart.ToolResult =>
        t.copy(providerMetadata = t.providerMetadata.merge(cacheMeta))
      case other =>
        other

  private def mediaTypeOf(format: String, image: Boolean): String =
    if image then
      s"image/${format}"
    else
      format match
        case "pdf" =>
          "application/pdf"
        case "csv" =>
          "text/csv"
        case "txt" =>
          "text/plain"
        case "md" =>
          "text/markdown"
        case "html" =>
          "text/html"
        case "doc" =>
          "application/msword"
        case "docx" =>
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "xls" =>
          "application/vnd.ms-excel"
        case "xlsx" =>
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        case other =>
          s"application/${other}"

  private def formatOf(mediaType: String): String =
    mediaType match
      case "application/pdf" =>
        "pdf"
      case "text/csv" =>
        "csv"
      case "text/plain" =>
        "txt"
      case "text/markdown" =>
        "md"
      case "text/html" =>
        "html"
      case "application/msword" =>
        "doc"
      case m if m.endsWith("wordprocessingml.document") =>
        "docx"
      case "application/vnd.ms-excel" =>
        "xls"
      case m if m.endsWith("spreadsheetml.sheet") =>
        "xlsx"
      case m =>
        m.substring(m.indexOf('/') + 1) match
          case "jpg" =>
            "jpeg"
          case f =>
            f

  /** Bedrock document names allow `[a-zA-Z0-9 ()\[\]-]`, at most 200 characters */
  private def documentName(name: Option[String], index: Int): String =
    val n = name
      .getOrElse(s"document-${index}")
      .map(c =>
        if c.isLetterOrDigit && c < 128 || " ()[]-".contains(c) then
          c
        else
          '-'
      )
      .take(200)
    if n.trim.isEmpty then
      s"document-${index}"
    else
      n

  // ---------------------------------------------------------------- request

  private def toLlmParts(blocks: Seq[ContentBlock]): Seq[LlmPart] =
    val out = mutable.ArrayBuffer.empty[LlmPart]
    blocks.foreach {
      case ContentBlock.Text(t) =>
        out += LlmPart.Text(t)
      case ContentBlock.Image(f, b) =>
        out += LlmPart.File(mediaTypeOf(f, image = true), FileSource.Base64(b))
      case ContentBlock.Document(f, n, b) =>
        out += LlmPart.File(mediaTypeOf(f, image = false), FileSource.Base64(b), Some(n))
      case ContentBlock.ToolUse(id, name, input) =>
        out += LlmPart.ToolCall(id, name, input)
      case ContentBlock.ToolResult(id, content, status) =>
        val parts = content.flatMap { c =>
          val o = JsonObj(c)
          if o.has("text") then
            Some(LlmPart.Text(o.str("text")))
          else if o.has("json") then
            Some(LlmPart.Text(o.get[JSONValue]("json").toJSON))
          else
            toLlmParts(Seq(JsonCodec[ContentBlock].decode(c))).headOption
        }
        out += LlmPart.ToolResult(id, parts, isError = status.contains("error"))
      case ContentBlock.Reasoning(text, sig, redacted) =>
        out += LlmPart.Reasoning(text.getOrElse(""), sig, redacted)
      case ContentBlock.CachePoint =>
        if out.nonEmpty then
          out(out.length - 1) = withCache(out.last)
      case u: ContentBlock.Unknown =>
        out += LlmPart.Raw(vendor, u.raw)
    }
    out.toSeq

  def toLlmRequest(r: ConverseRequest, modelId: String = ""): LlmRequest =
    val messages = r
      .messages
      .flatMap { m =>
        val parts           = toLlmParts(m.content)
        val (results, rest) = parts.partition(_.isInstanceOf[LlmPart.ToolResult])
        val role =
          if m.role == "assistant" then
            LlmRole.Assistant
          else
            LlmRole.User
        Seq(LlmMessage(LlmRole.Tool, results), LlmMessage(role, rest)).filter(_.parts.nonEmpty)
      }
    val fields   = r.additionalModelRequestFields.flatMap(JsonObj.unapply)
    val thinking = fields.flatMap(_.objOpt("thinking"))
    val extra = r.extra ++
      fields
        .map(f => f.extra(Set("thinking")))
        .filter(_.nonEmpty)
        .map(e => "additionalModelRequestFields" -> JSONObject(e.toSeq))
    LlmRequest(
      model = modelId,
      messages = messages,
      system = toLlmParts(r.system).collect { case t: LlmPart.Text =>
        t
      },
      tools = r
        .tools
        .collect {
          case Tool.Spec(name, schema, desc) =>
            LlmTool.Function(name, schema, desc)
          case Tool.Unknown(raw) =>
            LlmTool.Provider(vendor, raw)
        },
      toolChoice = r
        .toolChoice
        .map {
          case ToolChoice.Auto =>
            LlmToolChoice.Auto
          case ToolChoice.AnyTool =>
            LlmToolChoice.Required
          case ToolChoice.Tool(n) =>
            LlmToolChoice.Tool(n)
        },
      maxOutputTokens = r.inferenceConfig.flatMap(_.maxTokens),
      temperature = r.inferenceConfig.flatMap(_.temperature),
      topP = r.inferenceConfig.flatMap(_.topP),
      stopSequences = r.inferenceConfig.map(_.stopSequences).getOrElse(Nil),
      reasoning = thinking
        .filter(_.strOpt("type").contains("enabled"))
        .map(t => ReasoningOptions(budgetTokens = t.opt[Int]("budget_tokens"))),
      providerOptions = LlmConvert.providerOptionsOf(vendor, extra)
    )

  end toLlmRequest

  private def fromLlmParts(parts: Seq[LlmPart]): Seq[ContentBlock] =
    var docIndex = 0
    parts.flatMap { p =>
      val blocks: Seq[ContentBlock] =
        p match
          case LlmPart.Text(t, _) =>
            Seq(ContentBlock.Text(t))
          case f @ LlmPart.File(m, FileSource.Base64(b), name, _) =>
            if f.isImage then
              Seq(ContentBlock.Image(formatOf(m), b))
            else
              docIndex += 1
              Seq(ContentBlock.Document(formatOf(m), documentName(name, docIndex), b))
          case LlmPart.File(_, FileSource.Url(u), _, _) =>
            // Converse takes bytes or S3 locations only
            Seq(ContentBlock.Text(u))
          case _: LlmPart.File =>
            Nil
          case LlmPart.Reasoning(t, sig, red, _) if sig.isDefined || red.isDefined =>
            Seq(ContentBlock.Reasoning(Some(t).filter(_ => red.isEmpty), sig, red))
          case _: LlmPart.Reasoning =>
            Nil
          case LlmPart.ToolCall(id, name, input, false, _) =>
            Seq(ContentBlock.ToolUse(LlmConvert.bedrockToolId(id), name, input))
          case _: LlmPart.ToolCall =>
            Nil
          case r: LlmPart.ToolResult =>
            val content = r
              .output
              .flatMap {
                case LlmPart.Text(t, _) =>
                  Some(Json.obj(Json.field("text", t)))
                case f: LlmPart.File =>
                  fromLlmParts(Seq(f)).headOption.map(JsonCodec[ContentBlock].encode)
                case _ =>
                  None
              }
            Seq(
              ContentBlock.ToolResult(
                LlmConvert.bedrockToolId(r.id),
                if content.isEmpty then
                  Seq(Json.obj(Json.field("text", "")))
                else
                  content
                ,
                Some(
                  if r.isError then
                    "error"
                  else
                    "success"
                )
              )
            )
          case LlmPart.Raw(`vendor`, raw: JSONObject) =>
            Seq(JsonCodec[ContentBlock].decode(raw))
          case _: LlmPart.Raw =>
            Nil
      if blocks.nonEmpty && hasCache(p.providerMetadata) then
        blocks :+ ContentBlock.CachePoint
      else
        blocks
    }

  end fromLlmParts

  private def isClaude(modelId: String): Boolean =
    modelId.contains("anthropic") || modelId.contains("claude")

  def fromLlmRequest(r: LlmRequest): ConverseRequest =
    val msgs = r
      .messages
      .map { m =>
        val role =
          if m.role == LlmRole.Assistant then
            "assistant"
          else
            "user"
        val (results, rest) = m.parts.partition(_.isInstanceOf[LlmPart.ToolResult])
        Message(role, fromLlmParts(results ++ rest))
      }
      .filter(_.content.nonEmpty)
      // Roles must alternate; merge consecutive turns of the same role
      .foldLeft(Vector.empty[Message]) { (acc, m) =>
        acc.lastOption match
          case Some(last) if last.role == m.role =>
            acc.init :+ last.copy(content = last.content ++ m.content)
          case _ =>
            acc :+ m
      }
    val thinking = r
      .reasoning
      .filter(o => o.effortLevel != "none" && isClaude(r.model))
      .map(o =>
        Json.obj(
          "thinking" ->
            Some(Json.obj(Json.field("type", "enabled"), Json.field("budget_tokens", math.max(1024, o.budget))))
        )
      )
    val opts       = r.providerOptions.get(vendor).map(_.v.toMap).getOrElse(Map.empty)
    val addFields  = (thinking.map(_.v).getOrElse(Nil) ++
      opts
        .get("additionalModelRequestFields")
        .collect { case o: JSONObject =>
          o.v
        }
        .getOrElse(Nil))
    ConverseRequest(
      messages = msgs,
      system = fromLlmParts(r.system),
      inferenceConfig = Some(
        InferenceConfig(
          r.maxOutputTokens,
          r.temperature.filter(_ => thinking.isEmpty),
          r.topP.filter(_ => thinking.isEmpty),
          r.stopSequences
        )
      ).filter(_ != InferenceConfig()),
      tools = r
        .tools
        .collect {
          case LlmTool.Function(name, schema, desc, md) =>
            Seq(Tool.Spec(name, schema, desc)) ++ Option.when(hasCache(md))(Tool.CachePoint)
          case LlmTool.Provider(`vendor`, raw: JSONObject) =>
            Seq(Tool.Unknown(raw))
        }
        .flatten,
      toolChoice = r
        .toolChoice
        .collect {
          case LlmToolChoice.Auto =>
            ToolChoice.Auto
          case LlmToolChoice.Required =>
            ToolChoice.AnyTool
          case LlmToolChoice.Tool(n) =>
            ToolChoice.Tool(n)
        },
      additionalModelRequestFields = Some(JSONObject(addFields)).filter(!_.isEmpty),
      extra = opts - "additionalModelRequestFields"
    )

  end fromLlmRequest

  // ---------------------------------------------------------------- response

  def toFinishReason(stopReason: String): FinishReason =
    val kind =
      stopReason match
        case StopReason.EndTurn | StopReason.StopSequence =>
          FinishReason.Kind.Stop
        case StopReason.MaxTokens | StopReason.ModelContextWindowExceeded =>
          FinishReason.Kind.Length
        case StopReason.ToolUse =>
          FinishReason.Kind.ToolCalls
        case StopReason.GuardrailIntervened | StopReason.ContentFiltered =>
          FinishReason.Kind.ContentFilter
        case "" =>
          FinishReason.Kind.Interrupted
        case _ =>
          FinishReason.Kind.Other
    FinishReason(kind, Some(stopReason).filter(_.nonEmpty))

  def fromFinishReason(f: FinishReason): String =
    f.kind match
      case FinishReason.Kind.Length =>
        StopReason.MaxTokens
      case FinishReason.Kind.ToolCalls =>
        StopReason.ToolUse
      case FinishReason.Kind.ContentFilter =>
        StopReason.ContentFiltered
      case _ =>
        f.raw.filter(_ == StopReason.StopSequence).getOrElse(StopReason.EndTurn)

  /** Bedrock input tokens exclude cache reads and writes */
  def toLlmUsage(u: TokenUsage): LlmUsage =
    val read  = u.cacheReadInputTokens.getOrElse(0L)
    val write = u.cacheWriteInputTokens.getOrElse(0L)
    LlmUsage(u.inputTokens + read + write, u.outputTokens, read, write)

  def fromLlmUsage(u: LlmUsage): TokenUsage = TokenUsage(
    inputTokens = u.noCacheInputTokens,
    outputTokens = u.outputTokens,
    totalTokens = u.totalTokens,
    cacheReadInputTokens = Some(u.cacheReadTokens).filter(_ > 0),
    cacheWriteInputTokens = Some(u.cacheWriteTokens).filter(_ > 0)
  )

  def toLlmResponse(r: ConverseResponse, modelId: String = ""): LlmResponse = LlmResponse(
    id = "",
    model = modelId,
    content = toLlmParts(r.message.content),
    finishReason = toFinishReason(r.stopReason),
    usage = toLlmUsage(r.usage)
  )

  def fromLlmResponse(r: LlmResponse): ConverseResponse = ConverseResponse(
    Message("assistant", fromLlmParts(r.content)),
    fromFinishReason(r.finishReason),
    fromLlmUsage(r.usage)
  )

  // ---------------------------------------------------------------- streams

  /**
    * Converts ConverseStream events to neutral stream parts. Bedrock sends `messageStop` before
    * `metadata` (usage), so the Finish part is emitted on `metadata`, or by [[finish]].
    */
  class StreamDecoder(modelId: String = ""):
    import ConverseStreamEvent as E
    import LlmStreamPart as P

    private enum Block:
      case Text, Reasoning, Tool

    private val open                   = mutable.Map.empty[Int, (Block, String)]
    private val signature              = mutable.Map.empty[Int, String]
    private val redacted               = mutable.Map.empty[Int, String]
    private var stop: Option[String]   = None
    private var finished               = false

    def decode(e: ConverseStreamEvent): Seq[LlmStreamPart] =
      e match
        case E.MessageStart(_) =>
          Seq(P.Start("", modelId))
        case E.ContentBlockStart(i, Some((id, name))) =>
          open(i) = (Block.Tool, id)
          Seq(P.ToolInputStart(id, name))
        case d: E.ContentBlockDelta =>
          val i   = d.contentBlockIndex
          val out = Seq.newBuilder[LlmStreamPart]
          d.text
            .foreach { t =>
              if !open.contains(i) then
                open(i) = (Block.Text, s"${i}")
                out += P.TextStart(s"${i}")
              out += P.TextDelta(s"${i}", t)
            }
          if d.reasoningText.isDefined || d.reasoningSignature.isDefined || d.redactedContent.isDefined then
            if !open.contains(i) then
              open(i) = (Block.Reasoning, s"${i}")
              out += P.ReasoningStart(s"${i}")
            d.reasoningText.foreach(t => out += P.ReasoningDelta(s"${i}", t))
            d.reasoningSignature.foreach(s => signature(i) = signature.getOrElse(i, "") + s)
            d.redactedContent.foreach(r => redacted(i) = r)
          d.toolUseInput
            .foreach { s =>
              open.get(i).foreach((_, id) => out += P.ToolInputDelta(id, s))
            }
          out.result()
        case E.ContentBlockStop(i) =>
          open.remove(i) match
            case Some((Block.Text, id)) =>
              Seq(P.TextEnd(id))
            case Some((Block.Reasoning, id)) =>
              Seq(P.ReasoningEnd(id, signature.remove(i), redacted.remove(i)))
            case Some((Block.Tool, id)) =>
              Seq(P.ToolInputEnd(id))
            case None =>
              Nil
        case E.MessageStop(reason, _) =>
          stop = Some(reason)
          Nil
        case E.Metadata(usage, _) =>
          finished = true
          Seq(P.Finish(toFinishReason(stop.getOrElse("")), toLlmUsage(usage)))
        case E.Exception(t, msg) =>
          Seq(P.Error(msg, Some(t)))
        case _ =>
          Nil

    def finish(): Seq[LlmStreamPart] =
      if finished then
        Nil
      else
        finished = true
        Seq(P.Finish(toFinishReason(stop.getOrElse(""))))

  end StreamDecoder

  /** Converts neutral stream parts to ConverseStream events */
  class StreamEncoder:
    import ConverseStreamEvent as E
    import LlmStreamPart as P

    private var started  = false
    private var finished = false
    private var index    = -1
    private var open     = false

    private def startBlock(): Seq[ConverseStreamEvent] =
      val c = closeBlock()
      index += 1
      open = true
      c

    private def closeBlock(): Seq[ConverseStreamEvent] =
      if open then
        open = false
        Seq(E.ContentBlockStop(index))
      else
        Nil

    def encode(p: LlmStreamPart): Seq[ConverseStreamEvent] =
      val prefix =
        if started then
          Nil
        else
          started = true
          Seq(E.MessageStart("assistant"))
      val body: Seq[ConverseStreamEvent] =
        p match
          case _: P.TextStart | _: P.ReasoningStart =>
            startBlock()
          case P.TextDelta(_, d) =>
            Seq(E.ContentBlockDelta(index, text = Some(d)))
          case P.ReasoningDelta(_, d) =>
            Seq(E.ContentBlockDelta(index, reasoningText = Some(d)))
          case P.ReasoningEnd(_, sig, red, _) =>
            sig.map(s => E.ContentBlockDelta(index, reasoningSignature = Some(s))).toSeq ++
              red.map(r => E.ContentBlockDelta(index, redactedContent = Some(r))).toSeq ++ closeBlock()
          case P.ToolInputStart(id, name, false, _) =>
            startBlock() :+ E.ContentBlockStart(index, Some((LlmConvert.bedrockToolId(id), name)))
          case P.ToolInputDelta(_, d) =>
            Seq(E.ContentBlockDelta(index, toolUseInput = Some(d)))
          case _: P.TextEnd | _: P.ToolInputEnd =>
            closeBlock()
          case P.Finish(reason, u) =>
            finished = true
            closeBlock() ++ Seq(E.MessageStop(fromFinishReason(reason)), E.Metadata(fromLlmUsage(u)))
          case P.Error(msg, tpe) =>
            Seq(E.Exception(tpe.getOrElse("internalServerException"), msg))
          case _ =>
            Nil
      prefix ++ body

    def finish(): Seq[ConverseStreamEvent] =
      if finished then
        Nil
      else
        encode(P.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamEncoder

  /** Encode a ConverseStream event as an event-stream frame, as Bedrock sends it */
  def toFrame(e: ConverseStreamEvent): Array[Byte] =
    e match
      case ConverseStreamEvent.Exception(t, msg) =>
        EventStreamFrame.encode(
          Seq(":exception-type" -> t, ":content-type" -> "application/json", ":message-type" -> "exception"),
          Json.obj(Json.field("message", msg)).toJSON.getBytes("UTF-8")
        )
      case other =>
        val (name, payload) = ConverseStreamEvent.encode(other)
        EventStreamFrame.event(name, payload.toJSON)

end BedrockConverter
