package wvlet.uni.ai.openai

import wvlet.uni.ai.*
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.rx.Rx

/**
  * @param baseUrl
  *   the API root including `/v1`; point it at any OpenAI-compatible server (vLLM, Ollama,
  *   `https://bedrock-runtime.<region>.amazonaws.com/openai/v1`, ...)
  * @param useChatCompletions
  *   serve [[LlmClient]] calls through Chat Completions instead of Responses, for servers that only
  *   implement Chat Completions
  */
case class OpenAIConfig(
    apiKey: Option[String],
    baseUrl: String = "https://api.openai.com/v1",
    organization: Option[String] = None,
    project: Option[String] = None,
    headers: Seq[(String, String)] = Nil,
    useChatCompletions: Boolean = false
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

object OpenAIConfig:
  /** The OpenAI API with the key in `OPENAI_API_KEY` (and `OPENAI_BASE_URL` when set) */
  def fromEnv: OpenAIConfig = OpenAIConfig(
    sys.env.get("OPENAI_API_KEY"),
    sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1")
  )

/**
  * A client of the OpenAI Responses and Chat Completions APIs.
  *
  * {{{
  * val client = OpenAIClient(OpenAIConfig.fromEnv)
  * client.responses(ResponsesRequest("gpt-5.1", Seq(Item.user("Hello"))))
  * client.streamResponses(request)
  * client.chatCompletions(ChatCompletionRequest("gpt-5.1", Seq(ChatMessage.user("Hello"))))
  * }}}
  */
class OpenAIClient(val config: OpenAIConfig, http: HttpAsyncClient = LlmHttp.newClient)
    extends LlmClient
    with AutoCloseable:

  private def headers: Seq[(String, String)] =
    config.apiKey.map(k => "Authorization" -> s"Bearer ${k}").toSeq ++
      config.organization.map("OpenAI-Organization" -> _) ++
      config.project.map("OpenAI-Project" -> _) ++ config.headers

  private def url(path: String) = s"${config.baseUrl.stripSuffix("/")}${path}"

  def responses(r: ResponsesRequest): Rx[Response] = LlmHttp
    .postJson(
      http,
      LlmHttp.jsonRequest(
        url("/responses"),
        JsonCodec[ResponsesRequest].encode(r.copy(stream = None)),
        headers
      )
    )
    .map(JsonCodec[Response].decode)

  def streamResponses(r: ResponsesRequest): Rx[ResponseStreamEvent] = LlmHttp
    .postSSE(
      http,
      LlmHttp.jsonRequest(
        url("/responses"),
        JsonCodec[ResponsesRequest].encode(r.withStream(true)),
        headers
      )
    )
    .filter(e => e.data.nonEmpty && e.data != "[DONE]")
    .map(ResponseStreamEvent.fromServerSentEvent)

  def chatCompletions(r: ChatCompletionRequest): Rx[ChatCompletion] = LlmHttp
    .postJson(
      http,
      LlmHttp.jsonRequest(
        url("/chat/completions"),
        JsonCodec[ChatCompletionRequest].encode(r.withStream(false).copy(stream = None)),
        headers
      )
    )
    .map(JsonCodec[ChatCompletion].decode)

  /** Stream chunks until `[DONE]`; the last chunk carries usage */
  def streamChatCompletions(r: ChatCompletionRequest): Rx[ChatCompletionChunk] = LlmHttp
    .postSSE(
      http,
      LlmHttp.jsonRequest(
        url("/chat/completions"),
        JsonCodec[ChatCompletionRequest].encode(r.withStream(true)),
        headers
      )
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
