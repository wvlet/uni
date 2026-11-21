package wvlet.uni.ai.openai

import wvlet.uni.ai.*
import wvlet.uni.ai.LlmConvert.Carrier
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Converts OpenAI Responses types to and from the vendor-neutral model.
  *
  * Reasoning items keep their id and `encrypted_content` in `providerMetadata("openai")`. Signed
  * reasoning of another vendor (an Anthropic thinking signature) is carried in `encrypted_content`
  * with a [[LlmConvert.Carrier]] prefix, so a stateless Responses client returns it unchanged.
  */
object ResponsesConverter:
  private val vendor = ProviderMetadata.OpenAI

  /** The include value that returns encrypted reasoning for stateless (`store: false`) use */
  val EncryptedReasoning: String = "reasoning.encrypted_content"

  // ---------------------------------------------------------------- request

  def toLlmRequest(r: ResponsesRequest): LlmRequest =
    val system = Seq.newBuilder[LlmPart.Text]
    r.instructions.foreach(i => system += LlmPart.Text(i))
    val messages                             = mutable.ArrayBuffer.empty[LlmMessage]
    def add(role: LlmRole, p: LlmPart): Unit =
      messages.lastOption match
        case Some(last) if last.role == role =>
          messages(messages.length - 1) = last.copy(parts = last.parts :+ p)
        case _ =>
          messages += LlmMessage(role, Seq(p))

    r.input
      .foreach {
        case Item.Message(role @ ("system" | "developer"), content, _, _) =>
          system +=
            LlmPart.Text(
              content
                .collect { case ContentPart.InputText(t) =>
                  t
                }
                .mkString
            )
        case Item.Message(role, content, _, _) =>
          val r =
            if role == "assistant" then
              LlmRole.Assistant
            else
              LlmRole.User
          content.flatMap(toLlmPart).foreach(add(r, _))
        case Item.FunctionCall(callId, name, args, id, _) =>
          add(
            LlmRole.Assistant,
            LlmPart.ToolCall(
              callId,
              name,
              LlmConvert.parseToolInput(args),
              providerMetadata = ProviderMetadata.of(vendor, Json.opt("itemId", id))
            )
          )
        case Item.FunctionCallOutput(callId, output, _, _) =>
          add(LlmRole.Tool, LlmPart.ToolResult(callId, outputParts(output)))
        case r: Item.Reasoning =>
          add(LlmRole.Assistant, toLlmReasoning(r))
        case Item.Unknown(raw) =>
          add(LlmRole.Assistant, LlmPart.Raw(vendor, raw))
      }

    val extra =
      r.extra ++ r.store.map(b => "store" -> JSONBoolean(b)) ++
        r.previousResponseId.map(x => "previous_response_id" -> JSONString(x)) ++
        Option.when(r.include.nonEmpty)("include" -> JsonCodec[Seq[String]].encode(r.include)) ++
        r.metadata.map("metadata" -> _) ++
        r.serviceTier.map(x => "service_tier" -> JSONString(x)) ++
        r.promptCacheKey.map(x => "prompt_cache_key" -> JSONString(x))

    LlmRequest(
      model = r.model,
      messages = messages.toSeq,
      system = system.result(),
      tools = r.tools.map(toLlmTool),
      toolChoice = r.toolChoice.flatMap(toLlmToolChoice),
      parallelToolCalls = r.parallelToolCalls,
      maxOutputTokens = r.maxOutputTokens,
      temperature = r.temperature,
      topP = r.topP,
      reasoning = r
        .reasoning
        .flatMap(_.effort)
        .map(e =>
          ReasoningOptions(
            effort = Some(e),
            includeThoughts = r.reasoning.exists(_.summary.isDefined)
          )
        ),
      stream = r.stream.contains(true),
      providerOptions = LlmConvert.providerOptionsOf(vendor, extra)
    )

  end toLlmRequest

  private[openai] def toLlmTool(t: Tool): LlmTool =
    t match
      case Tool.Function(name, params, desc, _) =>
        LlmTool.Function(name, params.getOrElse(Json.obj(Json.field("type", "object"))), desc)
      case Tool.Unknown(raw) =>
        LlmTool.Provider(vendor, raw)

  private[openai] def toLlmToolChoice(c: ToolChoice): Option[LlmToolChoice] =
    c match
      case ToolChoice.Auto =>
        Some(LlmToolChoice.Auto)
      case ToolChoice.NoTool =>
        Some(LlmToolChoice.NoTool)
      case ToolChoice.Required =>
        Some(LlmToolChoice.Required)
      case ToolChoice.Function(n) =>
        Some(LlmToolChoice.Tool(n))
      case ToolChoice.Unknown(_) =>
        None

  private[openai] def fromLlmToolChoice(c: LlmToolChoice): ToolChoice =
    c match
      case LlmToolChoice.Auto =>
        ToolChoice.Auto
      case LlmToolChoice.NoTool =>
        ToolChoice.NoTool
      case LlmToolChoice.Required =>
        ToolChoice.Required
      case LlmToolChoice.Tool(n) =>
        ToolChoice.Function(n)

  private def toLlmPart(p: ContentPart): Option[LlmPart] =
    p match
      case ContentPart.InputText(t) =>
        Some(LlmPart.Text(t))
      case ContentPart.OutputText(t, _) =>
        Some(LlmPart.Text(t))
      case ContentPart.Refusal(t) =>
        Some(LlmPart.Text(t))
      case ContentPart.InputImage(url, fileId, _) =>
        url
          .map(u => LlmConvert.fileFromUrl(u, "image/jpeg"))
          .orElse(fileId.map(id => LlmPart.File("image/jpeg", FileSource.FileId(id))))
      case ContentPart.InputFile(fileId, data, name, url) =>
        data
          .map(d =>
            LlmConvert
              .parseDataUrl(d)
              .map((m, b) => LlmPart.File(m, FileSource.Base64(b), name))
              .getOrElse(LlmPart.File("application/pdf", FileSource.Base64(d), name))
          )
          .orElse(fileId.map(id => LlmPart.File("application/pdf", FileSource.FileId(id), name)))
          .orElse(url.map(u => LlmPart.File("application/pdf", FileSource.Url(u), name)))
      case ContentPart.Unknown(raw) =>
        Some(LlmPart.Raw(vendor, raw))

  private def outputParts(v: JSONValue): Seq[LlmPart] =
    v match
      case JSONString(s) =>
        Seq(LlmPart.Text(s))
      case arr: JSONArray =>
        JsonCodec[Seq[ContentPart]].decode(arr).flatMap(toLlmPart)
      case other =>
        Seq(LlmPart.Text(other.toJSON))

  private def toLlmReasoning(r: Item.Reasoning): LlmPart.Reasoning =
    val text = (r.summary ++ r.content).map(_.text).mkString("\n\n")
    r.encryptedContent.flatMap(Carrier.decode) match
      case Some((ProviderMetadata.Anthropic, data)) =>
        val o = JsonObj(data)
        LlmPart.Reasoning(text, o.strOpt("signature"), o.strOpt("redactedData"))
      case Some((other, data)) =>
        LlmPart.Reasoning(text, providerMetadata = Map(other -> data))
      case None =>
        LlmPart.Reasoning(
          text,
          providerMetadata = ProviderMetadata.of(
            vendor,
            Json.field("itemId", r.id),
            Json.opt("encryptedContent", r.encryptedContent)
          )
        )

  def fromLlmRequest(r: LlmRequest): ResponsesRequest =
    val input = r
      .messages
      .flatMap { m =>
        val items = mutable.ArrayBuffer.empty[Item]
        m.parts
          .foreach { p =>
            fromLlmPart(m.role, p, toClient = false).foreach { item =>
              (items.lastOption, item) match
                case (Some(prev: Item.Message), next: Item.Message) if prev.role == next.role =>
                  items(items.length - 1) = prev.copy(content = prev.content ++ next.content)
                case _ =>
                  items += item
            }
          }
        items.toSeq
      }
    val hasReasoning = r.reasoning.exists(_.effortLevel != "none")
    val req          = ResponsesRequest(
      model = r.model,
      input = input,
      instructions = Some(r.systemText).filter(_.nonEmpty),
      tools = r
        .tools
        .collect {
          case LlmTool.Function(name, schema, desc, _) =>
            Tool.Function(name, Some(schema), desc)
          case LlmTool.Provider(`vendor`, raw: JSONObject) =>
            Tool.Unknown(raw)
        },
      toolChoice = r.toolChoice.map(fromLlmToolChoice),
      parallelToolCalls = r.parallelToolCalls,
      maxOutputTokens = r.maxOutputTokens,
      temperature = r.temperature.filter(_ => !hasReasoning),
      topP = r.topP.filter(_ => !hasReasoning),
      reasoning = r
        .reasoning
        .filter(_ => hasReasoning)
        .map(o =>
          ReasoningConfig(Some(o.effortLevel), Some("auto").filter(_ => o.includeThoughts))
        ),
      stream =
        if r.stream then
          Some(true)
        else
          None
      ,
      // Stateless by default, so encrypted reasoning comes back and is replayed by the client
      store = Some(false),
      include = Seq(EncryptedReasoning).filter(_ => hasReasoning)
    )
    LlmConvert.withProviderOptions(req, r.providerOptions, vendor)

  end fromLlmRequest

  private def inputPart(p: LlmPart): Option[ContentPart] =
    p match
      case LlmPart.Text(t, _) =>
        Some(ContentPart.InputText(t))
      case f: LlmPart.File if f.isImage =>
        f.source match
          case FileSource.FileId(id) =>
            Some(ContentPart.InputImage(fileId = Some(id)))
          case _ =>
            Some(ContentPart.InputImage(imageUrl = LlmConvert.urlOf(f)))
      case f: LlmPart.File =>
        f.source match
          case FileSource.Base64(d) =>
            Some(
              ContentPart.InputFile(
                fileData = Some(LlmConvert.dataUrl(f.mediaType, d)),
                filename = f.filename.orElse(Some("document"))
              )
            )
          case FileSource.Url(u) =>
            Some(ContentPart.InputFile(fileUrl = Some(u)))
          case FileSource.FileId(id) =>
            Some(ContentPart.InputFile(fileId = Some(id)))
      case LlmPart.Raw(`vendor`, raw: JSONObject) =>
        Some(ContentPart.Unknown(raw))
      case _ =>
        None

  /**
    * @param toClient
    *   true when the items go to a Responses client (a proxy's response), which returns carriers
    *   unchanged; false when they go to OpenAI, which cannot decrypt them
    */
  private def fromLlmPart(role: LlmRole, p: LlmPart, toClient: Boolean): Option[Item] =
    p match
      case LlmPart.Text(t, _) if role == LlmRole.Assistant =>
        Some(Item.Message("assistant", Seq(ContentPart.OutputText(t))))
      case r: LlmPart.Reasoning =>
        fromLlmReasoning(r, toClient)
      case LlmPart.ToolCall(id, name, input, _, md) =>
        Some(
          Item.FunctionCall(
            id,
            name,
            LlmConvert.toolInputString(input),
            md.string(vendor, "itemId")
          )
        )
      case r: LlmPart.ToolResult =>
        Some(Item.FunctionCallOutput(r.id, JSONString(LlmConvert.toolOutputText(r.output))))
      case LlmPart.Raw(`vendor`, raw: JSONObject) if role == LlmRole.Assistant =>
        Some(JsonCodec[Item].decode(raw))
      case other =>
        inputPart(other).map(c => Item.Message("user", Seq(c)))

  private def fromLlmReasoning(r: LlmPart.Reasoning, toClient: Boolean): Option[Item] =
    val summary = Seq(ReasoningText(r.text)).filter(_.text.nonEmpty)
    (r.providerMetadata.get(vendor), r.signature, r.redactedData) match
      case (Some(md), _, _) =>
        val o = JsonObj(md)
        Some(
          Item.Reasoning(
            o.strOrEmpty("itemId"),
            summary,
            encryptedContent = o.strOpt("encryptedContent")
          )
        )
      case (None, sig, red) if toClient && (sig.isDefined || red.isDefined) =>
        val data = Json.obj(Json.opt("signature", sig), Json.opt("redactedData", red))
        Some(
          Item.Reasoning(
            s"rs_${math.abs(data.toJSON.hashCode)}",
            summary,
            encryptedContent = Some(Carrier.encode(ProviderMetadata.Anthropic, data))
          )
        )
      case _ =>
        // Unsigned reasoning of another vendor cannot be replayed
        None

  // ---------------------------------------------------------------- response

  def toFinishReason(r: Response): FinishReason =
    r.status match
      case ResponseStatus.Incomplete =>
        r.incompleteReason match
          case Some("content_filter") =>
            FinishReason(FinishReason.Kind.ContentFilter, Some("content_filter"))
          case other =>
            FinishReason(FinishReason.Kind.Length, other.orElse(Some("max_output_tokens")))
      case ResponseStatus.Failed =>
        FinishReason(FinishReason.Kind.Error, r.error.flatMap(_.code))
      case ResponseStatus.Completed =>
        if r.functionCalls.nonEmpty then
          FinishReason.toolCalls
        else if r
            .output
            .exists {
              case m: Item.Message =>
                m.content.exists(_.isInstanceOf[ContentPart.Refusal])
              case _ =>
                false
            }
        then
          FinishReason(FinishReason.Kind.ContentFilter, Some("refusal"))
        else
          FinishReason.stop
      case other =>
        FinishReason(FinishReason.Kind.Other, Some(other))

  def toLlmUsage(u: ResponseUsage): LlmUsage = LlmUsage(
    inputTokens = u.inputTokens,
    outputTokens = u.outputTokens,
    cacheReadTokens = u.cachedTokens.getOrElse(0L),
    reasoningTokens = u.reasoningTokens.getOrElse(0L)
  )

  def fromLlmUsage(u: LlmUsage): ResponseUsage = ResponseUsage(
    inputTokens = u.inputTokens,
    outputTokens = u.outputTokens,
    totalTokens = u.totalTokens,
    cachedTokens = Some(u.cacheReadTokens),
    reasoningTokens = Some(u.reasoningTokens)
  )

  def toLlmResponse(r: Response): LlmResponse = LlmResponse(
    id = r.id,
    model = r.model,
    content = r
      .output
      .flatMap {
        case Item.Message(_, content, _, _) =>
          content.flatMap(toLlmPart)
        case Item.FunctionCall(callId, name, args, id, _) =>
          Seq(
            LlmPart.ToolCall(
              callId,
              name,
              LlmConvert.parseToolInput(args),
              providerMetadata = ProviderMetadata.of(vendor, Json.opt("itemId", id))
            )
          )
        case r: Item.Reasoning =>
          Seq(toLlmReasoning(r))
        case other =>
          Seq(LlmPart.Raw(vendor, JsonCodec[Item].encode(other)))
      },
    finishReason = toFinishReason(r),
    usage = r.usage.map(toLlmUsage).getOrElse(LlmUsage())
  )

  private def statusOf(f: FinishReason): (String, Option[String]) =
    f.kind match
      case FinishReason.Kind.Length =>
        (ResponseStatus.Incomplete, Some("max_output_tokens"))
      case FinishReason.Kind.Error =>
        (ResponseStatus.Failed, None)
      case _ =>
        (ResponseStatus.Completed, None)

  def fromLlmResponse(r: LlmResponse, createdAt: Long = 0): Response =
    val (status, incomplete) = statusOf(r.finishReason)
    val output               = mutable.ArrayBuffer.empty[Item]
    r.content
      .foreach { p =>
        fromLlmPart(LlmRole.Assistant, p, toClient = true).foreach {
          case m: Item.Message =>
            output.lastOption match
              case Some(prev: Item.Message) =>
                output(output.length - 1) = prev.copy(content = prev.content ++ m.content)
              case _ =>
                output += m.copy(id = Some(s"msg_${output.length}"), status = Some("completed"))
          case f: Item.FunctionCall =>
            output +=
              f.copy(id = f.id.orElse(Some(s"fc_${output.length}")), status = Some("completed"))
          case other =>
            output += other
        }
      }
    Response(
      id = r.id,
      model = r.model,
      output = output.toSeq,
      status = status,
      createdAt = createdAt,
      usage = Some(fromLlmUsage(r.usage)),
      incompleteReason = incomplete,
      error =
        Option.when(status == ResponseStatus.Failed)(
          ResponseError(r.finishReason.raw.getOrElse("error"), Some("server_error"))
        )
    )

  end fromLlmResponse

  // ---------------------------------------------------------------- streams

  /** Converts Responses stream events to neutral stream parts; one instance per stream */
  class StreamDecoder:
    import ResponseStreamEvent as E
    import LlmStreamPart as P

    // item id -> tool call id, for argument deltas
    private val callIds     = mutable.Map.empty[String, String]
    private val argsSeen    = mutable.Set.empty[String]
    private val summarySeen = mutable.Map.empty[String, Int]
    private var finished    = false

    def decode(e: ResponseStreamEvent): Seq[LlmStreamPart] =
      e match
        case E.Lifecycle("response.created", r, _) =>
          Seq(P.Start(r.id, r.model))
        case E.Lifecycle(t, r, _)
            if t == "response.completed" || t == "response.incomplete" || t == "response.failed" =>
          finished = true
          Seq(P.Finish(toFinishReason(r), r.usage.map(toLlmUsage).getOrElse(LlmUsage())))
        case E.OutputItem("response.output_item.added", _, item, _) =>
          item match
            case f: Item.FunctionCall =>
              val itemId = f.id.getOrElse(f.callId)
              callIds(itemId) = f.callId
              Seq(
                P.ToolInputStart(
                  f.callId,
                  f.name,
                  providerMetadata = ProviderMetadata.of(vendor, Json.field("itemId", itemId))
                )
              )
            case r: Item.Reasoning =>
              Seq(P.ReasoningStart(r.id))
            case _ =>
              Nil
        case E.OutputItem("response.output_item.done", _, item, _) =>
          item match
            case f: Item.FunctionCall =>
              val itemId = f.id.getOrElse(f.callId)
              val pre    =
                if argsSeen.contains(itemId) || f.arguments.isEmpty then
                  Nil
                else
                  Seq(P.ToolInputDelta(f.callId, f.arguments))
              pre :+ P.ToolInputEnd(f.callId)
            case r: Item.Reasoning =>
              val md = toLlmReasoning(r)
              Seq(P.ReasoningEnd(r.id, md.signature, md.redactedData, md.providerMetadata))
            case _: Item.Message =>
              Nil
            case other =>
              Seq(P.Raw(vendor, JsonCodec[Item].encode(other)))
        case E.ContentPartEvent("response.content_part.added", itemId, _, ci, part, _) =>
          part match
            case _: ContentPart.OutputText | _: ContentPart.Refusal =>
              Seq(P.TextStart(s"${itemId}:${ci}"))
            case _ =>
              Nil
        case E.ContentPartEvent("response.content_part.done", itemId, _, ci, part, _) =>
          part match
            case _: ContentPart.OutputText | _: ContentPart.Refusal =>
              Seq(P.TextEnd(s"${itemId}:${ci}"))
            case _ =>
              Nil
        case E.TextDelta(t, itemId, _, i, d, _) =>
          if t == E.OutputTextDelta || t == E.RefusalDelta then
            Seq(P.TextDelta(s"${itemId}:${i}", d))
          else if t == E.FunctionArgumentsDelta then
            argsSeen += itemId
            Seq(P.ToolInputDelta(callIds.getOrElse(itemId, itemId), d))
          else if t == E.ReasoningSummaryDelta then
            // Separate summary parts with a blank line
            val prev = summarySeen.getOrElseUpdate(itemId, i)
            val sep  =
              if prev != i then
                summarySeen(itemId) = i
                "\n\n"
              else
                ""
            Seq(P.ReasoningDelta(itemId, sep + d))
          else if t == E.ReasoningTextDelta then
            Seq(P.ReasoningDelta(itemId, d))
          else
            Nil
        case E.Error(err, _) =>
          Seq(P.Error(err.message, err.code))
        case _ =>
          Nil

    def finish(): Seq[LlmStreamPart] =
      if finished then
        Nil
      else
        finished = true
        Seq(LlmStreamPart.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamDecoder

  /**
    * Converts neutral stream parts to Responses stream events: `response.created` first, output
    * items with their content parts, and a final `response.completed` (or `incomplete`/`failed`)
    * carrying the whole response.
    */
  class StreamEncoder(responseId: String = "resp_uni", model: String = "", createdAt: Long = 0):
    import ResponseStreamEvent as E
    import LlmStreamPart as P

    private var seq         = 0L
    private var started     = false
    private var finished    = false
    private var id          = responseId
    private var modelName   = model
    private var outputIndex = -1
    private var itemId      = ""
    private var callId      = ""
    private var toolName    = ""
    private val text        = StringBuilder()
    private val acc         = LlmStreamAccumulator()

    private def next(): Long =
      seq += 1
      seq - 1

    private def response(status: String): Response = Response(id, modelName, Nil, status, createdAt)

    private def ensureStarted(): Seq[ResponseStreamEvent] =
      if started then
        Nil
      else
        started = true
        Seq(
          E.Lifecycle("response.created", response(ResponseStatus.InProgress), next()),
          E.Lifecycle("response.in_progress", response(ResponseStatus.InProgress), next())
        )

    private def openItem(item: Item): Seq[ResponseStreamEvent] =
      outputIndex += 1
      text.clear()
      Seq(E.OutputItem("response.output_item.added", outputIndex, item, next()))

    def encode(p: LlmStreamPart): Seq[ResponseStreamEvent] =
      acc.add(p)
      val prefix =
        p match
          case P.Start(i, m, _) =>
            if i.nonEmpty then
              id = i
            if m.nonEmpty then
              modelName = m
            ensureStarted()
          case _ =>
            ensureStarted()
      val body: Seq[ResponseStreamEvent] =
        p match
          case _: P.Start =>
            Nil
          case _: P.TextStart =>
            itemId = s"msg_${outputIndex + 1}"
            openItem(Item.Message("assistant", Nil, Some(itemId), Some("in_progress"))) :+
              E.ContentPartEvent(
                "response.content_part.added",
                itemId,
                outputIndex,
                0,
                ContentPart.OutputText(""),
                next()
              )
          case P.TextDelta(_, d) =>
            text.append(d)
            Seq(E.TextDelta(E.OutputTextDelta, itemId, outputIndex, 0, d, next()))
          case _: P.TextEnd =>
            val t = text.result()
            Seq(
              E.TextDone(E.OutputTextDone, itemId, outputIndex, 0, t, next()),
              E.ContentPartEvent(
                "response.content_part.done",
                itemId,
                outputIndex,
                0,
                ContentPart.OutputText(t),
                next()
              ),
              E.OutputItem(
                "response.output_item.done",
                outputIndex,
                Item.Message(
                  "assistant",
                  Seq(ContentPart.OutputText(t)),
                  Some(itemId),
                  Some("completed")
                ),
                next()
              )
            )
          case _: P.ReasoningStart =>
            itemId = s"rs_${outputIndex + 1}"
            openItem(Item.Reasoning(itemId)) :+
              E.ReasoningSummaryPart(
                "response.reasoning_summary_part.added",
                itemId,
                outputIndex,
                0,
                ReasoningText(""),
                next()
              )
          case P.ReasoningDelta(_, d) =>
            text.append(d)
            Seq(E.TextDelta(E.ReasoningSummaryDelta, itemId, outputIndex, 0, d, next()))
          case P.ReasoningEnd(_, sig, red, md) =>
            val t    = text.result()
            val item = fromLlmReasoning(LlmPart.Reasoning(t, sig, red, md), toClient = true)
              .collect { case r: Item.Reasoning =>
                r.copy(id = itemId)
              }
              .getOrElse(Item.Reasoning(itemId, Seq(ReasoningText(t)).filter(_.text.nonEmpty)))
            Seq(
              E.TextDone(E.ReasoningSummaryDone, itemId, outputIndex, 0, t, next()),
              E.ReasoningSummaryPart(
                "response.reasoning_summary_part.done",
                itemId,
                outputIndex,
                0,
                ReasoningText(t),
                next()
              ),
              E.OutputItem("response.output_item.done", outputIndex, item, next())
            )
          case P.ToolInputStart(cid, name, _, _) =>
            itemId = s"fc_${outputIndex + 1}"
            callId = cid
            toolName = name
            openItem(Item.FunctionCall(cid, name, "", Some(itemId), Some("in_progress")))
          case P.ToolInputDelta(_, d) =>
            text.append(d)
            Seq(E.TextDelta(E.FunctionArgumentsDelta, itemId, outputIndex, 0, d, next()))
          case _: P.ToolInputEnd =>
            val args = text.result()
            Seq(
              E.TextDone(E.FunctionArgumentsDone, itemId, outputIndex, 0, args, next()),
              E.OutputItem(
                "response.output_item.done",
                outputIndex,
                Item.FunctionCall(callId, toolName, args, Some(itemId), Some("completed")),
                next()
              )
            )
          case P.Raw(`vendor`, raw: JSONObject) =>
            val item = JsonCodec[Item].decode(raw)
            openItem(item) :+ E.OutputItem("response.output_item.done", outputIndex, item, next())
          case _: P.Raw =>
            Nil
          case _: P.Finish =>
            finished = true
            val r = fromLlmResponse(acc.result.copy(id = id, model = modelName), createdAt)
            val t =
              r.status match
                case ResponseStatus.Incomplete =>
                  "response.incomplete"
                case ResponseStatus.Failed =>
                  "response.failed"
                case _ =>
                  "response.completed"
            Seq(E.Lifecycle(t, r, next()))
          case P.Error(msg, tpe) =>
            Seq(E.Error(ResponseError(msg, tpe), next()))
      prefix ++ body

    end encode

    def finish(): Seq[ResponseStreamEvent] =
      if finished then
        Nil
      else
        encode(LlmStreamPart.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamEncoder

end ResponsesConverter
