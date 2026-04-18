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

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.util.URLEncoder

/**
  * An immutable HTTP request representation
  */
case class Request(
    method: HttpMethod,
    uri: String,
    headers: HttpMultiMap = HttpMultiMap.empty,
    content: HttpContent = HttpContent.Empty,
    queryParams: Map[String, Seq[String]] = Map.empty,
    eventHandler: ServerSentEventHandler = ServerSentEventHandler.empty
):

  def path: String =
    val idx = uri.indexOf('?')
    if idx >= 0 then
      uri.substring(0, idx)
    else
      uri

  def query: Option[String] =
    val idx = uri.indexOf('?')
    if idx >= 0 then
      Some(uri.substring(idx + 1))
    else
      None

  def host: Option[String]      = headers.get(HttpHeader.Host)
  def userAgent: Option[String] = headers.get(HttpHeader.UserAgent)

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

  def getQueryParam(name: String): Option[String] = queryParams.get(name).flatMap(_.headOption)

  def getQueryParams(name: String): Seq[String] = queryParams.getOrElse(name, Seq.empty)

  def fullUri: String =
    if queryParams.isEmpty then
      uri
    else
      val queryString = queryParams
        .flatMap { case (k, vs) =>
          vs.map(v => s"${URLEncoder.encode(k)}=${URLEncoder.encode(v)}")
        }
        .mkString("&")
      val separator =
        if uri.contains("?") then
          "&"
        else
          "?"
      s"${uri}${separator}${queryString}"

  // Builder methods
  def withMethod(m: HttpMethod): Request                         = copy(method = m)
  def withUri(u: String): Request                                = copy(uri = u)
  def withHeaders(h: HttpMultiMap): Request                      = copy(headers = h)
  def withContent(c: HttpContent): Request                       = copy(content = c)
  def withQueryParams(params: Map[String, Seq[String]]): Request = copy(queryParams = params)

  def addHeader(name: String, value: String): Request = copy(headers = headers.add(name, value))

  def setHeader(name: String, value: String): Request = copy(headers = headers.set(name, value))

  def removeHeader(name: String): Request = copy(headers = headers.remove(name))

  def addQueryParam(name: String, value: String): Request =
    val existing = queryParams.getOrElse(name, Seq.empty)
    copy(queryParams = queryParams + (name -> (existing :+ value)))

  def setQueryParam(name: String, value: String): Request = copy(queryParams =
    queryParams + (name -> Seq(value))
  )

  def setQueryParam(name: String, values: Seq[String]): Request = copy(queryParams =
    queryParams + (name -> values)
  )

  def withTextContent(text: String): Request = copy(content = HttpContent.text(text))

  def withJsonContent(json: JSONValue): Request = copy(content = HttpContent.json(json))

  def withJsonContent(json: String): Request = copy(content = HttpContent.json(json))

  def withBytesContent(bytes: Array[Byte]): Request = copy(content = HttpContent.bytes(bytes))

  def withMultipartContent(mp: Multipart): Request = copy(content = HttpContent.multipart(mp))

  def withContentType(ct: ContentType): Request = setHeader(HttpHeader.ContentType, ct.toString)

  def withHost(host: String): Request = setHeader(HttpHeader.Host, host)

  def withUserAgent(ua: String): Request = setHeader(HttpHeader.UserAgent, ua)

  def withAccept(accept: String): Request = setHeader(HttpHeader.Accept, accept)

  def withAuthorization(auth: String): Request = setHeader(HttpHeader.Authorization, auth)

  def withBearerToken(token: String): Request = withAuthorization(s"Bearer ${token}")

  def withBasicAuth(username: String, password: String): Request =
    val encoded = wvlet.uni.util.Base64.encodeToString(s"${username}:${password}")
    withAuthorization(s"Basic ${encoded}")

  def withEventHandler(handler: ServerSentEventHandler): Request = copy(eventHandler = handler)

  def withAcceptEventStream: Request = withAccept(ContentType.TextEventStream.value)

end Request

object Request:
  def apply(method: HttpMethod, uri: String): Request = Request(
    method,
    uri,
    HttpMultiMap.empty,
    HttpContent.Empty,
    Map.empty,
    ServerSentEventHandler.empty
  )

  def get(uri: String): Request     = Request(HttpMethod.GET, uri)
  def post(uri: String): Request    = Request(HttpMethod.POST, uri)
  def put(uri: String): Request     = Request(HttpMethod.PUT, uri)
  def delete(uri: String): Request  = Request(HttpMethod.DELETE, uri)
  def patch(uri: String): Request   = Request(HttpMethod.PATCH, uri)
  def head(uri: String): Request    = Request(HttpMethod.HEAD, uri)
  def options(uri: String): Request = Request(HttpMethod.OPTIONS, uri)
