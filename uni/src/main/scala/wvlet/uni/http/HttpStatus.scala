/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http

/**
  * HTTP response status codes as defined in RFC 9110
  */
enum HttpStatus(val code: Int, val reason: String):
  // 1xx Informational
  case Continue_100           extends HttpStatus(100, "Continue")
  case SwitchingProtocols_101 extends HttpStatus(101, "Switching Protocols")
  case Processing_102         extends HttpStatus(102, "Processing")
  case EarlyHints_103         extends HttpStatus(103, "Early Hints")

  // 2xx Success
  case Ok_200                   extends HttpStatus(200, "OK")
  case Created_201              extends HttpStatus(201, "Created")
  case Accepted_202             extends HttpStatus(202, "Accepted")
  case NonAuthoritativeInfo_203 extends HttpStatus(203, "Non-Authoritative Information")
  case NoContent_204            extends HttpStatus(204, "No Content")
  case ResetContent_205         extends HttpStatus(205, "Reset Content")
  case PartialContent_206       extends HttpStatus(206, "Partial Content")
  case MultiStatus_207          extends HttpStatus(207, "Multi-Status")
  case AlreadyReported_208      extends HttpStatus(208, "Already Reported")
  case IMUsed_226               extends HttpStatus(226, "IM Used")

  // 3xx Redirection
  case MultipleChoices_300   extends HttpStatus(300, "Multiple Choices")
  case MovedPermanently_301  extends HttpStatus(301, "Moved Permanently")
  case Found_302             extends HttpStatus(302, "Found")
  case SeeOther_303          extends HttpStatus(303, "See Other")
  case NotModified_304       extends HttpStatus(304, "Not Modified")
  case UseProxy_305          extends HttpStatus(305, "Use Proxy")
  case TemporaryRedirect_307 extends HttpStatus(307, "Temporary Redirect")
  case PermanentRedirect_308 extends HttpStatus(308, "Permanent Redirect")

  // 4xx Client Error
  case BadRequest_400           extends HttpStatus(400, "Bad Request")
  case Unauthorized_401         extends HttpStatus(401, "Unauthorized")
  case PaymentRequired_402      extends HttpStatus(402, "Payment Required")
  case Forbidden_403            extends HttpStatus(403, "Forbidden")
  case NotFound_404             extends HttpStatus(404, "Not Found")
  case MethodNotAllowed_405     extends HttpStatus(405, "Method Not Allowed")
  case NotAcceptable_406        extends HttpStatus(406, "Not Acceptable")
  case ProxyAuthRequired_407    extends HttpStatus(407, "Proxy Authentication Required")
  case RequestTimeout_408       extends HttpStatus(408, "Request Timeout")
  case Conflict_409             extends HttpStatus(409, "Conflict")
  case Gone_410                 extends HttpStatus(410, "Gone")
  case LengthRequired_411       extends HttpStatus(411, "Length Required")
  case PreconditionFailed_412   extends HttpStatus(412, "Precondition Failed")
  case PayloadTooLarge_413      extends HttpStatus(413, "Payload Too Large")
  case UriTooLong_414           extends HttpStatus(414, "URI Too Long")
  case UnsupportedMediaType_415 extends HttpStatus(415, "Unsupported Media Type")
  case RangeNotSatisfiable_416  extends HttpStatus(416, "Range Not Satisfiable")
  case ExpectationFailed_417    extends HttpStatus(417, "Expectation Failed")
  case ImATeapot_418            extends HttpStatus(418, "I'm a teapot")
  case MisdirectedRequest_421   extends HttpStatus(421, "Misdirected Request")
  case UnprocessableEntity_422  extends HttpStatus(422, "Unprocessable Entity")
  case Locked_423               extends HttpStatus(423, "Locked")
  case FailedDependency_424     extends HttpStatus(424, "Failed Dependency")
  case TooEarly_425             extends HttpStatus(425, "Too Early")
  case UpgradeRequired_426      extends HttpStatus(426, "Upgrade Required")
  case PreconditionRequired_428 extends HttpStatus(428, "Precondition Required")
  case TooManyRequests_429      extends HttpStatus(429, "Too Many Requests")
  case HeaderFieldsTooLarge_431 extends HttpStatus(431, "Request Header Fields Too Large")
  case UnavailableForLegal_451  extends HttpStatus(451, "Unavailable For Legal Reasons")
  case ClientClosedRequest_499  extends HttpStatus(499, "Client Closed Request")

  // 5xx Server Error
  case InternalServerError_500     extends HttpStatus(500, "Internal Server Error")
  case NotImplemented_501          extends HttpStatus(501, "Not Implemented")
  case BadGateway_502              extends HttpStatus(502, "Bad Gateway")
  case ServiceUnavailable_503      extends HttpStatus(503, "Service Unavailable")
  case GatewayTimeout_504          extends HttpStatus(504, "Gateway Timeout")
  case HttpVersionNotSupported_505 extends HttpStatus(505, "HTTP Version Not Supported")
  case VariantAlsoNegotiates_506   extends HttpStatus(506, "Variant Also Negotiates")
  case InsufficientStorage_507     extends HttpStatus(507, "Insufficient Storage")
  case LoopDetected_508            extends HttpStatus(508, "Loop Detected")
  case NotExtended_510             extends HttpStatus(510, "Not Extended")
  case NetworkAuthRequired_511     extends HttpStatus(511, "Network Authentication Required")

  // Other status holder for non-standard codes
  case Other(override val code: Int)
      extends HttpStatus(
        code,
        code match
          case c if c >= 100 && c < 600 =>
            s"Unknown ${c / 100}xx (${code})"
          case _ =>
            s"Unknown (${code})"
      )

  def isInformational: Boolean = HttpStatus.isInformational(code)
  def isSuccessful: Boolean    = HttpStatus.isSuccessful(code)
  def isRedirection: Boolean   = HttpStatus.isRedirection(code)

  /**
    * Whether a client follows this status to its `Location`: 301, 302, 303, 307, and 308, as in the
    * Fetch standard. Other 3xx, such as `304 Not Modified` for a conditional request, go back to
    * the caller.
    */
  def isFollowableRedirect: Boolean =
    this match
      case MovedPermanently_301 | Found_302 | SeeOther_303 | TemporaryRedirect_307 |
          PermanentRedirect_308 =>
        true
      case _ =>
        false

  def isClientError: Boolean  = HttpStatus.isClientError(code)
  def isServerError: Boolean  = HttpStatus.isServerError(code)
  def isUnknownState: Boolean = HttpStatus.isUnknownState(code)

  def isRetryable: Boolean =
    this match
      case ServiceUnavailable_503 =>
        true
      case GatewayTimeout_504 =>
        true
      case TooManyRequests_429 =>
        true
      case RequestTimeout_408 =>
        true
      case Gone_410 =>
        // Server may have failed to process the request
        true
      case ClientClosedRequest_499 =>
        // Client may have closed connection prematurely
        true
      case _ =>
        false

  override def toString: String = s"${code} ${reason}"

