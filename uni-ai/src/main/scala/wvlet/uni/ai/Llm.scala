package wvlet.uni.ai

import wvlet.uni.json.JSON.*

/**
  * A vendor-neutral model of LLM requests, responses, and streams, used to convert between vendor
  * APIs. Each vendor package (`anthropic`, `openai`, `gemini`, `bedrock`) converts its own types to
  * and from this model, so any vendor's request can be served by any other vendor.
  *
  * The shape follows the Vercel AI SDK provider spec: typed parts, stream parts that open and close
  * blocks by id, and `providerMetadata` for data only one vendor understands (thinking signatures,
  * cache breakpoints, encrypted reasoning), keyed by vendor name.
  */
type ProviderMetadata = Map[String, JSONObject]

object ProviderMetadata:
  val empty: ProviderMetadata = Map.empty

  val Anthropic: String = "anthropic"
  val OpenAI: String    = "openai"
  val Gemini: String    = "gemini"
  val Bedrock: String   = "bedrock"

  def of(vendor: String, fields: JsonField*): ProviderMetadata =
    val o = Json.obj(fields*)
    if o.isEmpty then
      empty
    else
      Map(vendor -> o)

end ProviderMetadata

extension (m: ProviderMetadata)
  def field(vendor: String, key: String): Option[JSONValue] = m.get(vendor).flatMap(_.get(key))
  def string(vendor: String, key: String): Option[String]   = field(vendor, key).collect {
    case JSONString(s) =>
      s
  }

  def merge(other: ProviderMetadata): ProviderMetadata =
    other.foldLeft(m) { case (acc, (k, v)) =>
      acc.get(k) match
        case Some(prev) =>
          acc + (k -> JSONObject(prev.v.filterNot(x => v.get(x._1).isDefined) ++ v.v))
        case None =>
          acc + (k -> v)
    }

enum LlmRole:
  case User
  case Assistant

  /** A message carrying tool results */
  case Tool

/** Where file bytes come from */
enum FileSource:
  case Base64(data: String)
  case Url(url: String)

  /** An id of a file uploaded to the vendor (Anthropic Files API, OpenAI files, ...) */
  case FileId(id: String)

sealed trait LlmPart:
  def providerMetadata: ProviderMetadata

