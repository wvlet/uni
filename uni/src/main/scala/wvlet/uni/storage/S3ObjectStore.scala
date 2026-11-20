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
package wvlet.uni.storage

import wvlet.uni.http.HttpContent
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.storage.StorageSupport.*

/**
  * An [[ObjectStore]] for AWS S3 and S3-compatible services (Cloudflare R2, GCS with HMAC keys,
  * MinIO), signed with AWS Signature Version 4.
  *
  * Redirects are never followed: a redirect would resend the signed headers to another host. A
  * bucket in another region fails with [[StorageErrorCode.WrongRegion]] naming the right region.
  */
class S3ObjectStore(val config: S3Config) extends ObjectStore with LogSupport:
  import S3ObjectStore.*

  /** The signing region */
  val region: String = config.resolvedRegion

  private val http = config.httpClient.noFollowRedirects

  // The Node.js sync channel caps a response at maxResponseBytes, so read in smaller ranges
  private val maxChunk: Long = maxRangeChunk(http)
  private val clients        = Clients(http)

  override def close(): Unit = clients.close()

  // ---- Addressing and signing ----

  private val (endpointScheme, endpointAuthority, endpointPath) =
    val ep = config.endpoint.getOrElse(s"https://s3.${region}.amazonaws.com")
    val i  = ep.indexOf("://")
    if i <= 0 then
      throw IllegalArgumentException(s"S3 endpoint must be an http(s) URL: ${ep}")
    val rest  = ep.substring(i + 3)
    val slash = rest.indexOf('/')
    if slash < 0 then
      (ep.substring(0, i), rest, "")
    else
      (ep.substring(0, i), rest.substring(0, slash), rest.substring(slash).stripSuffix("/"))

  // Buckets with dots don't match the TLS wildcard certificate, so they use path-style
  private def isVirtualHosted(bucket: String): Boolean = !config.pathStyle && !bucket.contains('.')

  /** The host and the encoded path for an object (or the bucket when `key` is empty) */
  private[storage] def location(uri: ObjectUri): (String, String) =
    val keyPath = SigV4.encodePath(uri.key)
    if isVirtualHosted(uri.bucket) then
      (s"${uri.bucket}.${endpointAuthority}", s"${endpointPath}/${keyPath}")
    else
      val bucketPath = s"${endpointPath}/${SigV4.encodePath(uri.bucket)}"
      if uri.key.isEmpty then
        (endpointAuthority, bucketPath)
      else
        (endpointAuthority, s"${bucketPath}/${keyPath}")

  private def credentials: Option[AwsCredentials] = config.credentials.credentials

  private[storage] def buildRequest(call: Call): Request =
    val (host, path) = location(call.uri)
    val q            = SigV4.canonicalQuery(call.query)
    val url          =
      s"${endpointScheme}://${host}${path}${
          if q.isEmpty then
            ""
          else
            s"?${q}"
        }"
    val signed = call.headers ++ call.contentType.map("content-type" -> _)
    var req    = Request(call.method, url)
    signed.foreach((k, v) => req = req.setHeader(k, v))
    credentials.foreach { c =>
      val payloadHash =
        if call.body.isEmpty then
          Sha256.emptyHex
        else
          Sha256.hex(call.body)
      SigV4
        .signHeaders(
          SigV4Request(call.method.name, host, path, call.query, signed, payloadHash),
          c,
          region
        )
        .foreach((k, v) => req = req.setHeader(k, v))
    }
    if call.body.nonEmpty then
      req = req.withContent(HttpContent.bytes(call.body))
    req

  end buildRequest

  // ---- Sending ----

  private def send(call: Call): Response =
    val req = buildRequest(call)
    try clients.sync.send(req)
    catch unwrapHttpError

  private def sendAsync(call: Call): Rx[Response] = Rx
    .single(())
    .flatMap(_ => clients.async.send(buildRequest(call)))
    .recover(unwrapHttpError)

  private def checked(call: Call, resp: Response): Response =
    if resp.isSuccessful then
      resp
    else
      throw errorOf(call.uri, resp, region)

  // ---- Operations ----

  override def head(uri: ObjectUri): Option[ObjectInfo] = headResult(
    uri,
    send(Call(HttpMethod.HEAD, uri))
  )

  override def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]] = sendAsync(
    Call(HttpMethod.HEAD, uri)
  ).map(headResult(uri, _))

  private def headResult(uri: ObjectUri, resp: Response): Option[ObjectInfo] =
    if resp.statusCode == 404 then
      None
    else if !resp.isSuccessful then
      throw errorOf(uri, resp, region)
    else
      Some(
        ObjectInfo(
          uri,
          resp.header("Content-Length").flatMap(_.trim.toLongOption).getOrElse(0L),
          resp.header("ETag"),
          resp.header("Last-Modified"),
          resp.header("Content-Type")
        )
      )

  override def get(uri: ObjectUri): Array[Byte] =
    if StorageEnv.isNode then
      // One response may not exceed maxResponseBytes there
      head(uri) match
        case Some(info) =>
          getRange(uri, 0, info.size)
        case None =>
          throw noSuchKey(uri)
    else
      val call = Call(HttpMethod.GET, uri)
      bodyOf(checked(call, send(call)))

  override def getAsync(uri: ObjectUri): Rx[Array[Byte]] =
    val call = Call(HttpMethod.GET, uri)
    sendAsync(call).map(r => bodyOf(checked(call, r)))

  override def getRange(uri: ObjectUri, offset: Long, length: Long): Array[Byte] =
    readRanges(offset, length, maxChunk) { (pos, n) =>
      val call = rangeCall(uri, pos, n)
      rangeBody(send(call), pos, n, checked(call, _))
    }

  override def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]] =
    readRangesAsync(offset, length, maxChunk) { (pos, n) =>
      val call = rangeCall(uri, pos, n)
      sendAsync(call).map(rangeBody(_, pos, n, checked(call, _)))
    }

  private def rangeCall(uri: ObjectUri, offset: Long, length: Long): Call = Call(
    HttpMethod.GET,
    uri,
    headers = Seq(rangeHeader(offset, length))
  )

  override def put(uri: ObjectUri, content: Array[Byte], contentType: String): Unit =
    val call = Call(HttpMethod.PUT, uri, body = content, contentType = Some(contentType))
    checked(call, send(call))

  override def putAsync(uri: ObjectUri, content: Array[Byte], contentType: String): Rx[Unit] =
    val call = Call(HttpMethod.PUT, uri, body = content, contentType = Some(contentType))
    sendAsync(call).map(r => checked(call, r)).map(_ => ())

  override def delete(uri: ObjectUri): Unit =
    val call = Call(HttpMethod.DELETE, uri)
    deleteResult(call, send(call))

  override def deleteAsync(uri: ObjectUri): Rx[Unit] =
    val call = Call(HttpMethod.DELETE, uri)
    sendAsync(call).map(deleteResult(call, _))

  private def deleteResult(call: Call, resp: Response): Unit =
    if resp.statusCode != 404 then
      checked(call, resp)

  override def list(uri: ObjectUri, recursive: Boolean): Seq[ObjectInfo] =
    val result = Seq.newBuilder[ObjectInfo]
    var token  = Option.empty[String]
    var more   = true
    while more do
      val call = listCall(uri, recursive, token)
      val page = parseListing(uri, bodyOf(checked(call, send(call))))
      result ++= page.entries
      token = page.nextToken
      more = token.isDefined
    result.result()

  override def listAsync(uri: ObjectUri, recursive: Boolean): Rx[Seq[ObjectInfo]] =
    def loop(token: Option[String], acc: Vector[ObjectInfo]): Rx[Seq[ObjectInfo]] =
      val call = listCall(uri, recursive, token)
      sendAsync(call).flatMap { resp =>
        val page = parseListing(uri, bodyOf(checked(call, resp)))
        val all  = acc ++ page.entries
        page.nextToken match
          case Some(t) =>
            loop(Some(t), all)
          case None =>
            Rx.single(all)
      }
    loop(None, Vector.empty)

  private def listCall(uri: ObjectUri, recursive: Boolean, token: Option[String]): Call =
    val query =
      Seq("list-type" -> "2") ++ Option(uri.key).filter(_.nonEmpty).map("prefix" -> _) ++ (
        if recursive then
          Nil
        else
          Seq("delimiter" -> "/")
      ) ++ token.map("continuation-token" -> _)
    Call(HttpMethod.GET, uri.copy(key = ""), query = query)

  override def newOutputStream(uri: ObjectUri, contentType: String): ObjectOutputStream =
    S3OutputStream(this, uri, contentType, config.partSizeBytes)

  /**
    * A URL that lets anyone holding it send `method` to the object until it expires (at most 7
    * days). Use GET to share a download, PUT to accept an upload (a browser can `fetch` it).
    */
  override def presign(uri: ObjectUri, method: HttpMethod, expiresSeconds: Long): String =
    val c = credentials.getOrElse(
      throw StorageException(
        StorageErrorCode.NoCredentials,
        "Presigning needs credentials; this store is anonymous"
      )
    )
    val (host, path) = location(uri)
    val q = SigV4.presignQuery(SigV4Request(method.name, host, path), c, region, expiresSeconds)
    s"${endpointScheme}://${host}${path}?${q}"

  // ---- Multipart upload (used by S3OutputStream) ----

  private[storage] def createMultipartUpload(uri: ObjectUri, contentType: String): String =
    val call = Call(
      HttpMethod.POST,
      uri,
      query = Seq("uploads" -> ""),
      contentType = Some(contentType)
    )
    val xml = S3Xml.parse(utf8(bodyOf(checked(call, send(call)))))
    xml
      .childText("UploadId")
      .filter(_.nonEmpty)
      .getOrElse(
        throw StorageException(
          StorageErrorCode.InvalidResponse,
          s"No UploadId in the CreateMultipartUpload answer for ${uri}"
        )
      )

  private[storage] def uploadPart(
      uri: ObjectUri,
      uploadId: String,
      partNumber: Int,
      bytes: Array[Byte]
  ): String =
    val call = Call(
      HttpMethod.PUT,
      uri,
      query = Seq("partNumber" -> partNumber.toString, "uploadId" -> uploadId),
      body = bytes
    )
    checked(call, send(call))
      .header("ETag")
      .getOrElse(
        throw StorageException(
          StorageErrorCode.InvalidResponse,
          s"No ETag for part ${partNumber} of ${uri}"
        )
      )

  private[storage] def completeMultipartUpload(
      uri: ObjectUri,
      uploadId: String,
      etags: Seq[String]
  ): Unit =
    val parts =
      etags
        .zipWithIndex
        .map((etag, i) =>
          s"<Part><PartNumber>${i + 1}</PartNumber><ETag>${S3Xml.escape(etag)}</ETag></Part>"
        )
        .mkString
    val body = s"<CompleteMultipartUpload>${parts}</CompleteMultipartUpload>".getBytes("UTF-8")
    val call = Call(
      HttpMethod.POST,
      uri,
      query = Seq("uploadId" -> uploadId),
      body = body,
      contentType = Some("application/xml")
    )
    val resp = checked(call, send(call))
    // S3 may answer 200 and report a failure in the body
    val text = utf8(bodyOf(resp))
    if text.contains("<Error") then
      throw errorFromXml(uri, resp.statusCode, text)

  private[storage] def abortMultipartUpload(uri: ObjectUri, uploadId: String): Unit =
    val call = Call(HttpMethod.DELETE, uri, query = Seq("uploadId" -> uploadId))
    deleteResult(call, send(call))

