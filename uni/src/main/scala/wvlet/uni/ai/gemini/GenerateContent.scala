package wvlet.uni.ai.gemini

import wvlet.uni.ai.Json
import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.JsonObj
import wvlet.uni.http.ServerSentEvent
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

import scala.collection.mutable

/**
  * Models of the Gemini `generateContent` and `streamGenerateContent` APIs, served by Google AI
  * Studio (`generativelanguage.googleapis.com`) and Vertex AI (`aiplatform.googleapis.com`). See
  * https://ai.google.dev/api/generate-content
  *
  * Parts are key-tagged unions (`{"text": ...}`, `{"functionCall": ...}`). A `thoughtSignature` may
  * sit on any part and must be sent back unchanged with the part it came on.
  */
sealed trait Part:
  def thoughtSignature: Option[String]

object Part:
  /** Text; `thought = Some(true)` marks a thought summary */
  case class Text(
      text: String,
      thought: Option[Boolean] = None,
      thoughtSignature: Option[String] = None
  ) extends Part:
    def isThought: Boolean = thought.contains(true)

  /** Base64 bytes */
  case class InlineData(mimeType: String, data: String, thoughtSignature: Option[String] = None)
      extends Part

  /** A file by URI (`gs://...`, Files API URI, or https URL) */
  case class FileData(
      mimeType: Option[String],
      fileUri: String,
      thoughtSignature: Option[String] = None
  ) extends Part

  /**
    * A function call. `id` is returned by newer models (Gemini 3 and later); match the response by
    * name when it is absent. `partialArgs` and `willContinue` appear when
    * `streamFunctionCallArguments` is on.
    */
  case class FunctionCall(
      name: String,
      args: JSONValue = JSONObject.empty,
      id: Option[String] = None,
      thoughtSignature: Option[String] = None,
      partialArgs: Seq[JSONValue] = Nil,
      willContinue: Option[Boolean] = None
  ) extends Part

  case class FunctionResponse(
      name: String,
      response: JSONValue,
      id: Option[String] = None,
      thoughtSignature: Option[String] = None
  ) extends Part

  /** `executableCode`, `codeExecutionResult`, `videoMetadata`, and other part kinds */
  case class Unknown(raw: JSONObject) extends Part:
    def thoughtSignature: Option[String] = JsonObj(raw).strOpt("thoughtSignature")

  def text(s: String): Part = Text(s)

  private def sig(s: Option[String]) = Json.opt("thoughtSignature", s)

  given codec: JsonCodec[Part] = JsonCodec.of(
    {
      case Text(t, thought, s) =>
        Json.obj(Json.field("text", t), Json.opt("thought", thought), sig(s))
      case InlineData(m, d, s) =>
        Json.obj(
          "inlineData" -> Some(Json.obj(Json.field("mimeType", m), Json.field("data", d))),
          sig(s)
        )
      case FileData(m, uri, s) =>
        Json.obj(
          "fileData" -> Some(Json.obj(Json.opt("mimeType", m), Json.field("fileUri", uri))),
          sig(s)
        )
      case FunctionCall(name, args, id, s, partial, cont) =>
        Json.obj(
          "functionCall" ->
            Some(
              Json.obj(
                Json.opt("id", id),
                Json.field("name", name),
                Json.field("args", args),
                Json.nonEmpty("partialArgs", partial),
                Json.opt("willContinue", cont)
              )
            ),
          sig(s)
        )
      case FunctionResponse(name, resp, id, s) =>
        Json.obj(
          "functionResponse" ->
            Some(
              Json.obj(Json.opt("id", id), Json.field("name", name), Json.field("response", resp))
            ),
          sig(s)
        )
      case Unknown(raw) =>
        raw
    },
    v =>
      val o = JsonObj(v)
      val s = o.strOpt("thoughtSignature")
      if o.has("text") then
        Text(o.str("text"), o.opt[Boolean]("thought"), s)
      else if o.has("inlineData") then
        val d = o.obj("inlineData")
        InlineData(d.strOrEmpty("mimeType"), d.strOrEmpty("data"), s)
      else if o.has("fileData") then
        val d = o.obj("fileData")
        FileData(d.strOpt("mimeType"), d.str("fileUri"), s)
      else if o.has("functionCall") then
        val f = o.obj("functionCall")
        FunctionCall(
          f.strOrEmpty("name"),
          f.getOrElse[JSONValue]("args", JSONObject.empty),
          f.strOpt("id"),
          s,
          f.seq[JSONValue]("partialArgs"),
          f.opt[Boolean]("willContinue")
        )
      else if o.has("functionResponse") then
        val f = o.obj("functionResponse")
        FunctionResponse(
          f.strOrEmpty("name"),
          f.getOrElse[JSONValue]("response", JSONObject.empty),
          f.strOpt("id"),
          s
        )
      else
        Unknown(o.value)
  )

