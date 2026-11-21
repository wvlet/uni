package wvlet.uni.ai.openai

import wvlet.uni.ai.*
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.Request
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.rx.Rx

/**
  * @param baseUrl
  *   the API root including `/v1`; point it at any OpenAI-compatible server (vLLM, Ollama, ...)
  * @param useChatCompletions
  *   serve [[LlmClient]] calls through Chat Completions instead of Responses, for servers that only
  *   implement Chat Completions
  * @param bedrock
  *   serve requests from Amazon Bedrock's OpenAI API (`/openai/v1` on bedrock-runtime) instead of
  *   `baseUrl`, authenticated with a Bedrock API key or SigV4. See [[OpenAIConfig.bedrock]].
  */
case class OpenAIConfig(
    apiKey: Option[String],
    baseUrl: String = "https://api.openai.com/v1",
    organization: Option[String] = None,
    project: Option[String] = None,
    headers: Seq[(String, String)] = Nil,
    useChatCompletions: Boolean = false,
    bedrock: Option[OpenAIBedrock] = None
):
  def withApiKey(k: String): OpenAIConfig                 = copy(apiKey = Some(k))
  def noApiKey(): OpenAIConfig                            = copy(apiKey = None)
  def withBaseUrl(u: String): OpenAIConfig                = copy(baseUrl = u)
  def withOrganization(o: String): OpenAIConfig           = copy(organization = Some(o))
  def noOrganization(): OpenAIConfig                      = copy(organization = None)
  def withProject(p: String): OpenAIConfig                = copy(project = Some(p))
  def noProject(): OpenAIConfig                           = copy(project = None)
  def withHeaders(h: Seq[(String, String)]): OpenAIConfig = copy(headers = h)
  def withUseChatCompletions(b: Boolean): OpenAIConfig    = copy(useChatCompletions = b)
  def withBedrock(b: OpenAIBedrock): OpenAIConfig         = copy(bedrock = Some(b))
  def noBedrock(): OpenAIConfig                           = copy(bedrock = None)

/**
  * Amazon Bedrock's OpenAI API on bedrock-runtime, for the OpenAI models Bedrock hosts (e.g.
  * `us.openai.gpt-5.6-sol` on Responses, `openai.gpt-oss-120b-1:0` on Chat Completions)
  *
  * @param endpoint
  *   overrides the host `bedrock-runtime.<region>.amazonaws.com` (e.g. a VPC endpoint)
  */
case class OpenAIBedrock(
    region: String,
    auth: BedrockAuth = BedrockAuth.default,
    endpoint: Option[String] = None
)

object OpenAIConfig:
  /** The OpenAI API with the key in `OPENAI_API_KEY` (and `OPENAI_BASE_URL` when set) */
  def fromEnv: OpenAIConfig = OpenAIConfig(
    sys.env.get("OPENAI_API_KEY"),
    sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1")
  )

  /** Amazon Bedrock's OpenAI API in `region`, with `AWS_BEARER_TOKEN_BEDROCK` or AWS credentials */
  def bedrock(
      region: String,
      auth: BedrockAuth = BedrockAuth.default,
      endpoint: Option[String] = None
  ): OpenAIConfig = OpenAIConfig(
    apiKey = None,
    bedrock = Some(OpenAIBedrock(region, auth, endpoint))
  )

/**
  * A client of the OpenAI Responses and Chat Completions APIs.
  *
  * {{{
  * val client = OpenAIClient(OpenAIConfig.fromEnv)
  * client.responses(ResponsesRequest("gpt-5.1", Seq(Item.user("Hello"))))
  * client.streamResponses(request)
  * client.chatCompletions(ChatCompletionRequest("gpt-5.1", Seq(ChatMessage.user("Hello"))))
  *
  * // OpenAI models on Amazon Bedrock
  * OpenAIClient(OpenAIConfig.bedrock("us-east-1")).responses(ResponsesRequest("us.openai.gpt-5.6-sol", ...))
  * }}}
  */
class OpenAIClient(val config: OpenAIConfig, http: HttpAsyncClient = LlmHttp.newClient)
    extends LlmClient
    with AutoCloseable:

  private def headers: Seq[(String, String)] =
    config.apiKey.map(k => "Authorization" -> s"Bearer ${k}").toSeq ++
      config.organization.map("OpenAI-Organization" -> _) ++
      config.project.map("OpenAI-Project" -> _) ++ config.headers

  private def post(path: String, body: JSONValue): Request =
    config.bedrock match
      case Some(b) =>
        LlmHttp.bedrockRequest(
          b.region,
          b.endpoint,
          s"/openai/v1${path}",
          body,
          b.auth,
          headers,
          key => "Authorization" -> s"Bearer ${key}"
        )
      case None =>
        LlmHttp.jsonRequest(s"${config.baseUrl.stripSuffix("/")}${path}", body, headers)

  def responses(r: ResponsesRequest): Rx[Response] = LlmHttp
    .postJson(http, post("/responses", JsonCodec[ResponsesRequest].encode(r.copy(stream = None))))
    .map(JsonCodec[Response].decode)

  def streamResponses(r: ResponsesRequest): Rx[ResponseStreamEvent] = LlmHttp
    .postSSE(http, post("/responses", JsonCodec[ResponsesRequest].encode(r.withStream(true))))
    .filter(e => e.data.nonEmpty && e.data != "[DONE]")
    .map(ResponseStreamEvent.fromServerSentEvent)

  def chatCompletions(r: ChatCompletionRequest): Rx[ChatCompletion] = LlmHttp
    .postJson(
      http,
      post(
        "/chat/completions",
        JsonCodec[ChatCompletionRequest].encode(r.withStream(false).copy(stream = None))
      )
    )
    .map(JsonCodec[ChatCompletion].decode)

  /** Stream chunks until `[DONE]`; the last chunk carries usage */
  def streamChatCompletions(r: ChatCompletionRequest): Rx[ChatCompletionChunk] = LlmHttp
    .postSSE(
      http,
      post("/chat/completions", JsonCodec[ChatCompletionRequest].encode(r.withStream(true)))
    )
    .filter(e => e.data.nonEmpty && !ChatCompletionChunk.isDone(e))
    .map(ChatCompletionChunk.fromServerSentEvent)

  override def generate(request: LlmRequest): Rx[LlmResponse] =
    if config.useChatCompletions then
      chatCompletions(ChatCompletionsConverter.fromLlmRequest(request)).map(
        ChatCompletionsConverter.toLlmResponse
      )
    else
      responses(ResponsesConverter.fromLlmRequest(request)).map(ResponsesConverter.toLlmResponse)

  override def stream(request: LlmRequest): Rx[LlmStreamPart] =
    if config.useChatCompletions then
      LlmHttp.convert(
        streamChatCompletions(ChatCompletionsConverter.fromLlmRequest(request)),
        ChatCompletionsConverter.StreamDecoder()
      )(_.decode(_), _.finish())
    else
      LlmHttp.convert(
        streamResponses(ResponsesConverter.fromLlmRequest(request)),
        ResponsesConverter.StreamDecoder()
      )(_.decode(_), _.finish())

  override def close(): Unit = http.close()

end OpenAIClient
