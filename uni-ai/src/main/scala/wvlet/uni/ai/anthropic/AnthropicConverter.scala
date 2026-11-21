package wvlet.uni.ai.anthropic

import wvlet.uni.ai.*
import wvlet.uni.ai.LlmConvert.Carrier
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Converts Anthropic Messages types to and from the vendor-neutral [[LlmRequest]],
  * [[LlmResponse]], and [[LlmStreamPart]].
  *
  * Reasoning from other vendors (OpenAI encrypted reasoning, Gemini thought signatures) is carried
  * in thinking-block signatures with a [[LlmConvert.Carrier]] prefix, so a client such as Claude
  * Code returns it unchanged on the next turn.
  */
object AnthropicConverter:
  private val vendor = ProviderMetadata.Anthropic

  /** The default `max_tokens` when a neutral request has no limit; Anthropic requires one */
  val DefaultMaxTokens: Int = 8192

  // ---------------------------------------------------------------- request: Anthropic -> neutral

  def toLlmRequest(r: MessagesRequest): LlmRequest =
    val extra = r.extra ++
      r.metadata.map("metadata" -> _) ++
      r.serviceTier.map(t => "service_tier" -> JSONString(t)) ++
      r.thinking.map(t => "thinking" -> JsonCodec[ThinkingConfig].encode(t))
    LlmRequest(
      model = r.model,
      messages = r.messages.flatMap(toLlmMessages),
      system = r
        .system
        .collect { case ContentBlock.Text(t, _, c) =>
          LlmPart.Text(t, cacheMeta(c))
        },
      tools = r.tools.map(toLlmTool),
      toolChoice = r.toolChoice.flatMap(toLlmToolChoice),
      parallelToolCalls = r
        .toolChoice
        .flatMap {
          case ToolChoice.Auto(p) =>
            p
          case ToolChoice.AnyTool(p) =>
            p
          case ToolChoice.Tool(_, p) =>
            p
          case ToolChoice.NoTool =>
            None
        }
        .map(!_),
      maxOutputTokens = Some(r.maxTokens).filter(_ > 0),
      temperature = r.temperature,
      topP = r.topP,
      topK = r.topK,
      stopSequences = r.stopSequences,
      reasoning = r.thinking.flatMap(toReasoningOptions),
      stream = r.stream.contains(true),
      providerOptions =
        if extra.isEmpty then
          ProviderMetadata.empty
        else
          Map(vendor -> JSONObject(extra.toSeq))
    )

  private def toReasoningOptions(t: ThinkingConfig): Option[ReasoningOptions] =
    t.`type` match
      case "enabled" =>
        Some(ReasoningOptions(budgetTokens = t.budgetTokens))
      case "adaptive" =>
        Some(ReasoningOptions(effort = Some("high")))
      case _ =>
        None

  private def cacheMeta(c: Option[CacheControl]): ProviderMetadata = c
    .map(cc => Map(vendor -> Json.obj(Json.field("cacheControl", cc))))
    .getOrElse(ProviderMetadata.empty)

  private def cacheOf(md: ProviderMetadata): Option[CacheControl] = md
    .field(vendor, "cacheControl")
    .map(JsonCodec[CacheControl].decode)

  private def toLlmTool(t: Tool): LlmTool =
    t match
      case Tool.Custom(name, schema, desc, c) =>
        LlmTool.Function(name, schema, desc, cacheMeta(c))
      case Tool.Unknown(raw) =>
        LlmTool.Provider(vendor, raw)

  private def toLlmToolChoice(c: ToolChoice): Option[LlmToolChoice] =
    c match
      case ToolChoice.Auto(_) =>
        Some(LlmToolChoice.Auto)
      case ToolChoice.AnyTool(_) =>
        Some(LlmToolChoice.Required)
      case ToolChoice.Tool(n, _) =>
        Some(LlmToolChoice.Tool(n))
      case ToolChoice.NoTool =>
        Some(LlmToolChoice.NoTool)

  private def toLlmFile(source: Source, title: Option[String], c: Option[CacheControl], defaultType: String)
      : LlmPart =
    source match
      case Source.Base64(m, d) =>
        LlmPart.File(m, FileSource.Base64(d), title, cacheMeta(c))
      case Source.Url(u) =>
        LlmPart.File(defaultType, FileSource.Url(u), title, cacheMeta(c))
      case Source.File(id) =>
        LlmPart.File(defaultType, FileSource.FileId(id), title, cacheMeta(c))
      case Source.Text(d, _) =>
        LlmPart.Text(d, cacheMeta(c))
      case Source.Unknown(raw) =>
        LlmPart.Raw(vendor, raw)

  /**
    * One Anthropic message becomes one or two neutral messages: tool results in a user turn are
    * split into a Tool message that comes first, as Anthropic requires them first.
    */
  private def toLlmMessages(m: Message): Seq[LlmMessage] =
    // A Gemini signature carried in a thinking block belongs to the part that follows it
    var pendingMeta = ProviderMetadata.empty
    def attach(p: LlmPart): LlmPart =
      val md = pendingMeta
      pendingMeta = ProviderMetadata.empty
      if md.isEmpty then
        p
      else
        p match
          case t: LlmPart.ToolCall =>
            t.copy(providerMetadata = t.providerMetadata.merge(md))
          case t: LlmPart.Text =>
            t.copy(providerMetadata = t.providerMetadata.merge(md))
          case other =>
            other

    val results = Seq.newBuilder[LlmPart]
    val parts   = Seq.newBuilder[LlmPart]
    m.content
      .foreach {
        case ContentBlock.Text(t, _, c) =>
          parts += attach(LlmPart.Text(t, cacheMeta(c)))
        case ContentBlock.Image(s, c) =>
          parts += toLlmFile(s, None, c, "image/jpeg")
        case ContentBlock.Document(s, title, _, _, c) =>
          parts += toLlmFile(s, title, c, "application/pdf")
        case ContentBlock.ToolUse(id, name, input, c) =>
          parts += attach(LlmPart.ToolCall(id, name, input, providerMetadata = cacheMeta(c)))
        case ContentBlock.ToolResult(id, content, isError, c) =>
          results +=
            LlmPart.ToolResult(
              id,
              content.flatMap(b => toLlmMessages(Message(Role.User, Seq(b))).flatMap(_.parts)),
              isError = isError.contains(true),
              providerMetadata = cacheMeta(c)
            )
        case ContentBlock.Thinking(t, sig) =>
          Carrier.decode(sig) match
            case Some((ProviderMetadata.Gemini, data)) =>
              pendingMeta = Map(ProviderMetadata.Gemini -> data)
            case Some((other, data)) =>
              parts += LlmPart.Reasoning(t, providerMetadata = Map(other -> data))
            case None =>
              parts += LlmPart.Reasoning(t, Some(sig))
        case ContentBlock.RedactedThinking(d) =>
          parts += LlmPart.Reasoning("", redactedData = Some(d))
        case b @ (_: ContentBlock.ServerToolUse | _: ContentBlock.Unknown) =>
          parts += LlmPart.Raw(vendor, JsonCodec[ContentBlock].encode(b))
      }
    val role =
      m.role match
        case Role.User =>
          LlmRole.User
        case Role.Assistant =>
          LlmRole.Assistant
    Seq(LlmMessage(LlmRole.Tool, results.result()), LlmMessage(role, parts.result()))
      .filter(_.parts.nonEmpty)

  end toLlmMessages

  // ---------------------------------------------------------------- request: neutral -> Anthropic

  def fromLlmRequest(r: LlmRequest): MessagesRequest =
    val opts   = r.providerOptions.get(vendor).map(o => JsonObj(o)).toSeq
    val extras = opts.flatMap(_.fields).toMap
    val thinking = extras
      .get("thinking")
      .map(JsonCodec[ThinkingConfig].decode)
      .orElse(r.reasoning.flatMap(reasoningConfig(_, r.maxOutputTokens)))
    MessagesRequest(
      model = r.model,
      maxTokens = r.maxOutputTokens.getOrElse(DefaultMaxTokens),
      messages = mergeTurns(r.messages.map(fromLlmMessage)).filter(_.content.nonEmpty),
      system = r.system.map(t => ContentBlock.Text(t.text, cacheControl = cacheOf(t.providerMetadata))),
      tools = r
        .tools
        .collect {
          case LlmTool.Function(name, schema, desc, md) =>
            Tool.Custom(name, schema, desc, cacheOf(md))
          case LlmTool.Provider(`vendor`, raw: JSONObject) =>
            Tool.Unknown(raw)
        },
      toolChoice = r
        .toolChoice
        .map {
          case LlmToolChoice.Auto =>
            ToolChoice.Auto(r.parallelToolCalls.map(!_))
          case LlmToolChoice.Required =>
            ToolChoice.AnyTool(r.parallelToolCalls.map(!_))
          case LlmToolChoice.Tool(n) =>
            ToolChoice.Tool(n, r.parallelToolCalls.map(!_))
          case LlmToolChoice.NoTool =>
            ToolChoice.NoTool
        }
        .orElse(
          r.parallelToolCalls
            .filter(_ == false)
            .filter(_ => r.tools.nonEmpty)
            .map(_ => ToolChoice.Auto(Some(true)))
        ),
      thinking = thinking,
      // Anthropic rejects temperature and top_k while thinking is on
      temperature = r.temperature.filter(_ => !thinking.exists(_.`type` != "disabled")),
      topP = r.topP,
      topK = r.topK.filter(_ => !thinking.exists(_.`type` != "disabled")),
      stopSequences = r.stopSequences,
      stream =
        if r.stream then
          Some(true)
        else
          None
      ,
      metadata = extras.get("metadata"),
      serviceTier = extras.get("service_tier").collect { case JSONString(s) =>
        s
      },
      extra = extras -- Set("metadata", "service_tier", "thinking")
    )

  end fromLlmRequest

  private def reasoningConfig(o: ReasoningOptions, maxTokens: Option[Int]): Option[ThinkingConfig] =
    if o.effortLevel == "none" then
      None
    else
      // budget_tokens must be at least 1024 and below max_tokens
      val max    = maxTokens.getOrElse(DefaultMaxTokens)
      val budget = math.max(1024, math.min(o.budget, max - 1))
      if budget >= max then
        None
      else
        Some(ThinkingConfig.enabled(budget))

  private def mergeTurns(msgs: Seq[Message]): Seq[Message] =
    msgs.foldLeft(Vector.empty[Message]) { (acc, m) =>
      acc.lastOption match
        case Some(last) if last.role == m.role =>
          acc.init :+ last.copy(content = last.content ++ m.content)
        case _ =>
          acc :+ m
    }

  private def sourceOf(f: LlmPart.File): Source =
    f.source match
      case FileSource.Base64(d) =>
        Source.Base64(f.mediaType, d)
      case FileSource.Url(u) =>
        Source.Url(u)
      case FileSource.FileId(id) =>
        Source.File(id)

  private def fromLlmPart(p: LlmPart): Seq[ContentBlock] =
    p match
      case LlmPart.Text(t, md) =>
        carrierBefore(md) :+ ContentBlock.Text(t, cacheControl = cacheOf(md))
      case f: LlmPart.File if f.isImage =>
        Seq(ContentBlock.Image(sourceOf(f), cacheOf(f.providerMetadata)))
      case f: LlmPart.File =>
        Seq(ContentBlock.Document(sourceOf(f), f.filename, cacheControl = cacheOf(f.providerMetadata)))
      case LlmPart.Reasoning(t, sig, redacted, md) =>
        (sig, redacted) match
          case (Some(s), _) =>
            Seq(ContentBlock.Thinking(t, s))
          case (None, Some(d)) =>
            Seq(ContentBlock.RedactedThinking(d))
          case _ =>
            // Reasoning without an Anthropic signature is rejected; carry another vendor's data
            md.find(_._1 != vendor)
              .map((v, data) => ContentBlock.Thinking(t, Carrier.encode(v, data)))
              .toSeq
      case LlmPart.ToolCall(id, name, input, _, md) =>
        carrierBefore(md) :+
          ContentBlock.ToolUse(LlmConvert.anthropicToolId(id), name, input, cacheOf(md))
      case r: LlmPart.ToolResult =>
        Seq(
          ContentBlock.ToolResult(
            LlmConvert.anthropicToolId(r.id),
            r.output.flatMap(fromLlmPart),
            Some(true).filter(_ => r.isError),
            cacheOf(r.providerMetadata)
          )
        )
      case LlmPart.Raw(`vendor`, raw: JSONObject) =>
        Seq(JsonCodec[ContentBlock].decode(raw))
      case _: LlmPart.Raw =>
        Nil

  /** A Gemini thought signature rides in an empty thinking block before its part */
  private def carrierBefore(md: ProviderMetadata): Seq[ContentBlock] = md
    .get(ProviderMetadata.Gemini)
    .filter(_.get("thoughtSignature").isDefined)
    .map(d => ContentBlock.Thinking("", Carrier.encode(ProviderMetadata.Gemini, d)))
    .toSeq

  private def fromLlmMessage(m: LlmMessage): Message =
    val role =
      m.role match
        case LlmRole.Assistant =>
          Role.Assistant
        case _ =>
          Role.User
    // Tool results must lead a user turn
    val (results, rest) = m.parts.partition(_.isInstanceOf[LlmPart.ToolResult])
    Message(role, (results ++ rest).flatMap(fromLlmPart))

  // ---------------------------------------------------------------- response

  def toFinishReason(stopReason: Option[String]): FinishReason =
    stopReason match
      case None =>
        FinishReason(FinishReason.Kind.Interrupted)
      case Some(r) =>
        val kind =
          r match
            case StopReason.EndTurn | StopReason.StopSequence =>
              FinishReason.Kind.Stop
            case StopReason.MaxTokens | StopReason.ModelContextWindowExceeded =>
              FinishReason.Kind.Length
            case StopReason.ToolUse =>
              FinishReason.Kind.ToolCalls
            case StopReason.Refusal =>
              FinishReason.Kind.ContentFilter
            case _ =>
              FinishReason.Kind.Other
        FinishReason(kind, Some(r))

  private val stopReasons = Set(
    StopReason.EndTurn,
    StopReason.MaxTokens,
    StopReason.StopSequence,
    StopReason.ToolUse,
    StopReason.PauseTurn,
    StopReason.Refusal,
    StopReason.ModelContextWindowExceeded
  )

  def fromFinishReason(f: FinishReason): String =
    f.raw.filter(stopReasons.contains) match
      case Some(r) =>
        r
      case None =>
        f.kind match
          case FinishReason.Kind.Length =>
            StopReason.MaxTokens
          case FinishReason.Kind.ToolCalls =>
            StopReason.ToolUse
          case FinishReason.Kind.ContentFilter =>
            StopReason.Refusal
          case _ =>
            StopReason.EndTurn

  /** Anthropic input tokens exclude cache reads and writes; neutral input includes them */
  def toLlmUsage(u: Usage): LlmUsage =
    val read  = u.cacheReadInputTokens.getOrElse(0L)
    val write = u.cacheCreationInputTokens.getOrElse(0L)
    LlmUsage(u.inputTokens + read + write, u.outputTokens, read, write)

  def fromLlmUsage(u: LlmUsage): Usage = Usage(
    inputTokens = u.noCacheInputTokens,
    outputTokens = u.outputTokens,
    cacheCreationInputTokens = Some(u.cacheWriteTokens),
    cacheReadInputTokens = Some(u.cacheReadTokens)
  )

  def toLlmResponse(r: MessagesResponse): LlmResponse = LlmResponse(
    id = r.id,
    model = r.model,
    content = toLlmMessages(Message(r.role, r.content)).flatMap(_.parts),
    finishReason = toFinishReason(r.stopReason),
    usage = toLlmUsage(r.usage)
  )

  def fromLlmResponse(r: LlmResponse): MessagesResponse =
    val stop = fromFinishReason(r.finishReason)
    MessagesResponse(
      id = r.id,
      model = r.model,
      content = r.content.flatMap(fromLlmPart),
      stopReason = Some(stop),
      stopSequence = r.finishReason.raw.filter(_ => stop == StopReason.StopSequence),
      usage = fromLlmUsage(r.usage)
    )

  // ---------------------------------------------------------------- streams

  /** Converts Anthropic stream events to neutral stream parts; one instance per stream */
  class StreamDecoder:
    private enum Block:
      case Text, Reasoning, Tool, Raw

    private val open      = mutable.Map.empty[Int, (Block, String)]
    private val signature = mutable.Map.empty[Int, String]
    private val rawBlocks = mutable.Map.empty[Int, (ContentBlock, StringBuilder)]
    private var stop: Option[String] = None
    private var usage                = LlmUsage()
    private var finished             = false

    def decode(e: StreamEvent): Seq[LlmStreamPart] =
      import LlmStreamPart as P
      e match
        case StreamEvent.MessageStart(m) =>
          usage = toLlmUsage(m.usage)
          Seq(P.Start(m.id, m.model, usage))
        case StreamEvent.ContentBlockStart(i, b) =>
          val id = s"${i}"
          b match
            case ContentBlock.Text(t, _, _) =>
              open(i) = (Block.Text, id)
              Seq(P.TextStart(id)) ++ Option.when(t.nonEmpty)(P.TextDelta(id, t))
            case ContentBlock.Thinking(t, sig) =>
              open(i) = (Block.Reasoning, id)
              if sig.nonEmpty then
                signature(i) = sig
              Seq(P.ReasoningStart(id)) ++ Option.when(t.nonEmpty)(P.ReasoningDelta(id, t))
            case ContentBlock.RedactedThinking(d) =>
              Seq(P.ReasoningStart(id), P.ReasoningEnd(id, redactedData = Some(d)))
            case ContentBlock.ToolUse(toolId, name, input, _) =>
              open(i) = (Block.Tool, toolId)
              Seq(P.ToolInputStart(toolId, name)) ++
                Option.when(input != JSONObject.empty)(P.ToolInputDelta(toolId, input.toJSON))
            case other =>
              open(i) = (Block.Raw, id)
              rawBlocks(i) = (other, StringBuilder())
              Nil
        case StreamEvent.ContentBlockDelta(i, d) =>
          (open.get(i), d) match
            case (Some((Block.Text, id)), Delta.TextDelta(t)) =>
              Seq(P.TextDelta(id, t))
            case (Some((Block.Reasoning, id)), Delta.ThinkingDelta(t)) =>
              Seq(P.ReasoningDelta(id, t))
            case (Some((Block.Reasoning, _)), Delta.SignatureDelta(s)) =>
              signature(i) = signature.getOrElse(i, "") + s
              Nil
            case (Some((Block.Tool, id)), Delta.InputJsonDelta(p)) =>
              Seq(P.ToolInputDelta(id, p))
            case (Some((Block.Raw, _)), Delta.InputJsonDelta(p)) =>
              rawBlocks.get(i).foreach(_._2.append(p))
              Nil
            case _ =>
              Nil
        case StreamEvent.ContentBlockStop(i) =>
          open.remove(i) match
            case Some((Block.Text, id)) =>
              Seq(P.TextEnd(id))
            case Some((Block.Reasoning, id)) =>
              Seq(P.ReasoningEnd(id, signature.remove(i)))
            case Some((Block.Tool, id)) =>
              Seq(P.ToolInputEnd(id))
            case Some((Block.Raw, _)) =>
              rawBlocks
                .remove(i)
                .map { (b, sb) =>
                  val block =
                    b match
                      case s: ContentBlock.ServerToolUse if sb.nonEmpty =>
                        s.copy(input = LlmConvert.parseToolInput(sb.result()))
                      case other =>
                        other
                  P.Raw(vendor, JsonCodec[ContentBlock].encode(block))
                }
                .toSeq
            case None =>
              Nil
        case StreamEvent.MessageDelta(reason, _, u, _) =>
          stop = reason.orElse(stop)
          val next = toLlmUsage(u)
          usage = LlmUsage(
            inputTokens =
              if u.inputTokens > 0 then
                next.inputTokens
              else
                usage.inputTokens,
            outputTokens = u.outputTokens,
            cacheReadTokens = u.cacheReadInputTokens.map(_ => next.cacheReadTokens).getOrElse(usage.cacheReadTokens),
            cacheWriteTokens = u
              .cacheCreationInputTokens
              .map(_ => next.cacheWriteTokens)
              .getOrElse(usage.cacheWriteTokens)
          )
          Nil
        case StreamEvent.MessageStop =>
          finished = true
          Seq(P.Finish(toFinishReason(stop), usage))
        case StreamEvent.Error(err) =>
          Seq(P.Error(err.message, Some(err.`type`)))
        case _ =>
          Nil
      end match

    end decode

    /** Call when the upstream ends; emits a Finish if the stream had none */
    def finish(): Seq[LlmStreamPart] =
      if finished then
        Nil
      else
        finished = true
        Seq(LlmStreamPart.Finish(FinishReason(FinishReason.Kind.Interrupted), usage))

  end StreamDecoder

  /**
    * Converts neutral stream parts to Anthropic stream events, adding the framing Anthropic
    * clients expect: `message_start` first, a start and stop around every block, and `message_delta`
    * plus `message_stop` at the end.
    */
  class StreamEncoder(messageId: String = "msg_uni", model: String = ""):
    private var started   = false
    private var finished  = false
    private var index     = -1
    private var openBlock = false

    private def ensureStarted(id: String, m: String, u: LlmUsage): Seq[StreamEvent] =
      if started then
        Nil
      else
        started = true
        Seq(
          StreamEvent.MessageStart(
            MessagesResponse(
              id =
                if id.isEmpty then
                  messageId
                else
                  id,
              model =
                if m.isEmpty then
                  model
                else
                  m,
              content = Nil,
              usage = fromLlmUsage(u).copy(outputTokens = 0)
            )
          )
        )

    private def startBlock(b: ContentBlock): Seq[StreamEvent] =
      val close = closeBlock()
      index += 1
      openBlock = true
      close :+ StreamEvent.ContentBlockStart(index, b)

    private def closeBlock(): Seq[StreamEvent] =
      if openBlock then
        openBlock = false
        Seq(StreamEvent.ContentBlockStop(index))
      else
        Nil

    private def delta(d: Delta): Seq[StreamEvent] =
      if openBlock then
        Seq(StreamEvent.ContentBlockDelta(index, d))
      else
        Nil

    def encode(p: LlmStreamPart): Seq[StreamEvent] =
      import LlmStreamPart as P
      val prefix =
        p match
          case P.Start(id, m, u) =>
            ensureStarted(id, m, u)
          case _ =>
            ensureStarted("", "", LlmUsage())
      val body: Seq[StreamEvent] =
        p match
          case _: P.Start =>
            Nil
          case P.TextStart(_, md) =>
            carrierBefore(md).flatMap(b => startBlock(b) ++ closeBlock()) ++
              startBlock(ContentBlock.Text(""))
          case P.TextDelta(_, d) =>
            delta(Delta.TextDelta(d))
          case _: P.TextEnd =>
            closeBlock()
          case _: P.ReasoningStart =>
            startBlock(ContentBlock.Thinking("", ""))
          case P.ReasoningDelta(_, d) =>
            delta(Delta.ThinkingDelta(d))
          case P.ReasoningEnd(_, sig, redacted, md) =>
            val s = sig.orElse(
              md.find(_._1 != vendor).map((v, data) => Carrier.encode(v, data))
            ).orElse(
              // Redacted reasoning of another vendor has no text; carry it as a signature
              redacted.map(d => Carrier.encode("redacted", Json.obj(Json.field("data", d))))
            )
            s.toSeq.flatMap(x => delta(Delta.SignatureDelta(x))) ++ closeBlock()
          case P.ToolInputStart(id, name, _, md) =>
            carrierBefore(md).flatMap(b => startBlock(b) ++ closeBlock()) ++
              startBlock(ContentBlock.ToolUse(LlmConvert.anthropicToolId(id), name))
          case P.ToolInputDelta(_, d) =>
            delta(Delta.InputJsonDelta(d))
          case _: P.ToolInputEnd =>
            closeBlock()
          case P.Raw(`vendor`, raw: JSONObject) =>
            startBlock(JsonCodec[ContentBlock].decode(raw)) ++ closeBlock()
          case _: P.Raw =>
            Nil
          case P.Finish(reason, u) =>
            finished = true
            val stop = fromFinishReason(reason)
            closeBlock() ++
              Seq(
                StreamEvent.MessageDelta(
                  Some(stop),
                  reason.raw.filter(_ => stop == StopReason.StopSequence),
                  fromLlmUsage(u)
                ),
                StreamEvent.MessageStop
              )
          case P.Error(msg, tpe) =>
            Seq(StreamEvent.Error(ErrorDetail(tpe.getOrElse(ErrorDetail.Api), msg)))
      prefix ++ body

    end encode

    /** Call when the stream ends; closes the message if no Finish part came */
    def finish(): Seq[StreamEvent] =
      if finished then
        Nil
      else
        encode(LlmStreamPart.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamEncoder

end AnthropicConverter