end Part

/** A turn: role `user` or `model` (function responses go in `user` turns) */
case class Content(role: Option[String], parts: Seq[Part]):
  def text: String =
    parts
      .collect {
        case t: Part.Text if !t.isThought =>
          t.text
      }
      .mkString

object Content:
  def user(text: String): Content  = Content(Some("user"), Seq(Part.Text(text)))
  def model(text: String): Content = Content(Some("model"), Seq(Part.Text(text)))

  given JsonCodec[Content] = JsonCodec.obj(
    c => Seq(Json.opt("role", c.role), Json.field("parts", c.parts)),
    o => Content(o.strOpt("role"), o.seq[Part]("parts"))
  )

/**
  * A function declaration. `parametersJsonSchema` takes full JSON Schema; `parameters` takes the
  * OpenAPI subset.
  */
case class FunctionDeclaration(
    name: String,
    description: Option[String] = None,
    parametersJsonSchema: Option[JSONValue] = None,
    parameters: Option[JSONValue] = None
)

object FunctionDeclaration:
  given JsonCodec[FunctionDeclaration] = JsonCodec.obj(
    f =>
      Seq(
        Json.field("name", f.name),
        Json.opt("description", f.description),
        Json.opt("parameters", f.parameters),
        Json.opt("parametersJsonSchema", f.parametersJsonSchema)
      ),
    o =>
      FunctionDeclaration(
        o.str("name"),
        o.strOpt("description"),
        o.opt[JSONValue]("parametersJsonSchema"),
        o.opt[JSONValue]("parameters")
      )
  )

/**
  * A tool entry: function declarations, or a built-in tool (`googleSearch`, `codeExecution`,
  * `urlContext`, ...) kept in `extra`
  */
case class Tool(
    functionDeclarations: Seq[FunctionDeclaration] = Nil,
    extra: Map[String, JSONValue] = Map.empty
)

object Tool:
  given JsonCodec[Tool] = JsonCodec.obj(
    t => Seq(Json.nonEmpty("functionDeclarations", t.functionDeclarations)) ++ Json.extra(t.extra),
    o =>
      Tool(o.seq[FunctionDeclaration]("functionDeclarations"), o.extra(Set("functionDeclarations")))
  )

/** `toolConfig.functionCallingConfig`; mode `AUTO`, `ANY`, `NONE`, or `VALIDATED` */
case class FunctionCallingConfig(
    mode: Option[String] = None,
    allowedFunctionNames: Seq[String] = Nil,
    streamFunctionCallArguments: Option[Boolean] = None
)

object FunctionCallingConfig:
  given JsonCodec[FunctionCallingConfig] = JsonCodec.obj(
    c =>
      Seq(
        Json.opt("mode", c.mode),
        Json.nonEmpty("allowedFunctionNames", c.allowedFunctionNames),
        Json.opt("streamFunctionCallArguments", c.streamFunctionCallArguments)
      ),
    o =>
      FunctionCallingConfig(
        o.strOpt("mode"),
        o.seq[String]("allowedFunctionNames"),
        o.opt[Boolean]("streamFunctionCallArguments")
      )
  )

/** `thinkingConfig`: a token budget (Gemini 2.5) or a level (Gemini 3: `low`, `high`, ...) */
case class ThinkingConfig(
    thinkingBudget: Option[Int] = None,
    thinkingLevel: Option[String] = None,
    includeThoughts: Option[Boolean] = None
)

