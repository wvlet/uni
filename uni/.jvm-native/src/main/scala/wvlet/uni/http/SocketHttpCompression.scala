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
import java.util.zip.{Deflater, GZIPInputStream, Inflater, InflaterInputStream}

/**
  * Request body decoding for the socket server, as uni-netty's `NettyRequestDecompression` does: a
  * body sent with `Content-Encoding` gzip or deflate (or the x- aliases) is decoded before the host
  * check, filters, and handler see it, and the decoded size is capped, so a small compressed body
  * can't expand into gigabytes (a zip bomb). Other encodings pass through unchanged.
  */
private[http] object RequestDecompression:

  /**
    * The most bytes one Inflater call reads or writes, through both the decoder's input buffer and
    * `readCapped`'s chunk. Keep it under 64 KiB: Scala Native's javalib (0.5.12) declares zlib's
    * `avail_in` and `avail_out` as 16-bit on Windows, where they are 32-bit, so a larger call is
    * cut to its size mod 65536 there and decodes the wrong bytes.
    */
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
            val compressed =
              if encoding == "gzip" then
                compress(gzipPool, body)
              else
                compress(deflatePool, body)
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
            response.copy(headers = headers, content = HttpContent.bytes(compressed))
      end if
    end if

  end encode

  /**
    * A reusable compressor: a `Deflater` and an output buffer. Creating a `Deflater` per response
    * sets up a fresh zlib state (about 256 KB of native memory) and frees it again, which costs
    * more CPU than compressing a small body; `reset()` keeps the state.
    */
  private final class Compressor(val framed: Boolean):
    // gzip matches GZIPOutputStream (raw deflate at the default level, framed below); deflate
    // matches DeflaterOutputStream with a zlib-wrapped Deflater at Level
    val deflater =
      if framed then
        Deflater(Deflater.DEFAULT_COMPRESSION, true)
      else
        Deflater(Level)

    var out: Array[Byte] = new Array[Byte](4096)
    def dispose(): Unit  = deflater.end()

  private val gzipPool    = ScratchPool(ScratchPool.DefaultMax, () => Compressor(framed = true))
  private val deflatePool = ScratchPool(ScratchPool.DefaultMax, () => Compressor(framed = false))

  // A buffer grown past this isn't kept
  private final val MaxKeptBytes = 1 << 16

  private def compress(pool: ScratchPool[Compressor], body: Array[Byte]): Array[Byte] =
    val c = pool.borrow()
    try
      val d = c.deflater
      d.reset()
      d.setInput(body)
      d.finish()
      // The gzip header the JDK's GZIPOutputStream writes: magic, deflate, no flags, no mtime, no
      // extra flags, OS 255 (unknown)
      var pos =
        if c.framed then
          10
        else
          0
      if c.framed then
        java.util.Arrays.fill(c.out, 0, 10, 0.toByte)
        c.out(0) = 0x1f.toByte
        c.out(1) = 0x8b.toByte
        c.out(2) = Deflater.DEFLATED.toByte
        c.out(9) = 0xff.toByte
      while !d.finished() do
        if pos == c.out.length then
          c.out = java.util.Arrays.copyOf(c.out, c.out.length * 2)
        pos += d.deflate(c.out, pos, c.out.length - pos)
      if c.framed then
        // Trailer: CRC-32 and the input size mod 2^32, little-endian
        if pos + 8 > c.out.length then
          c.out = java.util.Arrays.copyOf(c.out, pos + 8)
        writeIntLE(c.out, pos, GzipCrc.of(body))
        writeIntLE(c.out, pos + 4, body.length)
        pos += 8
      val result = java.util.Arrays.copyOf(c.out, pos)
      if c.out.length > MaxKeptBytes then
        c.out = new Array[Byte](4096)
      result
    finally
      if !pool.release(c) then
        c.dispose()
    end try

  end compress

  private def writeIntLE(a: Array[Byte], at: Int, v: Int): Unit =
    a(at) = v.toByte
    a(at + 1) = (v >>> 8).toByte
    a(at + 2) = (v >>> 16).toByte
    a(at + 3) = (v >>> 24).toByte

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
        // indexOf, not split(';'): on Scala Native that compiles a regex for every item
        val semi = item.indexOf(';')
        val name =
          if semi < 0 then
            item.trim
          else
            item.substring(0, semi).trim
        val q = qValue(item, semi)
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

  /** The first `q=` parameter after `semi` in an Accept-Encoding item: 1.0 if none, 0 if invalid */
  private def qValue(item: String, semi: Int): Double =
    var from = semi + 1
    var q    = 1.0
    var done = semi < 0
    while !done do
      val next  = item.indexOf(';', from)
      val until =
        if next < 0 then
          item.length
        else
          next
      val param = item.substring(from, until).trim
      if param.startsWith("q=") then
        q = param.substring(2).toDoubleOption.getOrElse(0.0)
        done = true
      else if next < 0 then
        done = true
      else
        from = next + 1
    q

end ResponseCompression
