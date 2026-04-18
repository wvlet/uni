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

import java.io.ByteArrayOutputStream
import scala.util.Random

/**
  * A single part in a multipart body. See [[Multipart]] for the container.
  */
sealed trait MultipartPart:
  def name: String
  def headers: HttpMultiMap
  def bodyBytes: Array[Byte]
  def contentType: Option[ContentType]
  def filename: Option[String]

object MultipartPart:

  /**
    * A simple form field (name + text value). Serialized as UTF-8 bytes without an explicit
    * Content-Type header.
    */
  case class FormField(name: String, value: String, headers: HttpMultiMap = HttpMultiMap.empty)
      extends MultipartPart:
    def bodyBytes: Array[Byte]           = value.getBytes("UTF-8")
    def contentType: Option[ContentType] = None
    def filename: Option[String]         = None

  /**
    * A file part with a filename and explicit content type.
    */
  case class FilePart(
      name: String,
      fileName: String,
      bytes: Array[Byte],
      partContentType: ContentType = ContentType.ApplicationOctetStream,
      headers: HttpMultiMap = HttpMultiMap.empty
  ) extends MultipartPart:
    def bodyBytes: Array[Byte]           = bytes
    def contentType: Option[ContentType] = Some(partContentType)
    def filename: Option[String]         = Some(fileName)

end MultipartPart

/**
  * A multipart message body (RFC 7578 when subtype is `form-data`). Parts are held in memory; the
  * encoded bytes are produced lazily on demand.
  *
  * Construct via [[Multipart.builder]].
  */
case class Multipart(
    boundary: String,
    parts: List[MultipartPart],
    subtype: String = Multipart.FormDataSubtype
):

  def contentType: ContentType = ContentType(s"multipart/${subtype}").withBoundary(boundary)

  def encode: Array[Byte] = Multipart.encode(this)

end Multipart

object Multipart:

  val FormDataSubtype: String = "form-data"

  private val Crlf: Array[Byte]        = "\r\n".getBytes("UTF-8")
  private val DoubleDash: Array[Byte]  = "--".getBytes("UTF-8")
  private val BoundaryPrefix: String   = "----uni-"
  private val BoundaryRandomBytes: Int = 12
  private val HexChars: Array[Char]    = "0123456789abcdef".toCharArray

  def builder(): Builder = Builder()

  /**
    * Generate a random boundary string unlikely to collide with part content.
    */
  def generateBoundary(): String =
    val buf = StringBuilder(BoundaryPrefix.length + BoundaryRandomBytes * 2)
    buf.append(BoundaryPrefix)
    val rnd = Random
    var i   = 0
    while i < BoundaryRandomBytes do
      val b = rnd.nextInt(256)
      buf.append(HexChars((b >>> 4) & 0xf))
      buf.append(HexChars(b & 0xf))
      i += 1
    buf.result()

  /**
    * Encode a [[Multipart]] into its RFC 7578 byte representation.
    */
  def encode(mp: Multipart): Array[Byte] =
    val out      = ByteArrayOutputStream()
    val boundary = mp.boundary.getBytes("UTF-8")
    mp.parts
      .foreach { part =>
        out.write(DoubleDash)
        out.write(boundary)
        out.write(Crlf)
        writePartHeaders(out, part)
        out.write(Crlf)
        out.write(part.bodyBytes)
        out.write(Crlf)
      }
    out.write(DoubleDash)
    out.write(boundary)
    out.write(DoubleDash)
    out.write(Crlf)
    out.toByteArray

  private def writePartHeaders(out: ByteArrayOutputStream, part: MultipartPart): Unit =
    validateHeaderValue("name", part.name)
    val disposition = StringBuilder()
    disposition.append("form-data; name=\"").append(escapeQuotes(part.name)).append('"')
    part
      .filename
      .foreach { fn =>
        validateHeaderValue("filename", fn)
        disposition.append("; filename=\"").append(escapeQuotes(fn)).append('"')
      }
    writeHeaderLine(out, HttpHeader.ContentDisposition, disposition.result())
    part.contentType.foreach(ct => writeHeaderLine(out, HttpHeader.ContentType, ct.value))
    part
      .headers
      .entries
      .foreach { case (k, v) =>
        if !k.equalsIgnoreCase(HttpHeader.ContentDisposition) &&
          !k.equalsIgnoreCase(HttpHeader.ContentType)
        then
          writeHeaderLine(out, k, v)
      }

  private def writeHeaderLine(out: ByteArrayOutputStream, name: String, value: String): Unit =
    out.write(s"${name}: ${value}".getBytes("UTF-8"))
    out.write(Crlf)

  private def escapeQuotes(s: String): String = s.replace("\"", "\\\"")

  private def validateHeaderValue(field: String, value: String): Unit =
    if value.contains('\r') || value.contains('\n') then
      throw IllegalArgumentException(s"multipart ${field} must not contain CR or LF: ${value}")

  final class Builder private[Multipart] ():
    private var boundaryOpt: Option[String]                               = None
    private var subtype: String                                           = FormDataSubtype
    private val parts: scala.collection.mutable.ListBuffer[MultipartPart] =
      scala.collection.mutable.ListBuffer.empty

    def addField(name: String, value: String): Builder =
      parts += MultipartPart.FormField(name, value)
      this

    def addField(name: String, value: String, headers: HttpMultiMap): Builder =
      parts += MultipartPart.FormField(name, value, headers)
      this

    def addFile(name: String, filename: String, bytes: Array[Byte]): Builder =
      parts += MultipartPart.FilePart(name, filename, bytes)
      this

    def addFile(
        name: String,
        filename: String,
        bytes: Array[Byte],
        contentType: ContentType
    ): Builder =
      parts += MultipartPart.FilePart(name, filename, bytes, contentType)
      this

    def addPart(part: MultipartPart): Builder =
      parts += part
      this

    def withBoundary(boundary: String): Builder =
      boundaryOpt = Some(boundary)
      this

    def withSubtype(subtype: String): Builder =
      this.subtype = subtype
      this

    def build(): Multipart = Multipart(
      boundary = boundaryOpt.getOrElse(generateBoundary()),
      parts = parts.toList,
      subtype = subtype
    )

  end Builder

end Multipart