object LlmPart:
  case class Text(text: String, providerMetadata: ProviderMetadata = ProviderMetadata.empty)
      extends LlmPart

  /** An image, PDF, or other file, identified by its media type */
  case class File(
      mediaType: String,
      source: FileSource,
      filename: Option[String] = None,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmPart:
    def isImage: Boolean = mediaType.startsWith("image/")

  /**
    * Model reasoning. `signature` is the vendor's proof that the text is unmodified (Anthropic and
    * Bedrock); `redactedData` holds encrypted reasoning that has no visible text.
    */
  case class Reasoning(
      text: String,
      signature: Option[String] = None,
      redactedData: Option[String] = None,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmPart

  /**
    * A tool call. `providerExecuted` marks tools that the vendor runs (web search, code execution);
    * their calls and results are only meaningful to the same vendor.
    */
  case class ToolCall(
      id: String,
      name: String,
      input: JSONValue,
      providerExecuted: Boolean = false,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmPart

  /** A tool result; `output` holds Text and File parts */
  case class ToolResult(
      id: String,
      output: Seq[LlmPart],
      name: Option[String] = None,
      isError: Boolean = false,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmPart:
    def text: String =
      output
        .collect { case Text(t, _) =>
          t
        }
        .mkString

  /**
    * A vendor-specific block with no neutral equivalent (e.g. `web_search_tool_result`), forwarded
    * only to the same vendor and dropped elsewhere
    */
  case class Raw(vendor: String, raw: JSONValue) extends LlmPart:
    def providerMetadata: ProviderMetadata = ProviderMetadata.empty

end LlmPart

case class LlmMessage(role: LlmRole, parts: Seq[LlmPart]):
  def text: String =
    parts
      .collect { case LlmPart.Text(t, _) =>
        t
      }
      .mkString

object LlmMessage:
  def user(text: String): LlmMessage      = LlmMessage(LlmRole.User, Seq(LlmPart.Text(text)))
  def assistant(text: String): LlmMessage = LlmMessage(LlmRole.Assistant, Seq(LlmPart.Text(text)))

/**
  * A tool. `Function` is a client tool with a JSON Schema; `Provider` is a vendor's built-in tool,
  * forwarded only to that vendor.
  */
sealed trait LlmTool

object LlmTool:
  case class Function(
      name: String,
      inputSchema: JSONValue,
      description: Option[String] = None,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmTool

  case class Provider(vendor: String, raw: JSONValue) extends LlmTool

enum LlmToolChoice:
  case Auto
  case NoTool
  case Required
  case Tool(name: String)

/**
  * Reasoning settings. Vendors take either a token budget (Anthropic, Gemini 2.5, Bedrock) or an
  * effort level (OpenAI, Gemini 3); converters derive whichever the target needs.
  */
case class ReasoningOptions(
    effort: Option[String] = None,
    budgetTokens: Option[Int] = None,
    includeThoughts: Boolean = true
):
  def effortLevel: String = effort.getOrElse(ReasoningOptions.effortOf(budgetTokens.getOrElse(0)))
  def budget: Int         = budgetTokens.getOrElse(ReasoningOptions.budgetOf(effortLevel))

object ReasoningOptions:
  /** Token budget to effort, with the buckets claude-code-router uses */
  def effortOf(budget: Int): String =
    if budget <= 0 then
      "none"
    else if budget <= 1024 then
      "low"
    else if budget <= 8192 then
      "medium"
    else
      "high"

  def budgetOf(effort: String): Int =
    effort match
      case "none" =>
        0
      case "minimal" =>
        1024
      case "low" =>
        2048
      case "medium" =>
        8192
      case "high" =>
        24576
      case _ =>
        32768

end ReasoningOptions

/**
  * A vendor-neutral request.
  *
  * @param providerOptions
  *   request fields for one vendor, keyed by vendor name; a converter merges the target vendor's
  *   entry into the request body and ignores the others
  */
case class LlmRequest(
    model: String,
    messages: Seq[LlmMessage],
    system: Seq[LlmPart.Text] = Nil,
    tools: Seq[LlmTool] = Nil,
    toolChoice: Option[LlmToolChoice] = None,
    parallelToolCalls: Option[Boolean] = None,
    maxOutputTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    topK: Option[Int] = None,
    stopSequences: Seq[String] = Nil,
    reasoning: Option[ReasoningOptions] = None,
    stream: Boolean = false,
    providerOptions: ProviderMetadata = ProviderMetadata.empty
):
  def systemText: String = system.map(_.text).mkString("\n\n")

  def withModel(m: String): LlmRequest               = copy(model = m)
  def withMessages(m: Seq[LlmMessage]): LlmRequest   = copy(messages = m)
  def withSystem(text: String): LlmRequest           = copy(system = Seq(LlmPart.Text(text)))
  def withTools(t: Seq[LlmTool]): LlmRequest         = copy(tools = t)
  def withToolChoice(c: LlmToolChoice): LlmRequest   = copy(toolChoice = Some(c))
  def noToolChoice(): LlmRequest                     = copy(toolChoice = None)
  def withMaxOutputTokens(n: Int): LlmRequest        = copy(maxOutputTokens = Some(n))
  def noMaxOutputTokens(): LlmRequest                = copy(maxOutputTokens = None)
  def withTemperature(t: Double): LlmRequest         = copy(temperature = Some(t))
  def noTemperature(): LlmRequest                    = copy(temperature = None)
  def withReasoning(r: ReasoningOptions): LlmRequest = copy(reasoning = Some(r))
  def noReasoning(): LlmRequest                      = copy(reasoning = None)
  def withStream(enabled: Boolean): LlmRequest       = copy(stream = enabled)

end LlmRequest

/**
  * Why generation ended: a unified kind, plus the vendor's own value when there is one.
  */
case class FinishReason(kind: FinishReason.Kind, raw: Option[String] = None)

object FinishReason:
  enum Kind:
    case Stop
    case Length
    case ToolCalls
    case ContentFilter
    case Error

    /** The stream ended without a final event */
    case Interrupted
    case Other

  val stop: FinishReason      = FinishReason(Kind.Stop)
  val length: FinishReason    = FinishReason(Kind.Length)
  val toolCalls: FinishReason = FinishReason(Kind.ToolCalls)

/**
  * Token usage, in one consistent convention: `inputTokens` includes cache reads and writes,
  * `outputTokens` includes reasoning. Vendors disagree on this, so each converter normalizes.
  */
case class LlmUsage(
    inputTokens: Long = 0,
    outputTokens: Long = 0,
    cacheReadTokens: Long = 0,
    cacheWriteTokens: Long = 0,
    reasoningTokens: Long = 0
):
  /** Input tokens that were neither read from nor written to the cache */
  def noCacheInputTokens: Long = math.max(0L, inputTokens - cacheReadTokens - cacheWriteTokens)
  def totalTokens: Long        = inputTokens + outputTokens

case class LlmResponse(
    id: String,
    model: String,
    content: Seq[LlmPart],
    finishReason: FinishReason,
    usage: LlmUsage = LlmUsage(),
    providerMetadata: ProviderMetadata = ProviderMetadata.empty
):
  def text: String =
    content
      .collect { case LlmPart.Text(t, _) =>
        t
      }
      .mkString

  def toolCalls: Seq[LlmPart.ToolCall] = content.collect { case t: LlmPart.ToolCall =>
    t
  }

/**
  * A part of a vendor-neutral stream. Text, reasoning, and tool-input blocks open with a `*Start`,
  * grow with `*Delta`, and close with `*End`, all keyed by `id` (a tool call's id for tool input).
  * Blocks do not overlap: a block ends before the next one starts.
  */
sealed trait LlmStreamPart

object LlmStreamPart:
  /** The first part, with the response id, model, and the input usage known so far */
  case class Start(id: String, model: String, usage: LlmUsage = LlmUsage()) extends LlmStreamPart

  case class TextStart(id: String, providerMetadata: ProviderMetadata = ProviderMetadata.empty)
      extends LlmStreamPart

  case class TextDelta(id: String, delta: String) extends LlmStreamPart

  case class TextEnd(id: String, providerMetadata: ProviderMetadata = ProviderMetadata.empty)
      extends LlmStreamPart

  case class ReasoningStart(id: String, providerMetadata: ProviderMetadata = ProviderMetadata.empty)
      extends LlmStreamPart

  case class ReasoningDelta(id: String, delta: String) extends LlmStreamPart

  /** Ends a reasoning block with its signature or redacted data, when the vendor gives one */
  case class ReasoningEnd(
      id: String,
      signature: Option[String] = None,
      redactedData: Option[String] = None,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmStreamPart

  case class ToolInputStart(
      id: String,
      name: String,
      providerExecuted: Boolean = false,
      providerMetadata: ProviderMetadata = ProviderMetadata.empty
  ) extends LlmStreamPart

  /** A fragment of the tool input JSON; fragments are not valid JSON on their own */
  case class ToolInputDelta(id: String, delta: String) extends LlmStreamPart

  case class ToolInputEnd(id: String, providerMetadata: ProviderMetadata = ProviderMetadata.empty)
      extends LlmStreamPart

  /** A whole vendor-specific block, forwarded only to the same vendor */
  case class Raw(vendor: String, raw: JSONValue) extends LlmStreamPart

  /** The last part, with the cumulative usage */
  case class Finish(finishReason: FinishReason, usage: LlmUsage = LlmUsage()) extends LlmStreamPart

  /** An error reported in the stream; `errorType` is a vendor error type when known */
  case class Error(message: String, errorType: Option[String] = None) extends LlmStreamPart

end LlmStreamPart

/**
  * Builds an [[LlmResponse]] from a vendor-neutral stream
  */
class LlmStreamAccumulator:
  import LlmStreamPart.*

  private var id           = ""
  private var model        = ""
  private var usage        = LlmUsage()
  private var finish       = FinishReason(FinishReason.Kind.Interrupted)
  private val content      = scala.collection.mutable.ArrayBuffer.empty[LlmPart]
  private val text         = StringBuilder()
  private var meta         = ProviderMetadata.empty
  private var toolName     = ""
  private var toolExecuted = false

  def add(p: LlmStreamPart): Unit =
    p match
      case Start(i, m, u) =>
        id = i
        model = m
        usage = u
      case TextStart(_, md) =>
        text.clear()
        meta = md
      case TextDelta(_, d) =>
        text.append(d)
      case TextEnd(_, md) =>
        content += LlmPart.Text(text.result(), meta.merge(md))
      case ReasoningStart(_, md) =>
        text.clear()
        meta = md
      case ReasoningDelta(_, d) =>
        text.append(d)
      case ReasoningEnd(_, sig, red, md) =>
        content += LlmPart.Reasoning(text.result(), sig, red, meta.merge(md))
      case ToolInputStart(_, name, executed, md) =>
        text.clear()
        toolName = name
        toolExecuted = executed
        meta = md
      case ToolInputDelta(_, d) =>
        text.append(d)
      case ToolInputEnd(i, md) =>
        val input =
          if text.isEmpty then
            JSONObject.empty
          else
            Json.parseOrString(text.result())
        content += LlmPart.ToolCall(i, toolName, input, toolExecuted, meta.merge(md))
      case Raw(vendor, raw) =>
        content += LlmPart.Raw(vendor, raw)
      case Finish(reason, u) =>
        finish = reason
        usage = u
      case Error(msg, tpe) =>
        finish = FinishReason(FinishReason.Kind.Error, tpe.orElse(Some(msg)))

  def result: LlmResponse = LlmResponse(id, model, content.toSeq, finish, usage)

end LlmStreamAccumulator
