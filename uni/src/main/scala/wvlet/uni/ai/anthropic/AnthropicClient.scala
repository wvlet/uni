package wvlet.uni.ai.anthropic

import wvlet.uni.ai.*
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.GoogleCredentialsProvider

/** Where the Messages API is served */
enum AnthropicHost:
  /** The Anthropic API (`/v1/messages`) */
  case Direct(apiKey: String, baseUrl: String = "https://api.anthropic.com")

  /** Claude on Vertex AI (`:rawPredict`); `region` may be `global` */
  case Vertex(
      projectId: String,
      region: String,
      credentials: GoogleCredentialsProvider = GoogleCredentialsProvider.applicationDefault(
        Seq(GoogleCredentialsProvider.CloudPlatform)
      )
  )

  /**
    * Claude on Amazon Bedrock through its Anthropic Messages API (`/anthropic/v1/messages` on
    * bedrock-runtime). `model` is a Bedrock model or inference profile id such as
    * `us.anthropic.claude-sonnet-5`; `endpoint` overrides the host (e.g. a VPC endpoint).
    */
  case Bedrock(
      region: String,
      auth: BedrockAuth = BedrockAuth.default,
      endpoint: Option[String] = None
  )

/**
  * @param betas
  *   `anthropic-beta` feature flags (e.g. `context-1m-2025-08-07`)
  */
case class AnthropicConfig(
    host: AnthropicHost,
    version: String = "2023-06-01",
    betas: Seq[String] = Nil,
    headers: Seq[(String, String)] = Nil
):
  def withHost(h: AnthropicHost): AnthropicConfig            = copy(host = h)
  def withVersion(v: String): AnthropicConfig                = copy(version = v)
  def withBetas(b: Seq[String]): AnthropicConfig             = copy(betas = b)
  def withHeaders(h: Seq[(String, String)]): AnthropicConfig = copy(headers = h)

object AnthropicConfig:
  /** The Anthropic API with the key in `ANTHROPIC_API_KEY` */
  def fromEnv: AnthropicConfig = AnthropicConfig(
    AnthropicHost.Direct(
      sys
        .env
        .getOrElse("ANTHROPIC_API_KEY", throw IllegalStateException("ANTHROPIC_API_KEY is not set"))
    )
  )

/**
  * A client of the Anthropic Messages API on the Anthropic API, Vertex AI, or Amazon Bedrock.
  *
  * {{{
  * val client = AnthropicClient(AnthropicConfig.fromEnv)
  * client.messages(MessagesRequest("claude-sonnet-4-5", 1024, Seq(Message.user("Hello"))))
  * client.stream(request).map { case StreamEvent.ContentBlockDelta(_, Delta.TextDelta(t)) => ... }
  * }}}
  */
