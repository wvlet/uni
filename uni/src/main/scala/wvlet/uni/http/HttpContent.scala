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

import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.util.Utf8

/**
  * Represents the body content of an HTTP message
  */
sealed trait HttpContent:
  def isEmpty: Boolean
  def nonEmpty: Boolean = !isEmpty
  def contentType: Option[ContentType]
  def length: Long

  def asString: Option[String]
  def asBytes: Option[Array[Byte]]

  // Direct accessors that return values instead of Option
  def toContentString: String
  def toContentBytes: Array[Byte]
  def contentHash: Int

object HttpContent:

  case object Empty extends HttpContent:
    def isEmpty: Boolean                 = true
    def contentType: Option[ContentType] = None
    def length: Long                     = 0
    def asString: Option[String]         = None
    def asBytes: Option[Array[Byte]]     = None
    def toContentString: String          = ""
    def toContentBytes: Array[Byte]      = Array.empty[Byte]
    def contentHash: Int                 = 0

  case class TextContent(
      text: String,
      override val contentType: Option[ContentType] = Some(ContentType.TextPlain)
  ) extends HttpContent:
    def isEmpty: Boolean                     = text.isEmpty
    private lazy val bytesCache: Array[Byte] = Utf8.encode(text)
    val length: Long                         = bytesCache.length.toLong
    def asString: Option[String]             = Some(text)
    def asBytes: Option[Array[Byte]]         = Some(bytesCache)
    def toContentString: String              = text
    def toContentBytes: Array[Byte]          = bytesCache
    def contentHash: Int                     = java.util.Arrays.hashCode(bytesCache)

  case class ByteContent(
      bytes: Array[Byte],
      override val contentType: Option[ContentType] = Some(ContentType.ApplicationOctetStream)
  ) extends HttpContent:
    def isEmpty: Boolean             = bytes.isEmpty
    def length: Long                 = bytes.length.toLong
    def asString: Option[String]     = Some(Utf8.decode(bytes))
    def asBytes: Option[Array[Byte]] = Some(bytes)
    def toContentString: String      = Utf8.decode(bytes)
    def toContentBytes: Array[Byte]  = bytes
    def contentHash: Int             = java.util.Arrays.hashCode(bytes)

  case class JsonContent(
      json: JSONValue,
      override val contentType: Option[ContentType] = Some(ContentType.ApplicationJson)
  ) extends HttpContent:
    def isEmpty: Boolean                     = false
    private lazy val jsonString: String      = json.toJSON
    private lazy val bytesCache: Array[Byte] = Utf8.encode(jsonString)
    val length: Long                         = bytesCache.length.toLong
    def asString: Option[String]             = Some(jsonString)
    def asBytes: Option[Array[Byte]]         = Some(bytesCache)
    def toContentString: String              = jsonString
    def toContentBytes: Array[Byte]          = bytesCache
    def contentHash: Int                     = java.util.Arrays.hashCode(bytesCache)

  case class MultipartContent(multipart: Multipart) extends HttpContent:
    private lazy val encoded: Array[Byte] = multipart.encode
    def isEmpty: Boolean                  = false
    def length: Long                      = encoded.length.toLong
    def contentType: Option[ContentType]  = Some(multipart.contentType)
    def asString: Option[String]          = Some(String(encoded, "UTF-8"))
    def asBytes: Option[Array[Byte]]      = Some(encoded)
    def toContentString: String           = String(encoded, "UTF-8")
    def toContentBytes: Array[Byte]       = encoded
    def contentHash: Int                  = java.util.Arrays.hashCode(encoded)

  /**
    * `length` bytes of the file at `path`, starting at `offset`, read when the body is sent. Server
    * backends (Netty, Node.js, Scala Native) stream it from the file in bounded chunks, so a large
    * file is never held in memory. `asBytes` and `toContentBytes` read the range on each call, for
    * code that needs the bytes themselves.
    *
    * The file must not shrink while it is sent: a backend that reaches its end early closes the
    * connection, as the response can no longer meet its `Content-Length`.
    */
  case class FileContent(
      path: IOPath,
      offset: Long,
      length: Long,
      override val contentType: Option[ContentType] = Some(ContentType.ApplicationOctetStream)
  ) extends HttpContent:
    require(
      offset >= 0 && length >= 0,
      s"offset and length must be non-negative: ${offset}, ${length}"
    )

    def isEmpty: Boolean             = length == 0
    def asString: Option[String]     = Some(toContentString)
    def asBytes: Option[Array[Byte]] = Some(toContentBytes)
    def toContentString: String      = String(toContentBytes, "UTF-8")
    def toContentBytes: Array[Byte]  = StaticFilesCompat.readRange(path, offset, length)
    // A hash of the identity, not the bytes, so it needs no read
    def contentHash: Int = (path, offset, length).hashCode

  /**
    * A request body read as it arrives, for requests the server streams (see
    * `HttpServerConfig.withStreamingRequests`). Read it with [[openStream]], e.g. to copy a large
    * upload to a file without holding it in memory.
    *
    * `length` is the `Content-Length`, or -1 for a chunked body of unknown size. The stream is
    * readable from any thread until the response is sent; later reads throw `IOException`. A read
    * also throws when the body breaks off or exceeds the server's limit.
    *
    * [[toContentBytes]] (and `asBytes`, `toContentString`) read the bytes not yet read and keep
    * them, so a filter that calls it leaves the body for the handler: a later [[openStream]] reads
    * the kept bytes.
    */
  final class StreamContent(
      in: java.io.InputStream,
      val length: Long,
      override val contentType: Option[ContentType]
  ) extends HttpContent:
    private var kept: Array[Byte] = null

    /** The body's bytes as a stream. */
    def openStream: java.io.InputStream = synchronized {
      if kept != null then
        java.io.ByteArrayInputStream(kept)
      else
        in
    }

    def isEmpty: Boolean             = length == 0
    def asString: Option[String]     = Some(toContentString)
    def asBytes: Option[Array[Byte]] = Some(toContentBytes)
    def toContentString: String      = Utf8.decode(toContentBytes)

    def toContentBytes: Array[Byte] = synchronized {
      if kept == null then
        val out = java.io.ByteArrayOutputStream()
        val buf = new Array[Byte](8192)
        var n   = in.read(buf)
        while n >= 0 do
          out.write(buf, 0, n)
          n = in.read(buf)
        kept = out.toByteArray
      kept
    }

    // A hash of the identity, not the bytes, so it reads nothing
    def contentHash: Int          = System.identityHashCode(this)
    override def toString: String = s"StreamContent(length=${length})"

  end StreamContent

  /**
    * Thrown by a [[StreamContent]] read when the client's body breaks off: malformed framing (400),
    * a timeout (408), or a size over the limit (413). The server answers with `status`.
    */
  class RequestBodyException(val status: HttpStatus, message: String)
      extends java.io.IOException(message)

  /** A body read from `in`, as the server passes a streamed request body to its handler. */
  def stream(
      in: java.io.InputStream,
      length: Long,
      contentType: Option[ContentType] = Some(ContentType.ApplicationOctetStream)
  ): StreamContent = StreamContent(in, length, contentType)

  /** The content type of a request body from its headers, as [[fromBytes]] sets it. */
  private[http] def contentTypeOf(headers: HttpMultiMap): ContentType = headers
    .get(HttpHeader.ContentType)
    .flatMap(ContentType.parse)
    .getOrElse(ContentType.ApplicationOctetStream)

  def empty: HttpContent = Empty

  def text(s: String): HttpContent =
    if s.isEmpty then
      Empty
    else
      TextContent(s)

  def text(s: String, contentType: ContentType): HttpContent =
    if s.isEmpty then
      Empty
    else
      TextContent(s, Some(contentType))

  def bytes(b: Array[Byte]): HttpContent =
    if b.isEmpty then
      Empty
    else
      ByteContent(b)

  def bytes(b: Array[Byte], contentType: ContentType): HttpContent =
    if b.isEmpty then
      Empty
    else
      ByteContent(b, Some(contentType))

  /**
    * Build byte content from a request/response body and its headers, resolving the content type
    * from the `Content-Type` header (falling back to `application/octet-stream`). Shared by HTTP
    * server backends when converting an inbound request body.
    */
  def fromBytes(data: Array[Byte], headers: HttpMultiMap): HttpContent =
    if data.isEmpty then
      Empty
    else
      ByteContent(data, Some(contentTypeOf(headers)))

  def json(j: JSONValue): HttpContent = JsonContent(j)

  def multipart(mp: Multipart): HttpContent = MultipartContent(mp)

  def json(s: String): HttpContent = TextContent(s, Some(ContentType.ApplicationJson))

  def html(s: String): HttpContent = TextContent(s, Some(ContentType.TextHtml))

  def xml(s: String): HttpContent = TextContent(s, Some(ContentType.ApplicationXml))

end HttpContent