object ThinkingConfig:
  given JsonCodec[ThinkingConfig] = JsonCodec.obj(
    c =>
      Seq(
        Json.opt("thinkingBudget", c.thinkingBudget),
        Json.opt("thinkingLevel", c.thinkingLevel),
        Json.opt("includeThoughts", c.includeThoughts)
      ),
    o =>
      ThinkingConfig(
        o.opt[Int]("thinkingBudget"),
        o.strOpt("thinkingLevel"),
        o.opt[Boolean]("includeThoughts")
      )
  )

/**
  * `generationConfig`. `extra` keeps `responseMimeType`, `responseJsonSchema`, `seed`,
  * `responseModalities`, and other fields.
  */
case class GenerationConfig(
    maxOutputTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    topK: Option[Int] = None,
    stopSequences: Seq[String] = Nil,
    candidateCount: Option[Int] = None,
    thinkingConfig: Option[ThinkingConfig] = None,
    extra: Map[String, JSONValue] = Map.empty
)

object GenerationConfig:
  private val known = Set(
    "maxOutputTokens",
    "temperature",
    "topP",
    "topK",
    "stopSequences",
    "candidateCount",
    "thinkingConfig"
  )

  given JsonCodec[GenerationConfig] = JsonCodec.obj(
    g =>
      Seq(
        Json.opt("maxOutputTokens", g.maxOutputTokens),
        Json.opt("temperature", g.temperature),
        Json.opt("topP", g.topP),
        Json.opt("topK", g.topK),
        Json.nonEmpty("stopSequences", g.stopSequences),
        Json.opt("candidateCount", g.candidateCount),
        Json.opt("thinkingConfig", g.thinkingConfig)
      ) ++ Json.extra(g.extra),
    o =>
      GenerationConfig(
        o.opt[Int]("maxOutputTokens"),
        o.opt[Double]("temperature"),
        o.opt[Double]("topP"),
        o.opt[Int]("topK"),
        o.seq[String]("stopSequences"),
        o.opt[Int]("candidateCount"),
        o.opt[ThinkingConfig]("thinkingConfig"),
        o.extra(known)
      )
  )

end GenerationConfig

/**
  * A `generateContent` request. The model name goes in the URL, not the body.
  *
  * @param extra
  *   `safetySettings`, `cachedContent`, `labels`, and other fields, sent as they are
  */
