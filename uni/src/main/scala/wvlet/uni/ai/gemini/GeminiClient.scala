package wvlet.uni.ai.gemini

import wvlet.uni.ai.*
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.rx.Rx
import wvlet.uni.storage.GoogleCredentialsProvider

/** Where Gemini is served */
enum GeminiHost:
  /** Google AI Studio (Gemini API) with an API key */
  case AiStudio(
      apiKey: String,
      baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
  )

  /**
    * Vertex AI. `apiVersion` `v1beta1` supports function-call ids (Gemini 3); `region` may be
    * `global`.
    */
  case Vertex(
      projectId: String,
      region: String,
      apiVersion: String = "v1beta1",
      credentials: GoogleCredentialsProvider = GoogleCredentialsProvider.applicationDefault(
        Seq(GoogleCredentialsProvider.CloudPlatform)
      )
  )

case class GeminiConfig(host: GeminiHost, headers: Seq[(String, String)] = Nil):
  def withHost(h: GeminiHost): GeminiConfig               = copy(host = h)
  def withHeaders(h: Seq[(String, String)]): GeminiConfig = copy(headers = h)

  /** Whether the endpoint accepts `functionCall.id` */
  def supportsFunctionCallIds: Boolean =
    host match
      case GeminiHost.Vertex(_, _, version, _) =>
        version != "v1"
      case _ =>
        true

object GeminiConfig:
  /** Google AI Studio with the key in `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) */
  def fromEnv: GeminiConfig = GeminiConfig(
    GeminiHost.AiStudio(
      sys
        .env
        .get("GEMINI_API_KEY")
        .orElse(sys.env.get("GOOGLE_API_KEY"))
        .getOrElse(throw IllegalStateException("GEMINI_API_KEY is not set"))
    )
  )

/**
  * A client of the Gemini `generateContent` API on Google AI Studio or Vertex AI.
  *
  * {{{
  * val client = GeminiClient(GeminiConfig.fromEnv)
  * client.generateContent("gemini-2.5-flash", GenerateContentRequest(Seq(Content.user("Hello"))))
  * client.streamGenerateContent(model, request)
  * }}}
  *
  * @param defaultModel
  *   the model for [[LlmClient]] calls whose request has no model
  */
class GeminiClient(
    val config: GeminiConfig,
    http: HttpAsyncClient = LlmHttp.newClient,
    defaultModel: String = ""
) extends LlmClient
    with AutoCloseable:
  import GeminiHost.*

  private def endpoint(model: String, method: String): (String, Seq[(String, String)]) =
    config.host match
      case AiStudio(key, baseUrl) =>
        (s"${baseUrl.stripSuffix("/")}/models/${model}:${method}", Seq("x-goog-api-key" -> key))
      case Vertex(project, region, version, creds) =>
        val host =
          if region == "global" then
            "aiplatform.googleapis.com"
          else
            s"${region}-aiplatform.googleapis.com"
        (
          s"https://${host}/${version}/projects/${project}/locations/${region}/publishers/google/models/${model}:${method}",
          Seq("Authorization" -> s"Bearer ${LlmHttp.googleToken(creds)}")
        )

  private def operation(model: String, r: GenerateContentRequest, stream: Boolean) =
    val g = r.generationConfig
    LlmOperation(
      "generate_content",
      config.host match
        case _: AiStudio =>
          "gcp.gemini"
        case _: Vertex =>
          "gcp.vertex_ai"
      ,
      model,
      stream,
      g.flatMap(_.maxOutputTokens).map(_.toLong),
      g.flatMap(_.temperature),
      g.flatMap(_.topP)
    )

  // Each chunk of a stream carries the usage so far; the last one is final
  private def observe(trace: LlmCallTrace, r: GenerateContentResponse): Unit =
    trace.response(r.responseId.getOrElse(""), r.modelVersion.getOrElse(""))
    r.usageMetadata.foreach(u => trace.setUsage(GeminiConverter.toLlmUsage(u)))
    trace.setFinishReasons(r.candidates.flatMap(_.finishReason))

  def generateContent(model: String, r: GenerateContentRequest): Rx[GenerateContentResponse] =
    LlmTelemetry.traced(
      http,
      operation(model, r, stream = false), {
        val (url, auth) = endpoint(model, "generateContent")
        LlmHttp.jsonRequest(
          url,
          JsonCodec[GenerateContentRequest].encode(r),
          auth ++ config.headers
        )
      }
    )(req => LlmHttp.postJson(http, req).map(JsonCodec[GenerateContentResponse].decode))(observe)

  def streamGenerateContent(model: String, r: GenerateContentRequest): Rx[GenerateContentResponse] =
    LlmTelemetry.traced(
      http,
      operation(model, r, stream = true), {
        val (url, auth) = endpoint(model, "streamGenerateContent")
        LlmHttp.jsonRequest(
          s"${url}?alt=sse",
          JsonCodec[GenerateContentRequest].encode(r),
          auth ++ config.headers
        )
      }
    ) { req =>
      LlmHttp
        .postSSE(http, req)
        .filter(_.data.nonEmpty)
        .map(GenerateContentResponse.fromServerSentEvent)
    }(observe)

  private def modelOf(r: LlmRequest): String =
    if r.model.nonEmpty then
      r.model
    else
      defaultModel

  override def generate(request: LlmRequest): Rx[LlmResponse] =
    val model = modelOf(request)
    generateContent(model, GeminiConverter.fromLlmRequest(request, config.supportsFunctionCallIds))
      .map(GeminiConverter.toLlmResponse(_, model))

  override def stream(request: LlmRequest): Rx[LlmStreamPart] =
    val model = modelOf(request)
    LlmHttp.convert(
      streamGenerateContent(
        model,
        GeminiConverter.fromLlmRequest(request, config.supportsFunctionCallIds)
      ),
      GeminiConverter.StreamDecoder(model)
    )(_.decode(_), _.finish())

  override def close(): Unit = http.close()

end GeminiClient
