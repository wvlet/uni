package wvlet.uni.parquet

import wvlet.uni.http.Http
import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.http.HttpHeader
import wvlet.uni.http.HttpSyncClient
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.io.IOPath
import wvlet.uni.rx.Rx

/**
  * Random access to the bytes of a Parquet file, read asynchronously. Browser files and HTTP on
  * Scala.js are only available this way.
  */
trait AsyncByteSource extends AutoCloseable:
  /** A name for error messages, such as the file path or URL */
  def name: String

  def readAsync(offset: Long, length: Int): Rx[Array[Byte]]

  /**
    * The last `length` bytes (fewer if the file is shorter) together with the file size. Parquet
    * keeps its footer at the end, so opening a file starts here; HTTP sources answer it with one
    * request.
    */
  def readTailAsync(length: Int): Rx[(Array[Byte], Long)]

  override def close(): Unit = ()

/** Random access to the bytes of a Parquet file, read synchronously (JVM, Native, Node.js). */
trait ByteSource extends AsyncByteSource:
  def size: Long
  def read(offset: Long, length: Int): Array[Byte]

  def readTail(length: Int): (Array[Byte], Long) =
    val total = size
    val n     = math.min(total, length.toLong).toInt
    (read(total - n, n), total)

  override def readAsync(offset: Long, length: Int): Rx[Array[Byte]] = Rx.single(
    read(offset, length)
  )

  override def readTailAsync(length: Int): Rx[(Array[Byte], Long)] = Rx.single(readTail(length))

object ByteSource:
  /** A Parquet file held in memory */
  def ofBytes(bytes: Array[Byte], name: String = "bytes"): ByteSource = BytesSource(bytes, name)

  /** A local file (JVM, Scala Native, and Node.js) */
  def ofFile(path: IOPath): ByteSource = FileSource.open(path)

  def ofFile(path: String): ByteSource = ofFile(IOPath.parse(path))

  /**
    * A file served over HTTP, read with Range requests through a synchronous client (JVM, Scala
    * Native, and Node.js).
    */
  def ofUrl(url: String, config: HttpClientConfig = Http.client): ByteSource = HttpRangeSource(
    url,
    config.newSyncClient
  )

  /** A file served over HTTP, read with Range requests through an asynchronous client */
  def ofUrlAsync(url: String, config: HttpClientConfig = Http.client): AsyncByteSource =
    AsyncHttpRangeSource(url, config.newAsyncClient)

private[parquet] final class BytesSource(bytes: Array[Byte], val name: String) extends ByteSource:
  def size: Long                                   = bytes.length.toLong
  def read(offset: Long, length: Int): Array[Byte] =
    if offset < 0 || length < 0 || offset + length > bytes.length then
      throw ParquetException.corrupt(
        s"range [${offset}, ${offset + length}) is outside ${name} (${bytes.length} bytes)"
      )
    java.util.Arrays.copyOfRange(bytes, offset.toInt, offset.toInt + length)

