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

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.rx.Rx

/**
  * An immutable HTTP response representation
  */
case class Response(
    status: HttpStatus,
    headers: HttpMultiMap = HttpMultiMap.empty,
    content: HttpContent = HttpContent.Empty,
    events: Rx[ServerSentEvent] = Rx.empty
):

  def isSuccessful: Boolean    = status.isSuccessful
  def isClientError: Boolean   = status.isClientError
  def isServerError: Boolean   = status.isServerError
  def isRedirection: Boolean   = status.isRedirection
  def isInformational: Boolean = status.isInformational

  def statusCode: Int = status.code
  def reason: String  = status.reason

  def contentType: Option[ContentType] = content
    .contentType
    .orElse(headers.get(HttpHeader.ContentType).flatMap(ContentType.parse))

  def contentLength: Option[Long] = headers
    .get(HttpHeader.ContentLength)
    .flatMap(_.toLongOption)
    .orElse(Some(content.length))

  def header(name: String): Option[String]    = headers.get(name)
  def headerValues(name: String): Seq[String] = headers.getAll(name)
  def hasHeader(name: String): Boolean        = headers.contains(name)

  def contentAsString: Option[String]     = content.asString
  def contentAsBytes: Option[Array[Byte]] = content.asBytes

  def contentAsJson: Option[JSONValue] =
    content match
      case HttpContent.JsonContent(json, _) =>
        Some(json)
      case other =>
        other
          .asString
          .flatMap { str =>
            try
              Some(JSON.parse(str))
            catch
              case _: Exception =>
                None
          }

  def location: Option[String] = headers.get(HttpHeader.Location)

  // Builder methods
  def withStatus(s: HttpStatus): Response    = copy(status = s)
  def withHeaders(h: HttpMultiMap): Response = copy(headers = h)
  def withContent(c: HttpContent): Response  = copy(content = c)

  def addHeader(name: String, value: String): Response = copy(headers = headers.add(name, value))

  def setHeader(name: String, value: String): Response = copy(headers = headers.set(name, value))

  def removeHeader(name: String): Response = copy(headers = headers.remove(name))

  def withTextContent(text: String): Response = copy(content = HttpContent.text(text))

  def withJsonContent(json: JSONValue): Response = copy(content = HttpContent.json(json))

  def withJsonContent(json: String): Response = copy(content = HttpContent.json(json))

  def withBytesContent(bytes: Array[Byte]): Response = copy(content = HttpContent.bytes(bytes))

  def withHtmlContent(html: String): Response = copy(content = HttpContent.html(html))

  def withContentType(ct: ContentType): Response = setHeader(HttpHeader.ContentType, ct.toString)

  def withLocation(uri: String): Response = setHeader(HttpHeader.Location, uri)

  /**
    * Whether this response is an SSE event stream with streaming events
    */
  def isEventStream: Boolean = contentType.exists(_.isEventStream) && content == HttpContent.Empty

  def withEvents(events: Rx[ServerSentEvent]): Response = copy(events = events)

end Response

object Response:
  def apply(status: HttpStatus): Response = Response(status, HttpMultiMap.empty, HttpContent.Empty)

  // 2xx Success responses
  def ok: Response                       = Response(HttpStatus.Ok_200)
  def ok(content: String): Response      = ok.withTextContent(content)
  def ok(content: HttpContent): Response = ok.withContent(content)
  def created: Response                  = Response(HttpStatus.Created_201)
  def accepted: Response                 = Response(HttpStatus.Accepted_202)
  def noContent: Response                = Response(HttpStatus.NoContent_204)

  // 3xx Redirection responses
  def redirect(location: String): Response = Response(HttpStatus.Found_302).withLocation(location)

  def movedPermanently(location: String): Response = Response(HttpStatus.MovedPermanently_301)
    .withLocation(location)

  def seeOther(location: String): Response = Response(HttpStatus.SeeOther_303).withLocation(
    location
  )

  def temporaryRedirect(location: String): Response = Response(HttpStatus.TemporaryRedirect_307)
    .withLocation(location)

  def permanentRedirect(location: String): Response = Response(HttpStatus.PermanentRedirect_308)
    .withLocation(location)

  def notModified: Response = Response(HttpStatus.NotModified_304)

  // 4xx Client error responses
  def badRequest: Response                  = Response(HttpStatus.BadRequest_400)
  def badRequest(message: String): Response = badRequest.withTextContent(message)
  def unauthorized: Response                = Response(HttpStatus.Unauthorized_401)
  def forbidden: Response                   = Response(HttpStatus.Forbidden_403)
  def notFound: Response                    = Response(HttpStatus.NotFound_404)
  def notFound(message: String): Response   = notFound.withTextContent(message)
  def methodNotAllowed: Response            = Response(HttpStatus.MethodNotAllowed_405)
  def conflict: Response                    = Response(HttpStatus.Conflict_409)
  def gone: Response                        = Response(HttpStatus.Gone_410)
  def unprocessableEntity: Response         = Response(HttpStatus.UnprocessableEntity_422)
  def tooManyRequests: Response             = Response(HttpStatus.TooManyRequests_429)

  // 5xx Server error responses
  def internalServerError: Response                  = Response(HttpStatus.InternalServerError_500)
  def internalServerError(message: String): Response = internalServerError.withTextContent(message)

  def notImplemented: Response     = Response(HttpStatus.NotImplemented_501)
  def badGateway: Response         = Response(HttpStatus.BadGateway_502)
  def serviceUnavailable: Response = Response(HttpStatus.ServiceUnavailable_503)
  def gatewayTimeout: Response     = Response(HttpStatus.GatewayTimeout_504)

  /**
    * Create an SSE event stream response with the given events
    */
  def eventStream(events: Rx[ServerSentEvent]): Response = Response(HttpStatus.Ok_200)
    .setHeader(HttpHeader.ContentType, ContentType.TextEventStream.toString)
    .withEvents(events)

end Response
