package wvlet.uni.ai.openai

import wvlet.uni.ai.*
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Converts OpenAI Chat Completions types to and from the vendor-neutral model. Chat Completions
  * has no place for reasoning signatures or cache breakpoints, so those are dropped; reasoning text
  * maps to `reasoning_content`, as vLLM and other compatible servers use it.
  */
object ChatCompletionsConverter:
  private val vendor = ProviderMetadata.OpenAI

  // ---------------------------------------------------------------- request

  def toLlmRequest(r: ChatCompletionRequest): LlmRequest =
    val system                                        = Seq.newBuilder[LlmPart.Text]
    val messages                                      = mutable.ArrayBuffer.empty[LlmMessage]
    def add(role: LlmRole, parts: Seq[LlmPart]): Unit =
      if parts.nonEmpty then
        messages.lastOption match
          case Some(last) if last.role == role =>
            messages(messages.length - 1) = last.copy(parts = last.parts ++ parts)
          case _ =>
            messages += LlmMessage(role, parts)

    r.messages
      .foreach { m =>
        m.role match
          case "system" | "developer" =>
            system += LlmPart.Text(m.text)
          case "assistant" =>
            add(
              LlmRole.Assistant,
              m.reasoningContent.filter(_.nonEmpty).map(LlmPart.Reasoning(_)).toSeq ++
                contentParts(m.content) ++
                m.toolCalls
                  .map(c => LlmPart.ToolCall(c.id, c.name, LlmConvert.parseToolInput(c.arguments)))
            )
          case "tool" =>
            add(
              LlmRole.Tool,
              Seq(LlmPart.ToolResult(m.toolCallId.getOrElse(""), contentParts(m.content)))
            )
          case _ =>
            add(LlmRole.User, contentParts(m.content))
      }

    val extra = r.extra ++ r.user.map(u => "user" -> JSONString(u))
    LlmRequest(
      model = r.model,
      messages = messages.toSeq,
      system = system.result(),
      tools = r
        .tools
        .map(t =>
          LlmTool.Function(
            t.name,
            t.parameters.getOrElse(Json.obj(Json.field("type", "object"))),
            t.description
          )
        ),
      toolChoice = r.toolChoice.flatMap(ResponsesConverter.toLlmToolChoice),
      parallelToolCalls = r.parallelToolCalls,
      maxOutputTokens = r.maxCompletionTokens.orElse(r.maxTokens),
      temperature = r.temperature,
      topP = r.topP,
      stopSequences = r.stop,
      reasoning = r.reasoningEffort.map(e => ReasoningOptions(effort = Some(e))),
      stream = r.stream.contains(true),
      providerOptions = LlmConvert.providerOptionsOf(vendor, extra)
    )

  end toLlmRequest

  private def contentParts(c: Option[ChatMessage.Content]): Seq[LlmPart] =
    c match
      case Some(ChatMessage.Content.Text(t)) =>
        Seq(LlmPart.Text(t)).filter(_.text.nonEmpty)
      case Some(ChatMessage.Content.Parts(ps)) =>
        ps.map {
          case ChatContentPart.Text(t) =>
            LlmPart.Text(t)
          case ChatContentPart.ImageUrl(url, _) =>
            LlmConvert.fileFromUrl(url, "image/jpeg")
          case ChatContentPart.File(id, data, name) =>
            data
              .flatMap(LlmConvert.parseDataUrl)
              .map((m, b) => LlmPart.File(m, FileSource.Base64(b), name))
              .orElse(id.map(i => LlmPart.File("application/pdf", FileSource.FileId(i), name)))
              .getOrElse(LlmPart.Text(""))
          case ChatContentPart.Refusal(r) =>
            LlmPart.Text(r)
          case ChatContentPart.Unknown(raw) =>
            LlmPart.Raw(vendor, raw)
        }
      case None =>
        Nil

  def fromLlmRequest(r: LlmRequest): ChatCompletionRequest =
    val msgs = Seq.newBuilder[ChatMessage]
    if r.system.nonEmpty then
      msgs += ChatMessage.system(r.systemText)
    r.messages
      .foreach { m =>
        m.role match
          case LlmRole.Assistant =>
            val text =
              m.parts
                .collect { case LlmPart.Text(t, _) =>
                  t
                }
                .mkString
            val calls = m
              .parts
              .collect { case LlmPart.ToolCall(id, name, input, false, _) =>
                ChatToolCall(id, name, LlmConvert.toolInputString(input))
              }
            msgs +=
              ChatMessage(
                "assistant",
                content = Some(ChatMessage.Content.Text(text)).filter(_ =>
                  text.nonEmpty || calls.isEmpty
                ),
                toolCalls = calls
              )
          case LlmRole.Tool =>
            m.parts
              .collect { case t: LlmPart.ToolResult =>
                msgs += ChatMessage.tool(t.id, LlmConvert.toolOutputText(t.output))
              }
            val rest = m.parts.filterNot(_.isInstanceOf[LlmPart.ToolResult])
            if rest.nonEmpty then
              msgs += userMessage(rest)
          case LlmRole.User =>
            // Tool results given in a user turn still become tool messages
            m.parts
              .collect { case t: LlmPart.ToolResult =>
                msgs += ChatMessage.tool(t.id, LlmConvert.toolOutputText(t.output))
              }
            val rest = m.parts.filterNot(_.isInstanceOf[LlmPart.ToolResult])
            if rest.nonEmpty then
              msgs += userMessage(rest)
      }
    val req = ChatCompletionRequest(
      model = r.model,
      messages = msgs.result(),
      tools = r
        .tools
        .collect { case LlmTool.Function(name, schema, desc, _) =>
          ChatTool(name, Some(schema), desc)
        },
      toolChoice = r.toolChoice.map(ResponsesConverter.fromLlmToolChoice),
      parallelToolCalls = r.parallelToolCalls.filter(_ => r.tools.nonEmpty),
      maxCompletionTokens = r.maxOutputTokens,
      temperature = r.temperature,
      topP = r.topP,
      stop = r.stopSequences,
      reasoningEffort = r.reasoning.map(_.effortLevel).filter(_ != "none")
    ).withStream(r.stream)
    LlmConvert.withProviderOptions(req, r.providerOptions, vendor)

  end fromLlmRequest

  private def userMessage(parts: Seq[LlmPart]): ChatMessage =
    val chatParts = parts.flatMap {
      case LlmPart.Text(t, _) =>
        Some(ChatContentPart.Text(t))
      case f: LlmPart.File if f.isImage =>
        LlmConvert.urlOf(f).map(ChatContentPart.ImageUrl(_))
      case f: LlmPart.File =>
        f.source match
          case FileSource.Base64(d) =>
            Some(
              ChatContentPart.File(
                fileData = Some(LlmConvert.dataUrl(f.mediaType, d)),
                filename = f.filename.orElse(Some("document"))
              )
            )
          case FileSource.FileId(id) =>
            Some(ChatContentPart.File(fileId = Some(id)))
          case FileSource.Url(u) =>
            Some(ChatContentPart.Text(u))
      case LlmPart.Raw(`vendor`, raw: JSONObject) =>
        Some(ChatContentPart.Unknown(raw))
      case _ =>
        None
    }
    chatParts match
      case Seq(ChatContentPart.Text(t)) =>
        ChatMessage.user(t)
      case ps =>
        ChatMessage("user", Some(ChatMessage.Content.Parts(ps)))

  // ---------------------------------------------------------------- response

  def toFinishReason(reason: Option[String], hasToolCalls: Boolean): FinishReason =
    reason match
      case Some(ChatFinishReason.Stop) if hasToolCalls =>
        FinishReason(FinishReason.Kind.ToolCalls, reason)
      case Some(ChatFinishReason.Stop) =>
        FinishReason(FinishReason.Kind.Stop, reason)
      case Some(ChatFinishReason.Length) =>
        FinishReason(FinishReason.Kind.Length, reason)
      case Some(ChatFinishReason.ToolCalls) | Some("function_call") =>
        FinishReason(FinishReason.Kind.ToolCalls, reason)
      case Some(ChatFinishReason.ContentFilter) =>
        FinishReason(FinishReason.Kind.ContentFilter, reason)
      case Some(other) =>
        FinishReason(FinishReason.Kind.Other, Some(other))
      case None =>
        FinishReason(FinishReason.Kind.Interrupted)

  def fromFinishReason(f: FinishReason): String =
    f.kind match
      case FinishReason.Kind.Length =>
        ChatFinishReason.Length
      case FinishReason.Kind.ToolCalls =>
        ChatFinishReason.ToolCalls
      case FinishReason.Kind.ContentFilter =>
        ChatFinishReason.ContentFilter
      case _ =>
        ChatFinishReason.Stop

  def toLlmUsage(u: ChatUsage): LlmUsage = LlmUsage(
    inputTokens = u.promptTokens,
    outputTokens = u.completionTokens,
    cacheReadTokens = u.cachedTokens.getOrElse(0L),
    reasoningTokens = u.reasoningTokens.getOrElse(0L)
  )

  def fromLlmUsage(u: LlmUsage): ChatUsage = ChatUsage(
    promptTokens = u.inputTokens,
    completionTokens = u.outputTokens,
    totalTokens = u.totalTokens,
    cachedTokens = Some(u.cacheReadTokens),
    reasoningTokens = Some(u.reasoningTokens)
  )

  def toLlmResponse(c: ChatCompletion): LlmResponse =
    val choice = c.choices.headOption
    val m      = choice.map(_.message)
    val parts  = m
      .toSeq
      .flatMap { msg =>
        msg.reasoningContent.filter(_.nonEmpty).map(LlmPart.Reasoning(_)).toSeq ++
          contentParts(msg.content) ++ msg.refusal.map(LlmPart.Text(_)) ++
          msg
            .toolCalls
            .map(t => LlmPart.ToolCall(t.id, t.name, LlmConvert.parseToolInput(t.arguments)))
      }
    LlmResponse(
      id = c.id,
      model = c.model,
      content = parts,
      finishReason = toFinishReason(choice.flatMap(_.finishReason), m.exists(_.toolCalls.nonEmpty)),
      usage = c.usage.map(toLlmUsage).getOrElse(LlmUsage())
    )

  def fromLlmResponse(r: LlmResponse, created: Long = 0): ChatCompletion =
    val text      = r.text
    val reasoning =
      r.content
        .collect { case x: LlmPart.Reasoning =>
          x.text
        }
        .mkString
    val calls = r
      .toolCalls
      .filterNot(_.providerExecuted)
      .map(t => ChatToolCall(t.id, t.name, LlmConvert.toolInputString(t.input)))
    ChatCompletion(
      id = r.id,
      model = r.model,
      created = created,
      choices = Seq(
        ChatChoice(
          0,
          ChatMessage(
            "assistant",
            content = Some(ChatMessage.Content.Text(text)).filter(_ =>
              text.nonEmpty || calls.isEmpty
            ),
            toolCalls = calls,
            reasoningContent = Some(reasoning).filter(_.nonEmpty)
          ),
          Some(fromFinishReason(r.finishReason))
        )
      ),
      usage = Some(fromLlmUsage(r.usage))
    )

  end fromLlmResponse

  // ---------------------------------------------------------------- streams

  /**
    * Converts Chat Completions chunks to neutral stream parts. Usage comes in a last chunk after
    * the one with `finish_reason`, so the Finish part is emitted by [[finish]], when the stream
    * ends (`[DONE]`).
    */
  class StreamDecoder:
    import LlmStreamPart as P

    private enum Open:
      case NoBlock,
        Text,
        Reasoning,
        Tool

    private var started                = false
    private var open                   = Open.NoBlock
    private var toolId                 = ""
    private var toolIndex              = -1
    private var block                  = 0
    private var reason: Option[String] = None
    private var sawToolCalls           = false
    private var usage                  = LlmUsage()
    private var finished               = false

    private def close(): Seq[LlmStreamPart] =
      val id = s"${block}"
      val r  =
        open match
          case Open.Text =>
            Seq(P.TextEnd(id))
          case Open.Reasoning =>
            Seq(P.ReasoningEnd(id))
          case Open.Tool =>
            Seq(P.ToolInputEnd(toolId))
          case Open.NoBlock =>
            Nil
      if open != Open.NoBlock then
        block += 1
      open = Open.NoBlock
      r

    def decode(c: ChatCompletionChunk): Seq[LlmStreamPart] =
      val out = Seq.newBuilder[LlmStreamPart]
      if !started then
        started = true
        out += P.Start(c.id, c.model)
      c.choices
        .headOption
        .foreach { ch =>
          val d = ch.delta
          d.reasoningContent
            .filter(_.nonEmpty)
            .foreach { t =>
              if open != Open.Reasoning then
                out ++= close()
                open = Open.Reasoning
                out += P.ReasoningStart(s"${block}")
              out += P.ReasoningDelta(s"${block}", t)
            }
          d.content
            .orElse(d.refusal)
            .filter(_.nonEmpty)
            .foreach { t =>
              if open != Open.Text then
                out ++= close()
                open = Open.Text
                out += P.TextStart(s"${block}")
              out += P.TextDelta(s"${block}", t)
            }
          d.toolCalls
            .foreach { tc =>
              sawToolCalls = true
              if open != Open.Tool || tc.index != toolIndex then
                out ++= close()
                open = Open.Tool
                toolIndex = tc.index
                toolId = tc
                  .id
                  .getOrElse(LlmConvert.generatedToolId(tc.name.getOrElse(""), tc.index))
                out += P.ToolInputStart(toolId, tc.name.getOrElse(""))
              tc.arguments.filter(_.nonEmpty).foreach(a => out += P.ToolInputDelta(toolId, a))
            }
          if ch.finishReason.isDefined then
            out ++= close()
            reason = ch.finishReason
        }
      c.usage.foreach(u => usage = toLlmUsage(u))
      out.result()

    end decode

    def finish(): Seq[LlmStreamPart] =
      if finished then
        Nil
      else
        finished = true
        close() :+ P.Finish(toFinishReason(reason, sawToolCalls), usage)

  end StreamDecoder

  /**
    * Converts neutral stream parts to Chat Completions chunks. After the Finish part it emits the
    * finish chunk and a usage chunk; the caller ends the SSE stream with
    * [[ChatCompletionChunk.done]].
    */
  class StreamEncoder(chunkId: String = "chatcmpl-uni", model: String = "", created: Long = 0):
    import LlmStreamPart as P

    private var id        = chunkId
    private var modelName = model
    private var sentRole  = false
    private var toolIndex = -1
    private var finished  = false

    private def chunk(d: ChatDelta, finish: Option[String] = None): ChatCompletionChunk =
      val delta =
        if sentRole then
          d
        else
          sentRole = true
          d.copy(role = Some("assistant"))
      ChatCompletionChunk(id, modelName, Seq(ChatChunkChoice(0, delta, finish)), created)

    def encode(p: LlmStreamPart): Seq[ChatCompletionChunk] =
      p match
        case P.Start(i, m, _) =>
          if i.nonEmpty then
            id = i
          if m.nonEmpty then
            modelName = m
          Nil
        case P.TextDelta(_, d) =>
          Seq(chunk(ChatDelta(content = Some(d))))
        case P.ReasoningDelta(_, d) =>
          Seq(chunk(ChatDelta(reasoningContent = Some(d))))
        case P.ToolInputStart(tid, name, false, _) =>
          toolIndex += 1
          Seq(
            chunk(
              ChatDelta(toolCalls =
                Seq(ChatToolCallDelta(toolIndex, Some(tid), Some(name), Some("")))
              )
            )
          )
        case P.ToolInputDelta(_, d) if toolIndex >= 0 =>
          Seq(chunk(ChatDelta(toolCalls = Seq(ChatToolCallDelta(toolIndex, arguments = Some(d))))))
        case P.Finish(reason, u) =>
          finished = true
          Seq(
            chunk(ChatDelta(), Some(fromFinishReason(reason))),
            ChatCompletionChunk(id, modelName, Nil, created, Some(fromLlmUsage(u)))
          )
        case P.Error(msg, _) =>
          Nil
        case _ =>
          Nil

    def finish(): Seq[ChatCompletionChunk] =
      if finished then
        Nil
      else
        encode(P.Finish(FinishReason(FinishReason.Kind.Interrupted)))

  end StreamEncoder

end ChatCompletionsConverter
