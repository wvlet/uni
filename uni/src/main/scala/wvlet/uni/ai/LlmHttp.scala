package wvlet.uni.ai

import wvlet.uni.http.Http
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.HttpException
import wvlet.uni.http.HttpMaxRetryException
import wvlet.uni.http.Request
import wvlet.uni.http.ServerSentEvent
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.AwsCredentialsProvider
import wvlet.uni.storage.GoogleCredentialsProvider
import wvlet.uni.storage.Sha256
import wvlet.uni.storage.SigV4
import wvlet.uni.storage.SigV4Request

/**
  * A failed LLM API call: the HTTP status, the vendor's error type when the body names one, and the
  * error body
  */
class LlmApiException(
    val statusCode: Int,
    val errorType: Option[String],
    message: String,
    val body: String,
    cause: Throwable = null
) extends RuntimeException(
      s"${statusCode}${errorType.map(t => s" ${t}").getOrElse("")}: ${message}",
      cause
    ):
  def isRetryable: Boolean = statusCode == 429 || statusCode == 408 || statusCode >= 500

object LlmApiException:
  /**
    * Read the error shapes of the vendors: `{"error": {"type"|"code"|"status", "message"}}` and
    * Bedrock's `{"message": ...}`
    */
  def fromHttpException(e: HttpException): Throwable =
    e.statusCode match
      case Some(code) =>
        val body = e.contentAsString.getOrElse("")
        val json = Json.parseOrString(body)
        val err  = JsonObj.unapply(json).flatMap(_.objOpt("error"))
        val tpe  = err
          .flatMap(o => o.strOpt("type").orElse(o.strOpt("code")).orElse(o.strOpt("status")))
          .orElse(e.response.flatMap(_.header("x-amzn-ErrorType")).map(_.takeWhile(_ != ':')))
        val msg = err
          .flatMap(_.strOpt("message"))
          .orElse(JsonObj.unapply(json).flatMap(_.strOpt("message")))
          .getOrElse(body)
        LlmApiException(code, tpe, msg, body, e)
      case None =>
        e

/**
  * How to authenticate to Amazon Bedrock: a Bedrock API key (from `AWS_BEARER_TOKEN_BEDROCK`) or
  * SigV4 with AWS credentials
  */
enum BedrockAuth:
  case ApiKey(key: String)
  case Credentials(provider: AwsCredentialsProvider)

object BedrockAuth:
  /** `AWS_BEARER_TOKEN_BEDROCK` when set, else the default AWS credentials chain */
  def default: BedrockAuth =
    sys.env.get("AWS_BEARER_TOKEN_BEDROCK").filter(_.nonEmpty) match
      case Some(key) =>
        ApiKey(key)
      case None =>
        Credentials(AwsCredentialsProvider.default)

private[ai] object LlmHttp:
  def newClient: HttpAsyncClient = Http.client.noRetry.newAsyncClient

  def jsonRequest(url: String, body: JSONValue, headers: Seq[(String, String)]): Request =
    headers.foldLeft(Request.post(url).withJsonContent(body.toJSON)) { case (r, (k, v)) =>
      r.setHeader(k, v)
    }

  private def mapError[A](rx: Rx[A]): Rx[A] = rx.recoverWith {
    case e: HttpException =>
      Rx.exception(LlmApiException.fromHttpException(e))
    // A retryable status (429, 5xx) arrives wrapped once retries are used up
    case e: HttpMaxRetryException =>
      e.getCause match
        case h: HttpException =>
          Rx.exception(LlmApiException.fromHttpException(h))
        case _ =>
          Rx.exception(e)
  }

  def postJson(http: HttpAsyncClient, req: Request): Rx[JSONValue] = mapError(
    http.send(req).map(r => JSON.parseAny(r.contentAsString.getOrElse("{}")))
  )

  def postSSE(http: HttpAsyncClient, req: Request): Rx[ServerSentEvent] = mapError(
    http.sendSSE(req)
  )

  /** Decode a stream with a per-subscription stateful converter, flushing it at the end */
  def convert[A, B, C](
      src: Rx[A],
      newState: => C
  )(step: (C, A) => Seq[B], end: C => Seq[B]): Rx[B] = Rx
    .single(newState)
    .flatMap { state =>
      src.flatMap(a => Rx.fromSeq(step(state, a))).concat(Rx.fromSeq(end(state)))
    }

  /** Bearer token of a Google credentials provider, for Vertex AI */
  def googleToken(provider: GoogleCredentialsProvider): String = provider
    .accessToken
    .map(_.token)
    .getOrElse(throw IllegalStateException(s"No Google access token from ${provider.name}"))

  /**
    * A Bedrock request on bedrock-runtime: a Bedrock API key goes in the header `apiKeyHeader`
    * returns (`x-api-key` for the Anthropic API, `Authorization: Bearer` for the OpenAI API); AWS
    * credentials sign the request with SigV4 (service `bedrock`). `path` must need no
    * percent-encoding.
    */
  def bedrockRequest(
      region: String,
      endpoint: Option[String],
      path: String,
      body: JSONValue,
      auth: BedrockAuth,
      headers: Seq[(String, String)],
      apiKeyHeader: String => (String, String)
  ): Request =
    val host    = endpoint.getOrElse(s"bedrock-runtime.${region}.amazonaws.com")
    val payload = body.toJSON
    val base    = jsonRequest(s"https://${host}${path}", body, headers)
    auth match
      case BedrockAuth.ApiKey(key) =>
        val (k, v) = apiKeyHeader(key)
        base.setHeader(k, v)
      case BedrockAuth.Credentials(provider) =>
        val creds = provider
          .credentials
          .getOrElse(throw IllegalStateException(s"No AWS credentials from ${provider.name}"))
        val signed = SigV4.signHeaders(
          SigV4Request(
            "POST",
            host,
            path,
            headers = Seq("content-type" -> "application/json"),
            payloadHash = Sha256.hex(payload.getBytes("UTF-8"))
          ),
          creds,
          region,
          service = "bedrock",
          includeContentSha256 = false
        )
        signed.foldLeft(base) { case (r, (k, v)) =>
          r.setHeader(k, v)
        }

  end bedrockRequest

end LlmHttp

/**
  * A vendor-neutral client: any vendor client takes an [[LlmRequest]] and returns an
  * [[LlmResponse]] or a stream of [[LlmStreamPart]]
  */
trait LlmClient:
  def generate(request: LlmRequest): Rx[LlmResponse]
  def stream(request: LlmRequest): Rx[LlmStreamPart]