end S3ObjectStore

object S3ObjectStore:
  private[storage] case class Call(
      method: HttpMethod,
      uri: ObjectUri,
      query: Seq[(String, String)] = Nil,
      headers: Seq[(String, String)] = Nil,
      body: Array[Byte] = Array.emptyByteArray,
      contentType: Option[String] = None
  )

  private[storage] case class ListPage(entries: Seq[ObjectInfo], nextToken: Option[String])

  private[storage] def parseListing(prefix: ObjectUri, body: Array[Byte]): ListPage =
    val xml     = S3Xml.parse(utf8(body))
    val objects = xml
      .all("Contents")
      .map { c =>
        ObjectInfo(
          prefix.copy(key = c.childText("Key").getOrElse("")),
          c.childText("Size").flatMap(_.trim.toLongOption).getOrElse(0L),
          c.childText("ETag"),
          c.childText("LastModified")
        )
      }
    val dirs = xml
      .all("CommonPrefixes")
      .flatMap(_.childText("Prefix"))
      .map(p => ObjectInfo(prefix.copy(key = p), 0L, isDirectory = true))
    val truncated = xml.childText("IsTruncated").exists(_.trim.equalsIgnoreCase("true"))
    val next      =
      if truncated then
        xml.childText("NextContinuationToken").filter(_.nonEmpty)
      else
        None
    ListPage(objects ++ dirs, next)

  private def noSuchKey(uri: ObjectUri): StorageException = StorageException(
    StorageErrorCode.NoSuchKey,
    s"${uri} does not exist",
    Some(404)
  )

  private[storage] def errorOf(
      uri: ObjectUri,
      resp: Response,
      signingRegion: String = ""
  ): StorageException =
    val status = resp.statusCode
    resp.header("x-amz-bucket-region") match
      case Some(r) if r != signingRegion && (status == 301 || status == 307 || status == 400) =>
        StorageException(
          StorageErrorCode.WrongRegion,
          s"Bucket ${uri.bucket} is in region ${r}; set it with S3Config.withRegion(\"${r}\")",
          Some(status)
        )
      case _ =>
        val text = resp.contentAsBytes.map(utf8).getOrElse("")
        if text.contains("<Error") then
          errorFromXml(uri, status, text)
        else
          val code =
            status match
              case 404 =>
                StorageErrorCode.NoSuchKey
              case 403 =>
                StorageErrorCode.AccessDenied
              case 301 | 307 =>
                StorageErrorCode.WrongRegion
              case s =>
                s"HTTP${s}"
          StorageException(code, s"${uri}: HTTP ${status} ${resp.reason}", Some(status))

  private def errorFromXml(uri: ObjectUri, status: Int, text: String): StorageException =
    val xml =
      try
        Some(S3Xml.parse(text))
      catch
        case _: StorageException =>
          None
    val code = xml.flatMap(_.childText("Code")).filter(_.nonEmpty).getOrElse(s"HTTP${status}")
    val msg  = xml.flatMap(_.childText("Message")).filter(_.nonEmpty).getOrElse("")
    StorageException(code, s"${uri}: ${code} ${msg}".trim, Some(status))