case class GenerateContentRequest(
    contents: Seq[Content],
    systemInstruction: Option[Content] = None,
    tools: Seq[Tool] = Nil,
    functionCallingConfig: Option[FunctionCallingConfig] = None,
    generationConfig: Option[GenerationConfig] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  def withContents(c: Seq[Content]): GenerateContentRequest       = copy(contents = c)
  def withSystemInstruction(text: String): GenerateContentRequest = copy(systemInstruction =
    Some(Content(None, Seq(Part.Text(text))))
  )

  def noSystemInstruction(): GenerateContentRequest   = copy(systemInstruction = None)
  def withTools(t: Seq[Tool]): GenerateContentRequest = copy(tools = t)
  def withGenerationConfig(g: GenerationConfig): GenerateContentRequest = copy(generationConfig =
    Some(g)
  )

  def withFunctionCallingConfig(c: FunctionCallingConfig): GenerateContentRequest = copy(
    functionCallingConfig = Some(c)
  )

  def withExtra(key: String, v: JSONValue): GenerateContentRequest = copy(extra =
    extra + (key -> v)
  )

object GenerateContentRequest:
  private val known = Set(
    "contents",
    "systemInstruction",
    "tools",
    "toolConfig",
    "generationConfig"
  )

  given JsonCodec[GenerateContentRequest] = JsonCodec.obj(
    r =>
      Seq(
        Json.field("contents", r.contents),
        Json.opt("systemInstruction", r.systemInstruction),
        Json.nonEmpty("tools", r.tools),
        "toolConfig" ->
          r.functionCallingConfig.map(c => Json.obj(Json.field("functionCallingConfig", c))),
        Json.opt("generationConfig", r.generationConfig)
      ) ++ Json.extra(r.extra),
    o =>
      GenerateContentRequest(
        contents = o.seq[Content]("contents"),
        systemInstruction = o.opt[Content]("systemInstruction"),
        tools = o.seq[Tool]("tools"),
        functionCallingConfig = o
          .objOpt("toolConfig")
          .flatMap(_.opt[FunctionCallingConfig]("functionCallingConfig")),
        generationConfig = o.opt[GenerationConfig]("generationConfig"),
        extra = o.extra(known)
      )
  )

end GenerateContentRequest

/** Values of `finishReason` */
object GeminiFinishReason:
  val Stop: String                  = "STOP"
  val MaxTokens: String             = "MAX_TOKENS"
  val Safety: String                = "SAFETY"
  val Recitation: String            = "RECITATION"
  val Blocklist: String             = "BLOCKLIST"
  val ProhibitedContent: String     = "PROHIBITED_CONTENT"
  val Spii: String                  = "SPII"
  val ImageSafety: String           = "IMAGE_SAFETY"
  val MalformedFunctionCall: String = "MALFORMED_FUNCTION_CALL"
  val Other: String                 = "OTHER"

  /** Reasons that mean the output was blocked by a content filter */
  val filtered: Set[String] = Set(
    Safety,
    Recitation,
    Blocklist,
    ProhibitedContent,
    Spii,
    ImageSafety
  )

case class Candidate(
    content: Option[Content],
    finishReason: Option[String] = None,
    index: Option[Int] = None,
    extra: Map[String, JSONValue] = Map.empty
)

object Candidate:
  private val known = Set("content", "finishReason", "index")

  given JsonCodec[Candidate] = JsonCodec.obj(
    c =>
      Seq(
        Json.opt("content", c.content),
        Json.opt("finishReason", c.finishReason),
        Json.opt("index", c.index)
      ) ++ Json.extra(c.extra),
    o =>
      Candidate(
        o.opt[Content]("content"),
        o.strOpt("finishReason"),
        o.opt[Int]("index"),
        o.extra(known)
      )
  )

/**
  * `usageMetadata`. `promptTokenCount` includes `cachedContentTokenCount`; `candidatesTokenCount`
  * excludes `thoughtsTokenCount`.
  */
case class UsageMetadata(
    promptTokenCount: Long = 0,
    candidatesTokenCount: Long = 0,
    totalTokenCount: Long = 0,
    cachedContentTokenCount: Option[Long] = None,
    thoughtsTokenCount: Option[Long] = None,
    toolUsePromptTokenCount: Option[Long] = None,
    extra: Map[String, JSONValue] = Map.empty
)

object UsageMetadata:
  private val known = Set(
    "promptTokenCount",
    "candidatesTokenCount",
    "totalTokenCount",
    "cachedContentTokenCount",
    "thoughtsTokenCount",
    "toolUsePromptTokenCount"
  )

  given JsonCodec[UsageMetadata] = JsonCodec.obj(
    u =>
      Seq(
        Json.field("promptTokenCount", u.promptTokenCount),
        Json.field("candidatesTokenCount", u.candidatesTokenCount),
        Json.field("totalTokenCount", u.totalTokenCount),
        Json.opt("cachedContentTokenCount", u.cachedContentTokenCount),
        Json.opt("thoughtsTokenCount", u.thoughtsTokenCount),
        Json.opt("toolUsePromptTokenCount", u.toolUsePromptTokenCount)
      ) ++ Json.extra(u.extra),
    o =>
      UsageMetadata(
        o.getOrElse[Long]("promptTokenCount", 0L),
        o.getOrElse[Long]("candidatesTokenCount", 0L),
        o.getOrElse[Long]("totalTokenCount", 0L),
        o.opt[Long]("cachedContentTokenCount"),
        o.opt[Long]("thoughtsTokenCount"),
        o.opt[Long]("toolUsePromptTokenCount"),
        o.extra(known)
      )
  )

end UsageMetadata

/**
  * A `generateContent` response, and also each chunk of `streamGenerateContent?alt=sse`, where each
  * chunk holds only the new parts.
  *
  * @param extra
  *   `promptFeedback`, `createTime`, and other fields
  */
case class GenerateContentResponse(
    candidates: Seq[Candidate] = Nil,
    usageMetadata: Option[UsageMetadata] = None,
    modelVersion: Option[String] = None,
    responseId: Option[String] = None,
    extra: Map[String, JSONValue] = Map.empty
):
  /** The text of the first candidate, without thoughts */
  def text: String = candidates.headOption.flatMap(_.content).map(_.text).getOrElse("")

  def functionCalls: Seq[Part.FunctionCall] = candidates
    .headOption
    .flatMap(_.content)
    .toSeq
    .flatMap(_.parts)
    .collect { case f: Part.FunctionCall =>
      f
    }

  /** `promptFeedback.blockReason`, set when the prompt itself was blocked */
  def blockReason: Option[String] = extra
    .get("promptFeedback")
    .flatMap(JsonObj.unapply)
    .flatMap(_.strOpt("blockReason"))

  def toServerSentEvent: ServerSentEvent = ServerSentEvent(data =
    JsonCodec[GenerateContentResponse].toJson(this)
  )

object GenerateContentResponse:
  private val known = Set("candidates", "usageMetadata", "modelVersion", "responseId")

  given JsonCodec[GenerateContentResponse] = JsonCodec.obj(
    r =>
      Seq(
        Json.nonEmpty("candidates", r.candidates),
        Json.opt("usageMetadata", r.usageMetadata),
        Json.opt("modelVersion", r.modelVersion),
        Json.opt("responseId", r.responseId)
      ) ++ Json.extra(r.extra),
    o =>
      GenerateContentResponse(
        o.seq[Candidate]("candidates"),
        o.opt[UsageMetadata]("usageMetadata"),
        o.strOpt("modelVersion"),
        o.strOpt("responseId"),
        o.extra(known)
      )
  )

  def fromServerSentEvent(e: ServerSentEvent): GenerateContentResponse =
    JsonCodec[GenerateContentResponse].decode(JSON.parseAny(e.data))

end GenerateContentResponse

/**
  * Merges the chunks of `streamGenerateContent` into one response: parts are appended (adjacent
  * text of the same kind is joined), the last finish reason and usage win.
  */
class GenerateContentAccumulator:
  private val parts                        = mutable.ArrayBuffer.empty[Part]
  private var role: Option[String]         = None
  private var finishReason: Option[String] = None
  private var candidateExtra               = Map.empty[String, JSONValue]
  private var usage: Option[UsageMetadata] = None
  private var modelVersion: Option[String] = None
  private var responseId: Option[String]   = None
  private var extra                        = Map.empty[String, JSONValue]

  def add(chunk: GenerateContentResponse): Unit =
    chunk
      .candidates
      .headOption
      .foreach { c =>
        c.content
          .foreach { content =>
            role = content.role.orElse(role)
            content.parts.foreach(append)
          }
        finishReason = c.finishReason.orElse(finishReason)
        candidateExtra = candidateExtra ++ c.extra
      }
    usage = chunk.usageMetadata.orElse(usage)
    modelVersion = chunk.modelVersion.orElse(modelVersion)
    responseId = chunk.responseId.orElse(responseId)
    extra = extra ++ chunk.extra

  private def append(p: Part): Unit =
    (parts.lastOption, p) match
      case (Some(last: Part.Text), next: Part.Text)
          if last.isThought == next.isThought && last.thoughtSignature.isEmpty =>
        parts(parts.length - 1) = last.copy(
          text = last.text + next.text,
          thoughtSignature = next.thoughtSignature
        )
      case _ =>
        parts += p

  def result: GenerateContentResponse = GenerateContentResponse(
    candidates = Seq(
      Candidate(
        Some(Content(role.orElse(Some("model")), parts.toSeq)),
        finishReason,
        Some(0),
        candidateExtra
      )
    ),
    usageMetadata = usage,
    modelVersion = modelVersion,
    responseId = responseId,
    extra = extra
  )

end GenerateContentAccumulator