end HttpStatus

object HttpStatus:
  // All known status codes (excluding Other which is parameterized)
  val knownStatuses: Seq[HttpStatus] = Seq(
    Continue_100,
    SwitchingProtocols_101,
    Processing_102,
    EarlyHints_103,
    Ok_200,
    Created_201,
    Accepted_202,
    NonAuthoritativeInfo_203,
    NoContent_204,
    ResetContent_205,
    PartialContent_206,
    MultiStatus_207,
    AlreadyReported_208,
    IMUsed_226,
    MultipleChoices_300,
    MovedPermanently_301,
    Found_302,
    SeeOther_303,
    NotModified_304,
    UseProxy_305,
    TemporaryRedirect_307,
    PermanentRedirect_308,
    BadRequest_400,
    Unauthorized_401,
    PaymentRequired_402,
    Forbidden_403,
    NotFound_404,
    MethodNotAllowed_405,
    NotAcceptable_406,
    ProxyAuthRequired_407,
    RequestTimeout_408,
    Conflict_409,
    Gone_410,
    LengthRequired_411,
    PreconditionFailed_412,
    PayloadTooLarge_413,
    UriTooLong_414,
    UnsupportedMediaType_415,
    RangeNotSatisfiable_416,
    ExpectationFailed_417,
    ImATeapot_418,
    MisdirectedRequest_421,
    UnprocessableEntity_422,
    Locked_423,
    FailedDependency_424,
    TooEarly_425,
    UpgradeRequired_426,
    PreconditionRequired_428,
    TooManyRequests_429,
    HeaderFieldsTooLarge_431,
    UnavailableForLegal_451,
    ClientClosedRequest_499,
    InternalServerError_500,
    NotImplemented_501,
    BadGateway_502,
    ServiceUnavailable_503,
    GatewayTimeout_504,
    HttpVersionNotSupported_505,
    VariantAlsoNegotiates_506,
    InsufficientStorage_507,
    LoopDetected_508,
    NotExtended_510,
    NetworkAuthRequired_511
  )

  private val statusByCode: Map[Int, HttpStatus] = knownStatuses.map(s => s.code -> s).toMap

  def ofCode(code: Int): HttpStatus = statusByCode.getOrElse(code, Other(code))

  def unapply(code: Int): Option[HttpStatus] = Some(ofCode(code))

  // Static classification methods for use without creating HttpStatus instances
  def isUnknownState(code: Int): Boolean  = code < 100 || code >= 600
  def isInformational(code: Int): Boolean = code >= 100 && code < 200
  def isSuccessful(code: Int): Boolean    = code >= 200 && code < 300
  def isRedirection(code: Int): Boolean   = code >= 300 && code < 400
  def isClientError(code: Int): Boolean   = code >= 400 && code < 500
  def isServerError(code: Int): Boolean   = code >= 500 && code < 600

end HttpStatus
