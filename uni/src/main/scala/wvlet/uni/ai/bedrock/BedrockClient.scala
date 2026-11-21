package wvlet.uni.ai.bedrock

import wvlet.uni.ai.*
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.rx.Rx

/**
  * @param endpoint
  *   a host to use instead of `bedrock-runtime.<region>.amazonaws.com` (e.g. a VPC endpoint)
  */
case class BedrockConfig(
    region: String,
    auth: BedrockAuth = BedrockAuth.default,
    endpoint: Option[String] = None
):
  def withRegion(r: String): BedrockConfig    = copy(region = r)
  def withAuth(a: BedrockAuth): BedrockConfig = copy(auth = a)
  def withEndpoint(e: String): BedrockConfig  = copy(endpoint = Some(e))
  def noEndpoint(): BedrockConfig             = copy(endpoint = None)

object BedrockConfig:
  /** The region in `AWS_REGION` (or `AWS_DEFAULT_REGION`), with [[BedrockAuth.default]] */
  def fromEnv: BedrockConfig = BedrockConfig(
    sys.env.get("AWS_REGION").orElse(sys.env.get("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
  )

/**
  * A client of the Bedrock Converse API. For Claude through InvokeModel with the Anthropic request
  * body, use `AnthropicClient` with `AnthropicHost.Bedrock`.
  *
  * {{{
  * val client = BedrockClient(BedrockConfig.fromEnv)
  * client.converse("us.anthropic.claude-sonnet-4-5-20250929-v1:0", ConverseRequest(Seq(Message.user("Hello"))))
  * client.converseStream(modelId, request)
  * }}}
  *
  * @param defaultModel
  *   the model id for [[LlmClient]] calls whose request has no model
  */
class BedrockClient(
    val config: BedrockConfig,
    http: HttpAsyncClient = LlmHttp.newClient,
    defaultModel: String = ""
) extends LlmClient
    with AutoCloseable:

  private def request(modelId: String, action: String, r: ConverseRequest, accept: String) = LlmHttp
    .bedrockRequest(
      config.region,
      config.endpoint,
      Seq("model", modelId, action),
      JsonCodec[ConverseRequest].encode(r),
      config.auth,
      accept
    )

  def converse(modelId: String, r: ConverseRequest): Rx[ConverseResponse] = LlmHttp
    .postJson(http, request(modelId, "converse", r, "application/json"))
    .map(JsonCodec[ConverseResponse].decode)

  /** Stream events, decoded from the AWS event-stream frames */
  def converseStream(modelId: String, r: ConverseRequest): Rx[ConverseStreamEvent] =
    EventStreamDecoder
      .frames(
        LlmHttp.postBytes(
          http,
          request(modelId, "converse-stream", r, "application/vnd.amazon.eventstream")
        )
      )
      .map(ConverseStreamEvent.fromFrame)

  private def modelOf(r: LlmRequest): String =
    if r.model.nonEmpty then
      r.model
    else
      defaultModel

  override def generate(request: LlmRequest): Rx[LlmResponse] =
    val model = modelOf(request)
    converse(model, BedrockConverter.fromLlmRequest(request.withModel(model))).map(
      BedrockConverter.toLlmResponse(_, model)
    )

  override def stream(request: LlmRequest): Rx[LlmStreamPart] =
    val model = modelOf(request)
    LlmHttp.convert(
      converseStream(model, BedrockConverter.fromLlmRequest(request.withModel(model))),
      BedrockConverter.StreamDecoder(model)
    )(_.decode(_), _.finish())

  override def close(): Unit = http.close()

end BedrockClient
