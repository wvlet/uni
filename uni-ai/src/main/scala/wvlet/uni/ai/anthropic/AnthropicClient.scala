package wvlet.uni.ai.anthropic

import wvlet.uni.ai.*
import wvlet.uni.ai.bedrock.EventStreamDecoder
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.GoogleCredentialsProvider

import java.util.Base64

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

  /** Claude on Amazon Bedrock through InvokeModel */
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
      case _: Direct =>
        json
      case _: Vertex =>
        // The model goes in the URL; the version in the body
        withoutKeys(json, Set("model")) :+ ("anthropic_version" -> JSONString("vertex-2023-10-16"))
      case _: Bedrock =>
        withoutKeys(json, Set("model", "stream")) :+
          ("anthropic_version" -> JSONString("bedrock-2023-05-31"))

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
        val action =
          if stream then
            "invoke-with-response-stream"
          else
            "invoke"
        val withBetas =
          if config.betas.isEmpty then
            b
          else
            JSONObject(
              b.asInstanceOf[JSONObject].v :+
                ("anthropic_beta" -> JsonCodec[Seq[String]].encode(config.betas))
            )
        config
          .headers
          .foldLeft(
            LlmHttp.bedrockRequest(
              bd.region,
              bd.endpoint,
              Seq("model", r.model, action),
              withBetas,
              bd.auth,
              accept =
                if stream then
                  "application/vnd.amazon.eventstream"
                else
                  "application/json"
            )
          ) { case (req, (k, v)) =>
            req.setHeader(k, v)
          }
    end match

  end request

  /** Send a request and wait for the whole message */
  def messages(r: MessagesRequest): Rx[MessagesResponse] = LlmHttp
    .postJson(http, request(r, stream = false))
    .map(JsonCodec[MessagesResponse].decode)

  /** Send a request and receive the message as stream events */
  def stream(r: MessagesRequest): Rx[StreamEvent] =
    config.host match
      case _: Bedrock =>
        // InvokeModelWithResponseStream wraps each event in a "chunk" frame with base64 bytes
        EventStreamDecoder
          .frames(LlmHttp.postBytes(http, request(r, stream = true)))
          .flatMap { frame =>
            frame.messageType match
              case "event" if frame.eventType.contains("chunk") =>
                val bytes = JsonObj(JSON.parseAny(frame.payloadString)).str("bytes")
                val json  = String(Base64.getDecoder.decode(bytes), "UTF-8")
                Rx.single(JsonCodec[StreamEvent].fromJson(json))
              case "event" =>
                Rx.empty
              case _ =>
                val msg = JsonObj
                  .unapply(Json.parseOrString(frame.payloadString))
                  .flatMap(_.strOpt("message"))
                Rx.single(
                  StreamEvent.Error(
                    ErrorDetail(
                      frame.exceptionType.getOrElse("api_error"),
                      msg.getOrElse(frame.payloadString)
                    )
                  )
                )
          }
      case _ =>
        LlmHttp
          .postSSE(http, request(r, stream = true))
          .filter(e => e.data.nonEmpty)
          .map(StreamEvent.fromServerSentEvent)

  /** Count the input tokens of a request (Anthropic API only) */
  def countTokens(r: MessagesRequest): Rx[CountTokensResponse] =
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
