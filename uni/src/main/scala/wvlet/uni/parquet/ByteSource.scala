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
import wvlet.uni.storage.AzureConfig
import wvlet.uni.storage.GcsConfig
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.storage.S3Config

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

  /**
    * An object in an object store, read with ranged GETs (JVM, Scala Native, and Node.js). The
    * store stays open when the source closes.
    */
  def ofObject(store: ObjectStore, uri: ObjectUri): ByteSource = ObjectSource(store, uri, false)

  def ofObjectAsync(store: ObjectStore, uri: ObjectUri): AsyncByteSource = AsyncObjectSource(
    store,
    uri,
    false
  )

  /**
    * An S3 object such as `s3://bucket/key.parquet`, or one in an S3-compatible store (Cloudflare
    * R2, GCS, MinIO) configured by `config`
    */
  def ofS3(uri: String, config: S3Config = S3Config.default): ByteSource = ObjectSource(
    ObjectStore.s3(config),
    ObjectUri.parse(uri),
    true
  )

  def ofS3Async(uri: String, config: S3Config = S3Config.default): AsyncByteSource =
    AsyncObjectSource(ObjectStore.s3(config), ObjectUri.parse(uri), true)

  /**
    * A Google Cloud Storage object such as `gs://bucket/key.parquet`, read with OAuth credentials
    */
  def ofGcs(uri: String, config: GcsConfig = GcsConfig.default): ByteSource = ObjectSource(
    ObjectStore.gcs(config),
    ObjectUri.parse(uri),
    true
  )

  def ofGcsAsync(uri: String, config: GcsConfig = GcsConfig.default): AsyncByteSource =
    AsyncObjectSource(ObjectStore.gcs(config), ObjectUri.parse(uri), true)

  /** An Azure Blob such as `az://container/key.parquet`, in the storage account of `config` */
  def ofAzure(uri: String, config: AzureConfig = AzureConfig.default): ByteSource = ObjectSource(
    ObjectStore.azure(config),
    ObjectUri.parse(uri),
    true
  )

  def ofAzureAsync(uri: String, config: AzureConfig = AzureConfig.default): AsyncByteSource =
    AsyncObjectSource(ObjectStore.azure(config), ObjectUri.parse(uri), true)

end ByteSource

private[parquet] final class ObjectSource(store: ObjectStore, uri: ObjectUri, ownsStore: Boolean)
    extends ByteSource:
  def name: String = uri.toString

  private lazy val objectSize: Long = store
    .head(uri)
    .map(_.size)
    .getOrElse(throw ParquetException(s"${uri} does not exist"))

  def size: Long = objectSize

  def read(offset: Long, length: Int): Array[Byte] =
    val b = store.getRange(uri, offset, length.toLong)
    if b.length != length then
      throw ParquetException.corrupt(
        s"range [${offset}, ${offset + length}) is outside ${uri} (${objectSize} bytes)"
      )
    b

  override def close(): Unit =
    if ownsStore then
      store.close()

private[parquet] final class AsyncObjectSource(
    store: ObjectStore,
    uri: ObjectUri,
    ownsStore: Boolean
) extends AsyncByteSource:
  def name: String = uri.toString

  def readAsync(offset: Long, length: Int): Rx[Array[Byte]] = store
    .getRangeAsync(uri, offset, length.toLong)
    .map { b =>
      if b.length != length then
        throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${uri}")
      b
    }

  def readTailAsync(length: Int): Rx[(Array[Byte], Long)] = store
    .headAsync(uri)
    .flatMap {
      case Some(info) =>
        val n = math.min(info.size, length.toLong).toInt
        readAsync(info.size - n, n).map(b => (b, info.size))
      case None =>
        Rx.exception(ParquetException(s"${uri} does not exist"))
    }

  override def close(): Unit =
    if ownsStore then
      store.close()

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
