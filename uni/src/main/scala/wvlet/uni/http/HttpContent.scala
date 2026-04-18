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
    private lazy val bytesCache: Array[Byte] = text.getBytes("UTF-8")
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
    def asString: Option[String]     = Some(String(bytes, "UTF-8"))
    def asBytes: Option[Array[Byte]] = Some(bytes)
    def toContentString: String      = String(bytes, "UTF-8")
    def toContentBytes: Array[Byte]  = bytes
    def contentHash: Int             = java.util.Arrays.hashCode(bytes)

  case class JsonContent(
      json: JSONValue,
      override val contentType: Option[ContentType] = Some(ContentType.ApplicationJson)
  ) extends HttpContent:
    def isEmpty: Boolean                     = false
    private lazy val jsonString: String      = json.toJSON
    private lazy val bytesCache: Array[Byte] = jsonString.getBytes("UTF-8")
    val length: Long                         = bytesCache.length.toLong
    def asString: Option[String]             = Some(jsonString)
    def asBytes: Option[Array[Byte]]         = Some(bytesCache)
    def toContentString: String              = jsonString
    def toContentBytes: Array[Byte]          = bytesCache
    def contentHash: Int                     = java.util.Arrays.hashCode(bytesCache)

  case class MultipartContent(multipart: Multipart) extends HttpContent:
    private lazy val encoded: Array[Byte] = multipart.encode
    def isEmpty: Boolean                  = multipart.parts.isEmpty
    def length: Long                      = encoded.length.toLong
    def contentType: Option[ContentType]  = Some(multipart.contentType)
    def asString: Option[String]          = Some(String(encoded, "UTF-8"))
    def asBytes: Option[Array[Byte]]      = Some(encoded)
    def toContentString: String           = String(encoded, "UTF-8")
    def toContentBytes: Array[Byte]       = encoded
    def contentHash: Int                  = java.util.Arrays.hashCode(encoded)

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

  def json(j: JSONValue): HttpContent = JsonContent(j)

  def multipart(mp: Multipart): HttpContent =
    if mp.parts.isEmpty then
      Empty
    else
      MultipartContent(mp)

  def json(s: String): HttpContent = TextContent(s, Some(ContentType.ApplicationJson))

  def html(s: String): HttpContent = TextContent(s, Some(ContentType.TextHtml))

  def xml(s: String): HttpContent = TextContent(s, Some(ContentType.ApplicationXml))

end HttpContent