end S3ObjectStore

/**
  * Buffers writes into parts and uploads them with S3 multipart upload. A stream that never fills
  * one part sends a single PUT on close. Failures abort the upload so no partial object appears.
  */
private[storage] class S3OutputStream(
    store: S3ObjectStore,
    uri: ObjectUri,
    contentType: String,
    partSize: Int
) extends ObjectOutputStream
    with LogSupport:
  private val buf      = new Array[Byte](partSize)
  private var used     = 0
  private var uploadId = Option.empty[String]
  private val etags    = Seq.newBuilder[String]
  private var parts    = 0
  private var closed   = false
  private var failed   = false

  override def write(b: Int): Unit =
    val one = Array(b.toByte)
    write(one, 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    ensureOpen()
    var i   = off
    val end = off + len
    while i < end do
      if used == partSize then
        guarded(flushPart())
      val n = math.min(partSize - used, end - i)
      System.arraycopy(b, i, buf, used, n)
      used += n
      i += n

  private def ensureOpen(): Unit =
    if closed then
      throw java.io.IOException(s"The upload stream for ${uri} is closed")
    if failed then
      throw java.io.IOException(s"The upload to ${uri} failed")

  private def flushPart(): Unit =
    val id = uploadId.getOrElse {
      val newId = store.createMultipartUpload(uri, contentType)
      uploadId = Some(newId)
      newId
    }
    parts += 1
    etags += store.uploadPart(uri, id, parts, java.util.Arrays.copyOf(buf, used))
    used = 0

  private def guarded[A](body: => A): A =
    try
      body
    catch
      case e: Throwable =>
        failed = true
        abortUpload()
        throw e

  /** Discards the upload: nothing written so far is published */
  override def abort(): Unit =
    if !closed then
      closed = true
      failed = true
      abortUpload()

  private def abortUpload(): Unit =
    uploadId.foreach { id =>
      try
        store.abortMultipartUpload(uri, id)
      catch
        case e: Exception =>
          warn(s"Failed to abort the multipart upload of ${uri}: ${e.getMessage}")
    }
    uploadId = None

  override def close(): Unit =
    if !closed then
      closed = true
      if !failed then
        guarded {
          uploadId match
            case None =>
              store.put(uri, java.util.Arrays.copyOf(buf, used), contentType)
            case Some(id) =>
              if used > 0 then
                flushPart()
              store.completeMultipartUpload(uri, id, etags.result())
        }

end S3OutputStream