class AnthropicClient(val config: AnthropicConfig, http: HttpAsyncClient = LlmHttp.newClient)
    extends LlmClient
    with AutoCloseable:
  import AnthropicHost.*

  private def body(r: MessagesRequest, stream: Boolean): JSONValue =
    val json = JsonCodec[MessagesRequest].encode(
      r.copy(stream =
        if stream then
          Some(true)
        else
          None
      )
    )
    config.host match
      case _: Direct | _: Bedrock =>
        json
      case _: Vertex =>
        // The model goes in the URL; the version in the body
        withoutKeys(json, Set("model")) :+ ("anthropic_version" -> JSONString("vertex-2023-10-16"))

  extension (o: JSONObject)
    private def :+(kv: (String, JSONValue)): JSONObject = JSONObject(o.v :+ kv)

  private def withoutKeys(v: JSONValue, keys: Set[String]): JSONObject =
    v match
      case JSONObject(fs) =>
        JSONObject(fs.filterNot(f => keys.contains(f._1)))
      case _ =>
        JSONObject.empty

  private def betaHeaders: Seq[(String, String)] =
    Option.when(config.betas.nonEmpty)("anthropic-beta" -> config.betas.mkString(",")).toSeq

  private def vertexUrl(v: Vertex, model: String, method: String): String =
    val host =
      if v.region == "global" then
        "aiplatform.googleapis.com"
      else
        s"${v.region}-aiplatform.googleapis.com"
    s"https://${host}/v1/projects/${v.projectId}/locations/${v
        .region}/publishers/anthropic/models/${model}:${method}"

  private def request(r: MessagesRequest, stream: Boolean, path: String = "/v1/messages") =
    val b = body(r, stream)
    config.host match
      case Direct(key, baseUrl) =>
        LlmHttp.jsonRequest(
          s"${baseUrl.stripSuffix("/")}${path}",
          b,
          Seq("x-api-key" -> key, "anthropic-version" -> config.version) ++ betaHeaders ++
            config.headers
        )
      case v: Vertex =>
        val method =
          if stream then
            "streamRawPredict"
          else
            "rawPredict"
        LlmHttp.jsonRequest(
          vertexUrl(v, r.model, method),
          b,
          Seq("Authorization" -> s"Bearer ${LlmHttp.googleToken(v.credentials)}") ++ betaHeaders ++
            config.headers
        )
      case bd: Bedrock =>
        LlmHttp.bedrockRequest(
          bd.region,
          bd.endpoint,
          s"/anthropic${path}",
          b,
          bd.auth,
          Seq("anthropic-version" -> config.version) ++ betaHeaders ++ config.headers,
          key => "x-api-key" -> key
        )
    end match

  end request

  private def provider: String =
    config.host match
      case _: Direct =>
        "anthropic"
      case _: Vertex =>
        "gcp.vertex_ai"
      case _: Bedrock =>
        "aws.bedrock"

  private def operation(r: MessagesRequest, stream: Boolean) = LlmOperation(
    "chat",
    provider,
    r.model,
    stream,
    Some(r.maxTokens.toLong),
    r.temperature,
    r.topP
  )

  /** Send a request and wait for the whole message */
  def messages(r: MessagesRequest): Rx[MessagesResponse] =
    LlmTelemetry.traced(http, operation(r, stream = false), request(r, stream = false)) { req =>
      LlmHttp.postJson(http, req).map(JsonCodec[MessagesResponse].decode)
    } { (trace, m) =>
      trace.response(m.id, m.model)
      trace.setUsage(AnthropicConverter.toLlmUsage(m.usage))
      trace.setFinishReasons(m.stopReason.toSeq)
    }

  /** Send a request and receive the message as stream events */
  def stream(r: MessagesRequest): Rx[StreamEvent] =
    LlmTelemetry.traced(http, operation(r, stream = true), request(r, stream = true)) { req =>
      LlmHttp.postSSE(http, req).filter(e => e.data.nonEmpty).map(StreamEvent.fromServerSentEvent)
    } {
      // message_start carries the input usage, message_delta the cumulative output usage
      var usage = Usage()
      (trace, event) =>
        event match
          case StreamEvent.MessageStart(m) =>
            trace.response(m.id, m.model)
            usage = m.usage
            trace.setUsage(AnthropicConverter.toLlmUsage(usage))
          case StreamEvent.MessageDelta(reason, _, u, _) =>
            trace.setFinishReasons(reason.toSeq)
            usage = usage.copy(
              inputTokens = Some(u.inputTokens).filter(_ > 0).getOrElse(usage.inputTokens),
              outputTokens = u.outputTokens,
              cacheCreationInputTokens = u
                .cacheCreationInputTokens
                .orElse(usage.cacheCreationInputTokens),
              cacheReadInputTokens = u.cacheReadInputTokens.orElse(usage.cacheReadInputTokens)
            )
            trace.setUsage(AnthropicConverter.toLlmUsage(usage))
          // An error event ends the stream without a failed HTTP response
          case StreamEvent.Error(err) =>
            trace.setError(err.`type`)
          case _ =>
            ()
    }

  /**
    * Count the input tokens of a request (Anthropic API only; Bedrock documents `count_tokens` only
    * on its deprecated mantle endpoint)
    */
  def countTokens(r: MessagesRequest): Rx[CountTokensResponse] =
    config.host match
      case _: Bedrock =>
        Rx.exception(
          UnsupportedOperationException("countTokens is not available for Claude on Bedrock")
        )
      case _ =>
        countTokensOnAnthropic(r)

  private def countTokensOnAnthropic(r: MessagesRequest): Rx[CountTokensResponse] =
    val req = request(r.copy(maxTokens = 0), stream = false, "/v1/messages/count_tokens")
    // count_tokens takes no max_tokens
    val json = withoutKeys(
      JSON.parseAny(req.content.asString.getOrElse("{}")),
      Set("max_tokens", "stream")
    )
    LlmHttp
      .postJson(http, req.withJsonContent(json.toJSON))
      .map(JsonCodec[CountTokensResponse].decode)

  override def generate(request: LlmRequest): Rx[LlmResponse] = messages(
    AnthropicConverter.fromLlmRequest(request)
  ).map(AnthropicConverter.toLlmResponse)

  override def stream(request: LlmRequest): Rx[LlmStreamPart] =
    LlmHttp.convert(
      stream(AnthropicConverter.fromLlmRequest(request)),
      AnthropicConverter.StreamDecoder()
    )(_.decode(_), _.finish())

  override def close(): Unit = http.close()

end AnthropicClient
