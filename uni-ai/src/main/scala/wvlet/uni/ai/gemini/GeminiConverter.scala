package wvlet.uni.ai.gemini

import wvlet.uni.ai.*
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Converts Gemini `generateContent` types to and from the vendor-neutral model.
  *
  * Thought signatures are kept in `providerMetadata("gemini")("thoughtSignature")` on the part they
  * came with. Function calls without an id (models before Gemini 3) get a deterministic id, and
  * their responses are matched by function name and order.
  */
object GeminiConverter:
  private val vendor = ProviderMetadata.Gemini

  /**
    * The documented placeholder for a function call in history that has no thought signature (e.g.
    * one produced by another vendor). Gemini 3 rejects such calls otherwise.
    */
  val SkipThoughtSignature: String = "skip_thought_signature_validator"

  private def sigMeta(sig: Option[String]): ProviderMetadata = ProviderMetadata.of(
    vendor,
    Json.opt("thoughtSignature", sig)
  )

  private def sigOf(md: ProviderMetadata): Option[String] = md.string(vendor, "thoughtSignature")

  // ---------------------------------------------------------------- request

  def toLlmRequest(r: GenerateContentRequest, model: String = ""): LlmRequest =
    // Pair calls and responses without ids by name and order
    val callCount     = mutable.Map.empty[String, Int].withDefaultValue(0)
    val responseCount = mutable.Map.empty[String, Int].withDefaultValue(0)
    def callId(name: String, id: Option[String], counter: mutable.Map[String, Int]): String = id
      .getOrElse {
        val n = counter(name)
        counter(name) = n + 1
        LlmConvert.generatedToolId(name, n)
      }

    val messages = r
      .contents
      .flatMap { c =>
        val isModel = c.role.contains("model")
        val results = Seq.newBuilder[LlmPart]
        val parts   = Seq.newBuilder[LlmPart]
        c.parts
          .foreach {
            case t @ Part.Text(text, _, sig) =>
              if t.isThought then
                parts += LlmPart.Reasoning(text, providerMetadata = sigMeta(sig))
              else
                parts += LlmPart.Text(text, sigMeta(sig))
            case Part.InlineData(m, d, _) =>
              parts += LlmPart.File(m, FileSource.Base64(d))
            case Part.FileData(m, uri, _) =>
              parts += LlmPart.File(m.getOrElse("application/octet-stream"), FileSource.Url(uri))
            case Part.FunctionCall(name, args, id, sig, _, _) =>
              parts +=
                LlmPart.ToolCall(
                  callId(name, id, callCount),
                  name,
                  args,
                  providerMetadata = sigMeta(sig)
                )
            case Part.FunctionResponse(name, resp, id, _) =>
              results +=
                LlmPart.ToolResult(
                  callId(name, id, responseCount),
                  Seq(LlmPart.Text(responseText(resp))),
                  name = Some(name)
                )
            case u: Part.Unknown =>
              parts += LlmPart.Raw(vendor, u.raw)
          }
        Seq(
          LlmMessage(LlmRole.Tool, results.result()),
          LlmMessage(
            if isModel then
              LlmRole.Assistant
            else
              LlmRole.User
            ,
            parts.result()
          )
        ).filter(_.parts.nonEmpty)
      }

    val fc    = r.functionCallingConfig
    val gen   = r.generationConfig.getOrElse(GenerationConfig())
    val think = gen.thinkingConfig
    val extra =
      r.extra ++ Option.when(gen.extra.nonEmpty)("generationConfig" -> JSONObject(gen.extra.toSeq))
    LlmRequest(
      model = model,
      messages = messages,
      system = r
        .systemInstruction
        .toSeq
        .flatMap(_.parts)
        .collect { case Part.Text(t, _, _) =>
          LlmPart.Text(t)
        },
      tools = r
        .tools
        .flatMap { t =>
          t.functionDeclarations
            .map(f =>
              LlmTool.Function(
                f.name,
                f.parametersJsonSchema
                  .orElse(f.parameters)
                  .getOrElse(Json.obj(Json.field("type", "object"))),
                f.description
              )
            ) ++ t.extra.map((k, v) => LlmTool.Provider(vendor, Json.obj(k -> Some(v))))
        },
      toolChoice = fc.flatMap(c =>
        c.mode
          .map {
            case "ANY" if c.allowedFunctionNames.size == 1 =>
              LlmToolChoice.Tool(c.allowedFunctionNames.head)
            case "ANY" =>
              LlmToolChoice.Required
            case "NONE" =>
              LlmToolChoice.NoTool
            case _ =>
              LlmToolChoice.Auto
          }
      ),
      maxOutputTokens = gen.maxOutputTokens,
      temperature = gen.temperature,
      topP = gen.topP,
      topK = gen.topK,
      stopSequences = gen.stopSequences,
      reasoning = think.map(t =>
        ReasoningOptions(
          effort = t.thinkingLevel.orElse(t.thinkingBudget.filter(_ == 0).map(_ => "none")),
          budgetTokens = t.thinkingBudget.filter(_ > 0),
          includeThoughts = t.includeThoughts.contains(true)
        )
      ),
      providerOptions = LlmConvert.providerOptionsOf(vendor, extra)
    )

  end toLlmRequest

  private def responseText(resp: JSONValue): String =
    resp match
      case JSONObject(Seq(("result" | "output", JSONString(s)))) =>
        s
      case other =>
        other.toJSON

  /** A function response must be a JSON object; text that is not one is wrapped as `result` */
  private def responseJson(text: String): JSONValue =
    Json.parseOrString(text) match
      case o: JSONObject =>
        o
      case _ =>
        Json.obj(Json.field("result", text))

  /**
    * @param includeFunctionCallIds
    *   send `functionCall.id` and `functionResponse.id`; Vertex AI `v1` rejects them, so turn this
    *   off there
    */
  def fromLlmRequest(
      r: LlmRequest,
      includeFunctionCallIds: Boolean = true
  ): GenerateContentRequest =
    val toolNames                                 = mutable.Map.empty[String, String]
    val contents                                  = mutable.ArrayBuffer.empty[Content]
    def add(role: String, parts: Seq[Part]): Unit =
      if parts.nonEmpty then
        contents.lastOption match
          case Some(last) if last.role.contains(role) =>
            contents(contents.length - 1) = last.copy(parts = last.parts ++ parts)
          case _ =>
            contents += Content(Some(role), parts)
    def id(x: String): Option[String] = Some(x).filter(_ => includeFunctionCallIds)

    r.messages
      .foreach { m =>
        m.role match
          case LlmRole.Assistant =>
            val parts = m
              .parts
              .flatMap {
                case LlmPart.Text(t, md) =>
                  Some(Part.Text(t, None, sigOf(md)))
                case LlmPart.Reasoning(t, _, _, md) =>
                  // Only Gemini's own thoughts can be replayed
                  sigOf(md).map(s => Part.Text(t, Some(true), Some(s)))
                case LlmPart.ToolCall(tid, name, input, false, md) =>
                  toolNames(tid) = name
                  val args =
                    input match
                      case o: JSONObject =>
                        o
                      case other =>
                        Json.obj(Json.field("input", other))
                  Some(Part.FunctionCall(name, args, id(tid), sigOf(md)))
                case LlmPart.Raw(`vendor`, raw: JSONObject) =>
                  Some(JsonCodec[Part].decode(raw))
                case _ =>
                  None
              }
            // Gemini 3 needs a signature on the first function call of a turn
            val firstCall = parts.indexWhere(_.isInstanceOf[Part.FunctionCall])
            val signed    =
              if firstCall >= 0 && parts.forall(_.thoughtSignature.isEmpty) then
                parts.updated(
                  firstCall,
                  parts(firstCall)
                    .asInstanceOf[Part.FunctionCall]
                    .copy(thoughtSignature = Some(SkipThoughtSignature))
                )
              else
                parts
            add("model", signed)
          case _ =>
            add(
              "user",
              m.parts
                .flatMap {
                  case LlmPart.Text(t, md) =>
                    Some(Part.Text(t, None, sigOf(md)))
                  case f: LlmPart.File =>
                    f.source match
                      case FileSource.Base64(d) =>
                        Some(Part.InlineData(f.mediaType, d))
                      case FileSource.Url(u) =>
                        Some(Part.FileData(Some(f.mediaType), u))
                      case FileSource.FileId(fid) =>
                        Some(Part.FileData(Some(f.mediaType), fid))
                  case t: LlmPart.ToolResult =>
                    val name = t.name.orElse(toolNames.get(t.id)).getOrElse("")
                    Some(
                      Part.FunctionResponse(
                        name,
                        responseJson(LlmConvert.toolOutputText(t.output)),
                        id(t.id)
                      )
                    )
                  case LlmPart.Raw(`vendor`, raw: JSONObject) =>
                    Some(JsonCodec[Part].decode(raw))
                  case _ =>
                    None
                }
            )
      }

    val functions = r
      .tools
      .collect { case LlmTool.Function(name, schema, desc, _) =>
        FunctionDeclaration(name, desc, parametersJsonSchema = Some(schema))
      }
    val builtins = r
      .tools
      .collect { case LlmTool.Provider(`vendor`, raw: JSONObject) =>
        Tool(extra = raw.v.toMap)
      }
    val optFields = r.providerOptions.get(vendor).map(_.v.toMap).getOrElse(Map.empty)
    val genExtra  = optFields
      .get("generationConfig")
      .collect { case o: JSONObject =>
        o.v.toMap
      }
      .getOrElse(Map.empty)
    val thinking = r
      .reasoning
      .map { o =>
        if o.effortLevel == "none" then
          ThinkingConfig(thinkingBudget = Some(0))
        else
          ThinkingConfig(thinkingBudget = Some(o.budget), includeThoughts = Some(o.includeThoughts))
      }

    GenerateContentRequest(
      contents = contents.toSeq,
      systemInstruction = Some(r.systemText)
        .filter(_.nonEmpty)
        .map(t => Content(None, Seq(Part.Text(t)))),
      tools = Seq(Tool(functions)).filter(_.functionDeclarations.nonEmpty) ++ builtins,
      functionCallingConfig = r
        .toolChoice
        .map {
          case LlmToolChoice.Auto =>
            FunctionCallingConfig(Some("AUTO"))
          case LlmToolChoice.Required =>
            FunctionCallingConfig(Some("ANY"))
          case LlmToolChoice.NoTool =>
            FunctionCallingConfig(Some("NONE"))
          case LlmToolChoice.Tool(n) =>
            FunctionCallingConfig(Some("ANY"), Seq(n))
        },
      generationConfig = Some(
        GenerationConfig(
          maxOutputTokens = r.maxOutputTokens,
          temperature = r.temperature,
          topP = r.topP,
          topK = r.topK,
          stopSequences = r.stopSequences,
          thinkingConfig = thinking,
          extra = genExtra
        )
      ).filter(_ != GenerationConfig()),
      extra = optFields - "generationConfig"
    )

  end fromLlmRequest

  // ---------------------------------------------------------------- response

  def toFinishReason(
      reason: Option[String],
      hasToolCalls: Boolean,
      blockReason: Option[String] = None
  ): FinishReason =
    reason match
      case Some(GeminiFinishReason.Stop) if hasToolCalls =>
        FinishReason(FinishReason.Kind.ToolCalls, reason)
      case Some(GeminiFinishReason.Stop) =>
        FinishReason(FinishReason.Kind.Stop, reason)
      case Some(GeminiFinishReason.MaxTokens) =>
        FinishReason(FinishReason.Kind.Length, reason)
      case Some(r) if GeminiFinishReason.filtered.contains(r) =>
        FinishReason(FinishReason.Kind.ContentFilter, reason)
      case Some(GeminiFinishReason.MalformedFunctionCall) =>
        FinishReason(FinishReason.Kind.Error, reason)
      case Some(other) =>
        FinishReason(FinishReason.Kind.Other, Some(other))
      case None if blockReason.isDefined =>
        FinishReason(FinishReason.Kind.ContentFilter, blockReason)
      case None if hasToolCalls =>
        FinishReason(FinishReason.Kind.ToolCalls)
      case None =>
        FinishReason(FinishReason.Kind.Interrupted)

  def fromFinishReason(f: FinishReason): String =
    f.kind match
      case FinishReason.Kind.Length =>
        GeminiFinishReason.MaxTokens
      case FinishReason.Kind.ContentFilter =>
        GeminiFinishReason.Safety
      case FinishReason.Kind.Error =>
        GeminiFinishReason.Other
      case _ =>
        GeminiFinishReason.Stop

  /** Gemini output excludes thoughts; neutral output includes reasoning */
  def toLlmUsage(u: UsageMetadata): LlmUsage =
    val thoughts = u.thoughtsTokenCount.getOrElse(0L)
    LlmUsage(
      inputTokens = u.promptTokenCount + u.toolUsePromptTokenCount.getOrElse(0L),
      outputTokens = u.candidatesTokenCount + thoughts,
      cacheReadTokens = u.cachedContentTokenCount.getOrElse(0L),
      reasoningTokens = thoughts
    )

  def fromLlmUsage(u: LlmUsage): UsageMetadata = UsageMetadata(
    promptTokenCount = u.inputTokens,
    candidatesTokenCount = u.outputTokens - u.reasoningTokens,
    totalTokenCount = u.totalTokens,
    cachedContentTokenCount = Some(u.cacheReadTokens).filter(_ > 0),
    thoughtsTokenCount = Some(u.reasoningTokens).filter(_ > 0)
  )

  def toLlmResponse(r: GenerateContentResponse, model: String = ""): LlmResponse =
    val c       = r.candidates.headOption
    val content = toLlmRequest(
      GenerateContentRequest(c.flatMap(_.content).toSeq.map(_.copy(role = Some("model"))))
    ).messages.flatMap(_.parts)
    LlmResponse(
      id = r.responseId.getOrElse(""),
      model = r.modelVersion.getOrElse(model),
      content = content,
      finishReason = toFinishReason(
        c.flatMap(_.finishReason),
        content.exists(_.isInstanceOf[LlmPart.ToolCall]),
        r.blockReason
      ),
      usage = r.usageMetadata.map(toLlmUsage).getOrElse(LlmUsage())
    )

  def fromLlmResponse(r: LlmResponse): GenerateContentResponse =
    val req = fromLlmRequest(LlmRequest(r.model, Seq(LlmMessage(LlmRole.Assistant, r.content))))
    // Keep the signatures as they are; the placeholder is only for request history
    val parts = req
      .contents
      .flatMap(_.parts)
      .map {
        case f: Part.FunctionCall if f.thoughtSignature.contains(SkipThoughtSignature) =>
          f.copy(thoughtSignature = None)
        case other =>
          other
      }
    GenerateContentResponse(
      candidates = Seq(
        Candidate(
          Some(Content(Some("model"), parts)),
          Some(fromFinishReason(r.finishReason)),
          Some(0)
        )
      ),
      usageMetadata = Some(fromLlmUsage(r.usage)),
      modelVersion = Some(r.model).filter(_.nonEmpty),
      responseId = Some(r.id).filter(_.nonEmpty)
    )

  // ---------------------------------------------------------------- streams

  /**
    * Converts `streamGenerateContent` chunks to neutral stream parts. Each chunk holds new parts;
    * function calls arrive whole. Call [[finish]] when the stream ends.
    */
  class StreamDecoder(model: String = ""):
    import LlmStreamPart as P

    private enum Open:
      case NoBlock,
        Text,
        Reasoning

    private var started                     = false
    private var open                        = Open.NoBlock
    private var block                       = 0
    private var sig: Option[String]         = None
    private var reason: Option[String]      = None
    private var blockReason: Option[String] = None
    private var sawCalls                    = false
    private var usage                       = LlmUsage()
    private var finished                    = false
    private var callIndex                   = 0

    private def close(): Seq[LlmStreamPart] =
      val id = s"${block}"
      val md = sigMeta(sig)
      val r  =
        open match
          case Open.Text =>
            Seq(P.TextEnd(id, md))
          case Open.Reasoning =>
            Seq(P.ReasoningEnd(id, providerMetadata = md))
          case Open.NoBlock =>
            Nil
      if open != Open.NoBlock then
        block += 1
        sig = None
      open = Open.NoBlock
      r

    def decode(chunk: GenerateContentResponse): Seq[LlmStreamPart] =
      val out = Seq.newBuilder[LlmStreamPart]
      if !started then
        started = true
        out += P.Start(chunk.responseId.getOrElse(""), chunk.modelVersion.getOrElse(model))
      chunk
        .candidates
        .headOption
        .foreach { c =>
          c.content
            .toSeq
            .flatMap(_.parts)
            .foreach {
              case t @ Part.Text(text, _, s) =>
                val kind =
                  if t.isThought then
                    Open.Reasoning
                  else
                    Open.Text
                if text.isEmpty && s.isDefined && open != Open.NoBlock then
                  // A trailing empty part carries the signature of the block before it
                  sig = s
                else
                  if open != kind then
                    out ++= close()
                    open = kind
                    out += (
                      if kind == Open.Reasoning then
                        P.ReasoningStart(s"${block}")
                      else
                        P.TextStart(s"${block}")
                    )
                  if text.nonEmpty then
                    out += (
                      if kind == Open.Reasoning then
                        P.ReasoningDelta(s"${block}", text)
                      else
                        P.TextDelta(s"${block}", text)
                    )
                  s.foreach(x => sig = Some(x))
              case Part.FunctionCall(name, args, id, s, _, _) =>
                out ++= close()
                sawCalls = true
                val tid = id.getOrElse(LlmConvert.generatedToolId(name, callIndex))
                callIndex += 1
                out += P.ToolInputStart(tid, name, providerMetadata = sigMeta(s))
                out += P.ToolInputDelta(tid, args.toJSON)
                out += P.ToolInputEnd(tid)
              case other =>
                out ++= close()
                out += P.Raw(vendor, JsonCodec[Part].encode(other))
            }
          c.finishReason.foreach(r => reason = Some(r))
        }
      chunk.blockReason.foreach(b => blockReason = Some(b))
      chunk.usageMetadata.foreach(u => usage = toLlmUsage(u))
      out.result()

    end decode

    def finish(): Seq[LlmStreamPart] =
      if finished then
        Nil
      else
        finished = true
        close() :+ P.Finish(toFinishReason(reason, sawCalls, blockReason), usage)

  end StreamDecoder

  /** Converts neutral stream parts to `streamGenerateContent` chunks */
  class StreamEncoder(model: String = ""):
    import LlmStreamPart as P

    private var responseId   = ""
    private var modelVersion = model
    private var toolId       = ""
    private var toolName     = ""
    private var toolMeta     = ProviderMetadata.empty
    private val args         = StringBuilder()
    private var finished     = false

    private def chunk(
        parts: Seq[Part],
        finish: Option[String] = None,
        usage: Option[UsageMetadata] = None
    ): GenerateContentResponse = GenerateContentResponse(
      candidates = Seq(Candidate(Some(Content(Some("model"), parts)), finish, Some(0))),
      usageMetadata = usage,
      modelVersion = Some(modelVersion).filter(_.nonEmpty),
      responseId = Some(responseId).filter(_.nonEmpty)
    )

    def encode(p: LlmStreamPart): Seq[GenerateContentResponse] =
      p match
        case P.Start(id, m, _) =>
          responseId = id
          if m.nonEmpty then
            modelVersion = m
          Nil
        case P.TextDelta(_, d) =>
          Seq(chunk(Seq(Part.Text(d))))
        case P.TextEnd(_, md) =>
          sigOf(md).map(s => chunk(Seq(Part.Text("", None, Some(s))))).toSeq
        case P.ReasoningDelta(_, d) =>
          Seq(chunk(Seq(Part.Text(d, Some(true)))))
        case P.ReasoningEnd(_, _, _, md) =>
          sigOf(md).map(s => chunk(Seq(Part.Text("", Some(true), Some(s))))).toSeq
        case P.ToolInputStart(id, name, false, md) =>
          toolId = id
          toolName = name
          toolMeta = md
          args.clear()
          Nil
        case P.ToolInputDelta(_, d) =>
          args.append(d)
          Nil
        case _: P.ToolInputEnd if toolName.nonEmpty =>
          val input =
            LlmConvert.parseToolInput(args.result()) match
              case o: JSONObject =>
                o
              case other =>
                Json.obj(Json.field("input", other))
          val call = Part.FunctionCall(toolName, input, Some(toolId), sigOf(toolMeta))
          toolName = ""
          Seq(chunk(Seq(call)))
        case P.Raw(`vendor`, raw: JSONObject) =>
          Seq(chunk(Seq(JsonCodec[Part].decode(raw))))
        case P.Finish(reason, u) =>
          finished = true
          Seq(chunk(Nil, Some(fromFinishReason(reason)), Some(fromLlmUsage(u))))
        case _ =>
          Nil

    def finish(): Seq[GenerateContentResponse] =
      if finished then
        Nil
      else
        encode(P.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamEncoder

end GeminiConverter