/** Parses Range responses for the HTTP sources */
private[parquet] object HttpRange:
  def rangeHeader(offset: Long, length: Int): String = s"bytes=${offset}-${offset + length - 1}"

  def suffixHeader(length: Int): String = s"bytes=-${length}"

  /** The total size from `Content-Range: bytes a-b/total` */
  def totalSize(resp: Response): Option[Long] = resp
    .header(HttpHeader.ContentRange)
    .flatMap { v =>
      val slash = v.lastIndexOf('/')
      if slash >= 0 then
        v.substring(slash + 1).trim.toLongOption
      else
        None
    }

  def body(url: String, resp: Response): Array[Byte] =
    if !resp.isSuccessful then
      throw ParquetException(s"Failed to read ${url}: HTTP ${resp.statusCode} ${resp.reason}")
    resp.contentAsBytes.getOrElse(Array.emptyByteArray)

  /** Checks a ranged GET answer. A 200 means the server ignored the Range header. */
  def rangeBody(url: String, resp: Response, offset: Long, length: Int): Array[Byte] =
    val b = body(url, resp)
    if resp.statusCode == 206 then
      if b.length != length then
        throw ParquetException(
          s"${url}: asked for ${length} bytes at ${offset}, but the server sent ${b.length}"
        )
      b
    else if offset == 0 && b.length == length then
      b
    else
      throw ParquetException(
        s"${url} does not support HTTP Range requests (it answered ${resp
            .statusCode} with the whole file)"
      )

  /** Interprets the answer to a suffix range request */
  def tail(url: String, resp: Response, length: Int): Option[(Array[Byte], Long)] =
    if resp.statusCode == 206 then
      totalSize(resp).map(total => (body(url, resp), total))
    else if resp.statusCode == 200 then
      // The server sent the whole file
      val b = body(url, resp)
      val n = math.min(b.length, length)
      Some((java.util.Arrays.copyOfRange(b, b.length - n, b.length), b.length.toLong))
    else
      // 416 or other refusals: fall back to HEAD for the size
      None

  def contentLength(url: String, resp: Response): Long =
    if !resp.isSuccessful then
      throw ParquetException(s"Failed to read ${url}: HTTP ${resp.statusCode} ${resp.reason}")
    resp
      .header(HttpHeader.ContentLength)
      .flatMap(_.trim.toLongOption)
      .getOrElse(throw ParquetException(s"${url} did not report its size (no Content-Length)"))

end HttpRange

private[parquet] final class HttpRangeSource(url: String, client: HttpSyncClient)
    extends ByteSource:
  def name: String = url

  private var knownSize: Option[Long] = None

  def size: Long =
    knownSize match
      case Some(s) =>
        s
      case None =>
        val s = HttpRange.contentLength(url, client.send(Request.head(url)))
        knownSize = Some(s)
        s

  def read(offset: Long, length: Int): Array[Byte] =
    if length == 0 then
      Array.emptyByteArray
    else
      val resp = client.send(
        Request.get(url).addHeader(HttpHeader.Range, HttpRange.rangeHeader(offset, length))
      )
      HttpRange.rangeBody(url, resp, offset, length)

  override def readTail(length: Int): (Array[Byte], Long) =
    val resp = client.send(
      Request.get(url).addHeader(HttpHeader.Range, HttpRange.suffixHeader(length))
    )
    HttpRange.tail(url, resp, length) match
      case Some((bytes, total)) =>
        knownSize = Some(total)
        (bytes, total)
      case None =>
        super.readTail(length)

  override def close(): Unit = client.close()

end HttpRangeSource

private[parquet] final class AsyncHttpRangeSource(url: String, client: HttpAsyncClient)
    extends AsyncByteSource:
  def name: String = url

  // Rx.single defers the request until the stream runs
  private def send(req: Request): Rx[Response] = Rx.single(()).flatMap(_ => client.send(req))

  def readAsync(offset: Long, length: Int): Rx[Array[Byte]] =
    if length == 0 then
      Rx.single(Array.emptyByteArray)
    else
      send(Request.get(url).addHeader(HttpHeader.Range, HttpRange.rangeHeader(offset, length))).map(
        HttpRange.rangeBody(url, _, offset, length)
      )

  def readTailAsync(length: Int): Rx[(Array[Byte], Long)] = send(
    Request.get(url).addHeader(HttpHeader.Range, HttpRange.suffixHeader(length))
  ).flatMap { resp =>
    HttpRange.tail(url, resp, length) match
      case Some(t) =>
        Rx.single(t)
      case None =>
        send(Request.head(url)).flatMap { head =>
          val total = HttpRange.contentLength(url, head)
          val n     = math.min(total, length.toLong).toInt
          readAsync(total - n, n).map(b => (b, total))
        }
  }

  override def close(): Unit = client.close()

end AsyncHttpRangeSource
