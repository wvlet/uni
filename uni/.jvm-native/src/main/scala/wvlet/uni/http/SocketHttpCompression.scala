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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.util.zip.{
  Deflater,
  DeflaterOutputStream,
  GZIPInputStream,
  GZIPOutputStream,
  Inflater,
  InflaterInputStream
}

/**
  * Request body decoding for the socket server, as uni-netty's `NettyRequestDecompression` does: a
  * body sent with `Content-Encoding` gzip or deflate (or the x- aliases) is decoded before the host
  * check, filters, and handler see it, and the decoded size is capped, so a small compressed body
  * can't expand into gigabytes (a zip bomb). Other encodings pass through unchanged.
  */
private[http] object RequestDecompression:

  private final val BufferSize = 8192

  /**
    * The request with its body decoded, or the response that rejects it: 413 over `maxSize`, 400
    * for a malformed body. Both close the connection.
    */
  def decode(request: Request, maxSize: Int): Either[Response, Request] =
    val encodings = HttpConnectionReader.tokens(request.headers.getAll(HttpHeader.ContentEncoding))
    encodings match
      case Seq(encoding @ ("gzip" | "x-gzip" | "deflate" | "x-deflate")) =>
        val body = request.content.toContentBytes
        if body.isEmpty then
          Right(request.removeHeader(HttpHeader.ContentEncoding))
        else
          val gzip     = encoding.endsWith("gzip")
          val inflater =
            if gzip then
              null
            else
              Inflater(!isZlibWrapped(body))
          try
            val in = open(body, inflater)
            try
              readCapped(in, maxSize) match
                case None =>
                  Left(
                    Response(HttpStatus.PayloadTooLarge_413).withTextContent(
                      "Decompressed request body too large"
                    )
                  )
                case Some(decoded) =>
                  val headers = request
                    .headers
                    .remove(HttpHeader.ContentEncoding)
                    .set(HttpHeader.ContentLength, decoded.length.toString)
                  Right(
                    request.copy(
                      headers = headers,
                      content = HttpContent.fromBytes(decoded, headers)
                    )
                  )
            finally
              in.close()
              // A stream given its own Inflater doesn't free it, and Native has no finalizers
              if inflater != null then
                inflater.end()
          catch
            case _: IOException =>
              Left(Response.badRequest("Malformed compressed request body"))
        end if
      case _ =>
        Right(request)
    end match

  end decode

  /**
    * A decoder of `body`. deflate accepts both zlib-wrapped and raw data, as Netty's `ZLIB_OR_NONE`
    * does, because clients send either.
    */
  private def open(body: Array[Byte], inflater: Inflater): InputStream =
    val in = ByteArrayInputStream(body)
    if inflater == null then
      GZIPInputStream(in, BufferSize)
    else
      InflaterInputStream(in, inflater, BufferSize)

  private def isZlibWrapped(body: Array[Byte]): Boolean =
    body.length >= 2 &&
      (body(0) & 0x0f) == 8 &&
      (((body(0) & 0xff) << 8) | (body(1) & 0xff)) % 31 == 0

  /** All bytes of `in`, or None once they exceed `maxSize`. */
  private def readCapped(in: InputStream, maxSize: Int): Option[Array[Byte]] =
    val out   = ByteArrayOutputStream()
    val chunk = new Array[Byte](BufferSize)
    var total = 0L
    var n     = in.read(chunk)
    while n >= 0 do
      total += n
      if total > maxSize then
        return None
      out.write(chunk, 0, n)
      n = in.read(chunk)
    Some(out.toByteArray)

end RequestDecompression

/**
  * Response body compression for the socket server, as uni-netty's `IdentityRangeCompressor`
  * (Netty's `HttpContentCompressor`) does: gzip or deflate, chosen from `Accept-Encoding`.
  */
private[http] object ResponseCompression:

  // Smaller bodies would grow: gzip adds about 20 bytes of framing
  private[http] final val MinSize = 1024

  // Netty's default compression level
  private final val Level = 6

  /**
    * The response compressed for the request's `Accept-Encoding`, or the response itself. Bodies
    * that must not change are left alone: 1xx, 204, 304, and `206 Partial Content` (its
    * Content-Range counts identity bytes), and bodies that already have a `Content-Encoding`. A
    * compressed body is a different representation, so a strong ETag becomes weak, as nginx does.
    */
  def encode(request: Request, response: Response): Response =
    val code = response.status.code
    // Most API clients send no Accept-Encoding, so check it before touching the body
    if !request.headers.contains(HttpHeader.AcceptEncoding) || code < 200 || code == 204 ||
      code == 206 || code == 304 || response.headers.contains(HttpHeader.ContentEncoding)
    then
      response
    else
      val body = response.content.toContentBytes
      if body.length < MinSize then
        response
      else
        chooseEncoding(request.headers.getAll(HttpHeader.AcceptEncoding)) match
          case None =>
            response
          case Some(encoding) =>
            val out = ByteArrayOutputStream(body.length / 2)
            if encoding == "gzip" then
              val enc = GZIPOutputStream(out)
              enc.write(body)
              enc.close()
            else
              val deflater = Deflater(Level)
              try
                val enc = DeflaterOutputStream(out, deflater)
                enc.write(body)
                enc.close()
              finally
                deflater.end()
            val contentType = response
              .headers
              .get(HttpHeader.ContentType)
              .orElse(response.content.contentType.map(_.value))
            var headers = response.headers.set(HttpHeader.ContentEncoding, encoding)
            contentType.foreach(ct => headers = headers.set(HttpHeader.ContentType, ct))
            if !HttpConnectionReader
                .tokens(response.headers.getAll(HttpHeader.Vary))
                .exists(v => v == "accept-encoding" || v == "*")
            then
              headers = headers.add(HttpHeader.Vary, HttpHeader.AcceptEncoding)
            response
              .headers
              .get(HttpHeader.ETag)
              .filter(_.startsWith("\""))
              .foreach(tag => headers = headers.set(HttpHeader.ETag, s"W/${tag}"))
            response.copy(headers = headers, content = HttpContent.bytes(out.toByteArray))
      end if
    end if

  end encode

  /**
    * gzip or deflate by their q-values, as Netty's `HttpContentCompressor.determineEncoding` picks
    * them: the higher one wins and gzip wins a tie; `*` stands for whichever of gzip, then deflate,
    * is not listed. None when neither is acceptable.
    */
  private[http] def chooseEncoding(acceptEncoding: Seq[String]): Option[String] =
    var gzipQ = -1.0
    var defQ  = -1.0
    var starQ = -1.0
    HttpConnectionReader
      .tokens(acceptEncoding)
      .foreach { item =>
        val parts = item.split(';')
        val name  = parts(0).trim
        val q     = parts
          .drop(1)
          .map(_.trim)
          .collectFirst {
            case p if p.startsWith("q=") =>
              p.drop(2).toDoubleOption.getOrElse(0.0)
          }
          .getOrElse(1.0)
        name match
          case "gzip" | "x-gzip" =>
            gzipQ = math.max(gzipQ, q)
          case "deflate" | "x-deflate" =>
            defQ = math.max(defQ, q)
          case "*" =>
            starQ = math.max(starQ, q)
          case _ =>
            ()
      }
    if gzipQ > 0 || defQ > 0 then
      if gzipQ >= defQ then
        Some("gzip")
      else
        Some("deflate")
    else if starQ > 0 && gzipQ < 0 then
      Some("gzip")
    else if starQ > 0 && defQ < 0 then
      Some("deflate")
    else
      None

  end chooseEncoding

end ResponseCompression
