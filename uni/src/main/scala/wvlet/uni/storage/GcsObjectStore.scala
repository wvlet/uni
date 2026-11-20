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
import wvlet.uni.json.JSON
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.storage.StorageSupport.*
import wvlet.uni.util.URLEncoder

/**
  * An [[ObjectStore]] for Google Cloud Storage through its JSON API, authorized with OAuth 2.0
  * access tokens from a [[GoogleCredentialsProvider]] (Application Default Credentials by default).
  * Large streams use resumable uploads; `presign` makes V4 signed URLs.
  *
  * Redirects are never followed, so a bearer token is never sent to another host.
  */
class GcsObjectStore(val config: GcsConfig) extends ObjectStore with LogSupport:
  import GcsObjectStore.*

  val endpoint: String = config.resolvedEndpoint

  private val credentialsProvider = config.resolvedCredentials
  private val http                = config.httpClient.noFollowRedirects
  private val maxChunk: Long      = maxRangeChunk(http)
  private val clients             = Clients(http)

  private val (endpointScheme, endpointAuthority) =
    val i = endpoint.indexOf("://")
    if i <= 0 then
      throw IllegalArgumentException(s"GCS endpoint must be an http(s) URL: ${endpoint}")
    val rest = endpoint.substring(i + 3)
    (endpoint.substring(0, i), rest.takeWhile(_ != '/'))

  override def close(): Unit = clients.close()

  // ---- Requests ----

  private[storage] def objectUrl(uri: ObjectUri, query: Seq[(String, String)] = Nil): String =
    withQuery(
      s"${endpoint}/storage/v1/b/${URLEncoder.encode(uri.bucket)}/o/${URLEncoder.encode(uri.key)}",
      query
    )

  private def bucketUrl(uri: ObjectUri, upload: Boolean, query: Seq[(String, String)]): String =
    val prefix =
      if upload then
        "/upload"
      else
        ""
    withQuery(s"${endpoint}${prefix}/storage/v1/b/${URLEncoder.encode(uri.bucket)}/o", query)

  private def authorized(req: Request): Request = credentialsProvider
    .accessToken
    .map(t => req.withBearerToken(t.token))
    .getOrElse(req)

  private[storage] def send(req: Request): Response =
    try clients.sync.send(authorized(req))
    catch unwrapHttpError

  private def sendAsync(req: => Request): Rx[Response] = Rx
    .single(())
    .flatMap(_ => clients.async.send(authorized(req)))
    .recover(unwrapHttpError)

  private def checked(uri: ObjectUri, resp: Response): Response =
    if resp.isSuccessful then
      resp
    else
      throw errorOf(uri, resp)

  // ---- Operations ----

  override def head(uri: ObjectUri): Option[ObjectInfo] = headResult(
    uri,
    send(Request.get(objectUrl(uri)))
  )

  override def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]] = sendAsync(
    Request.get(objectUrl(uri))
  ).map(headResult(uri, _))

  private def headResult(uri: ObjectUri, resp: Response): Option[ObjectInfo] =
    if resp.statusCode == 404 then
      None
    else
      json(checked(uri, resp)) match
        case o: JSON.JSONObject =>
          Some(objectInfo(uri, o))
        case _ =>
          throw invalidResponse(uri)

  private def mediaUrl(uri: ObjectUri): String = objectUrl(uri, Seq("alt" -> "media"))

  override def get(uri: ObjectUri): Array[Byte] =
    if StorageEnv.isNode then
      // One response may not exceed maxResponseBytes there
      head(uri) match
        case Some(info) =>
          getRange(uri, 0, info.size)
        case None =>
          throw noSuchKey(uri)
    else
      bodyOf(checked(uri, send(Request.get(mediaUrl(uri)))))

  override def getAsync(uri: ObjectUri): Rx[Array[Byte]] = sendAsync(Request.get(mediaUrl(uri)))
    .map(r => bodyOf(checked(uri, r)))

  private def rangeRequest(uri: ObjectUri, offset: Long, length: Long): Request =
    val (k, v) = rangeHeader(offset, length)
    Request.get(mediaUrl(uri)).setHeader(k, v)

  override def getRange(uri: ObjectUri, offset: Long, length: Long): Array[Byte] =
    readRanges(offset, length, maxChunk) { (pos, n) =>
      rangeBody(send(rangeRequest(uri, pos, n)), pos, n, checked(uri, _))
    }

  override def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]] =
    readRangesAsync(offset, length, maxChunk) { (pos, n) =>
      sendAsync(rangeRequest(uri, pos, n)).map(rangeBody(_, pos, n, checked(uri, _)))
    }

  private def putRequest(uri: ObjectUri, content: Array[Byte], contentType: String): Request =
    Request
      .post(bucketUrl(uri, upload = true, Seq("uploadType" -> "media", "name" -> uri.key)))
      .setHeader("Content-Type", contentType)
      .withContent(HttpContent.bytes(content))

  override def put(uri: ObjectUri, content: Array[Byte], contentType: String): Unit = checked(
    uri,
    send(putRequest(uri, content, contentType))
  )

  override def putAsync(uri: ObjectUri, content: Array[Byte], contentType: String): Rx[Unit] =
    sendAsync(putRequest(uri, content, contentType)).map(r => checked(uri, r)).map(_ => ())

  override def delete(uri: ObjectUri): Unit = deleteResult(
    uri,
    send(Request.delete(objectUrl(uri)))
  )

  override def deleteAsync(uri: ObjectUri): Rx[Unit] = sendAsync(Request.delete(objectUrl(uri)))
    .map(deleteResult(uri, _))

  private def deleteResult(uri: ObjectUri, resp: Response): Unit =
    if resp.statusCode != 404 then
      checked(uri, resp)

  private def listRequest(uri: ObjectUri, recursive: Boolean, token: Option[String]): Request =
    val query =
      Option(uri.key).filter(_.nonEmpty).map("prefix" -> _).toSeq ++ (
        if recursive then
          Nil
        else
          Seq("delimiter" -> "/")
      ) ++ token.map("pageToken" -> _)
    Request.get(bucketUrl(uri, upload = false, query))

  override def list(uri: ObjectUri, recursive: Boolean): Seq[ObjectInfo] =
    val result = Seq.newBuilder[ObjectInfo]
    var token  = Option.empty[String]
    var more   = true
    while more do
      val page = parseListing(uri, json(checked(uri, send(listRequest(uri, recursive, token)))))
      result ++= page.entries
      token = page.nextToken
      more = token.isDefined
    result.result()

  override def listAsync(uri: ObjectUri, recursive: Boolean): Rx[Seq[ObjectInfo]] =
    def loop(token: Option[String], acc: Vector[ObjectInfo]): Rx[Seq[ObjectInfo]] = sendAsync(
      listRequest(uri, recursive, token)
    ).flatMap { resp =>
      val page = parseListing(uri, json(checked(uri, resp)))
      val all  = acc ++ page.entries
      page.nextToken match
        case Some(t) =>
          loop(Some(t), all)
        case None =>
          Rx.single(all)
    }
    loop(None, Vector.empty)

  override def newOutputStream(uri: ObjectUri, contentType: String): ObjectOutputStream =
    GcsOutputStream(this, uri, contentType, config.chunkSizeBytes)

  /**
    * A V4 signed URL (`GOOG4-RSA-SHA256`) for the XML API host. A service-account key signs
    * locally; metadata-server credentials sign through IAM `signBlob`. User credentials can't sign.
    */
  override def presign(uri: ObjectUri, method: HttpMethod, expiresSeconds: Long): String =
    val signer = credentialsProvider
      .signer
      .getOrElse(
        throw StorageException(
          StorageErrorCode.NoCredentials,
          s"Signing a URL needs a service account; ${credentialsProvider.name} can't sign. " +
            "Use a service-account key file or the metadata server's account"
        )
      )
    GcsSignedUrl.sign(
      method.name,
      endpointScheme,
      endpointAuthority,
      GcsSignedUrl.path(uri),
      signer,
      expiresSeconds
    )

  // ---- Resumable upload (used by GcsOutputStream) ----

  /** Starts a resumable upload and returns its session URI, which must stay out of logs */
  private[storage] def startResumableUpload(uri: ObjectUri, contentType: String): String =
    val body =
      s"""{"name":${JSON.JSONString(uri.key).toJSON},"contentType":${JSON
          .JSONString(contentType)
          .toJSON}}"""
    val req = Request
      .post(bucketUrl(uri, upload = true, Seq("uploadType" -> "resumable", "name" -> uri.key)))
      .setHeader("X-Upload-Content-Type", contentType)
      .withJsonContent(body)
    val location = checked(uri, send(req))
      .header("Location")
      .getOrElse(
        throw StorageException(
          StorageErrorCode.InvalidResponse,
          s"No session URI in the resumable upload answer for ${uri}"
        )
      )
    // The session URI grants upload access; never send it, or a token, to another host
    if !location.startsWith(s"${endpointScheme}://${endpointAuthority}/") then
      throw StorageException(
        StorageErrorCode.InvalidResponse,
        s"The resumable upload session for ${uri} is on another host than ${endpointAuthority}"
      )
    location

  /**
    * Sends `bytes` at `offset` of the upload. Returns the number of bytes the server has committed,
    * or None when the upload is complete. `total` is set on the last chunk.
    */
  private[storage] def uploadChunk(
      uri: ObjectUri,
      session: String,
      offset: Long,
      bytes: Array[Byte],
      length: Int,
      total: Option[Long]
  ): Option[Long] =
    val totalStr = total.map(_.toString).getOrElse("*")
    val range    =
      if length == 0 then
        s"bytes */${totalStr}"
      else
        s"bytes ${offset}-${offset + length - 1}/${totalStr}"
    val req = Request
      .put(session)
      .setHeader("Content-Range", range)
      // Answer 200 instead of 308, which fetch with manual redirects can't read
      .setHeader("X-GUploader-No-308", "yes")
      .withContent(HttpContent.bytes(java.util.Arrays.copyOf(bytes, length)))
    // The session URI acts as a credential: no Authorization header, and errors don't echo it
    val resp =
      try
        try clients.sync.send(req)
        catch unwrapHttpError
      catch
        case e: StorageException =>
          throw e
        case e: Exception =>
          throw StorageException(
            "UploadFailed",
            s"Uploading a chunk of ${uri} failed: ${e.getClass.getSimpleName}"
          )
    val incomplete =
      resp.statusCode == 308 || resp.header("X-Http-Status-Code-Override").contains("308")
    if incomplete then
      Some(committedBytes(resp))
    else if resp.isSuccessful then
      None
    else
      throw errorOf(uri, resp)

  end uploadChunk

  /** Cancels a resumable upload. Google answers 499 for a cancelled session. */
  private[storage] def cancelResumableUpload(uri: ObjectUri, session: String): Unit =
    try
      val resp =
        try clients.sync.noRetry.send(Request.delete(session))
        catch unwrapHttpError
      if !(resp.isSuccessful || resp.statusCode == 499 || resp.statusCode == 404) then
        warn(s"Cancelling the upload of ${uri} answered HTTP ${resp.statusCode}")
    catch
      case e: Exception =>
        warn(s"Failed to cancel the upload of ${uri}: ${e.getClass.getSimpleName}")

end GcsObjectStore

object GcsObjectStore:
  private[storage] case class ListPage(entries: Seq[ObjectInfo], nextToken: Option[String])

  private def withQuery(url: String, query: Seq[(String, String)]): String =
    if query.isEmpty then
      url
    else
      s"${url}?${SigV4.canonicalQuery(query)}"

  private def json(resp: Response): JSON.JSONValue =
    val text = utf8(bodyOf(resp))
    try
      JSON.parse(text)
    catch
      case _: Exception =>
        throw StorageException(StorageErrorCode.InvalidResponse, "GCS answered invalid JSON")

  private def invalidResponse(uri: ObjectUri): StorageException = StorageException(
    StorageErrorCode.InvalidResponse,
    s"Unexpected GCS answer for ${uri}"
  )

  private def str(o: JSON.JSONObject, key: String): Option[String] = o
    .get(key)
    .collect { case JSON.JSONString(s) =>
      s
    }

  private[storage] def objectInfo(base: ObjectUri, o: JSON.JSONObject): ObjectInfo = ObjectInfo(
    base.copy(key = str(o, "name").getOrElse(base.key)),
    o.get("size")
      .collect {
        case JSON.JSONString(s) if s.toLongOption.isDefined =>
          s.toLong
        case JSON.JSONLong(v) =>
          v
      }
      .getOrElse(0L),
    str(o, "etag"),
    str(o, "updated"),
    str(o, "contentType")
  )

  private[storage] def parseListing(prefix: ObjectUri, v: JSON.JSONValue): ListPage =
    v match
      case o: JSON.JSONObject =>
        val items = o
          .get("items")
          .collect { case JSON.JSONArray(a) =>
            a.collect { case item: JSON.JSONObject =>
              objectInfo(prefix.copy(key = ""), item)
            }
          }
          .getOrElse(Nil)
        val dirs = o
          .get("prefixes")
          .collect { case JSON.JSONArray(a) =>
            a.collect { case JSON.JSONString(p) =>
              ObjectInfo(prefix.copy(key = p), 0L, isDirectory = true)
            }
          }
          .getOrElse(Nil)
        ListPage(items ++ dirs, str(o, "nextPageToken").filter(_.nonEmpty))
      case _ =>
        throw invalidResponse(prefix)

  /** The committed size from a resumable upload's `Range: bytes=0-N` header */
  private[storage] def committedBytes(resp: Response): Long = resp
    .header("Range")
    .flatMap { r =>
      val i = r.lastIndexOf('-')
      if i < 0 then
        None
      else
        r.substring(i + 1).trim.toLongOption.map(_ + 1)
    }
    .getOrElse(0L)

  private def noSuchKey(uri: ObjectUri): StorageException = StorageException(
    StorageErrorCode.NoSuchKey,
    s"${uri} does not exist",
    Some(404)
  )

  /** Maps Google's `{"error": {"code", "message", "errors": [{"reason"}]}}` to an exception */
  private[storage] def errorOf(uri: ObjectUri, resp: Response): StorageException =
    val status = resp.statusCode
    val err    =
      try
        JSON.parse(resp.contentAsString.getOrElse("")) match
          case o: JSON.JSONObject =>
            o.get("error")
              .collect { case e: JSON.JSONObject =>
                e
              }
          case _ =>
            None
      catch
        case _: Exception =>
          None
    val message = err.flatMap(str(_, "message")).filter(_.nonEmpty)
    val reason  = err
      .flatMap(_.get("errors"))
      .collect { case JSON.JSONArray(a) =>
        a
      }
      .flatMap(_.headOption)
      .collect { case e: JSON.JSONObject =>
        e
      }
      .flatMap(str(_, "reason"))
    val code =
      status match
        case 404 =>
          // A missing bucket and a missing object both have reason notFound; only the message
          // differs ("The specified bucket does not exist." vs "No such object: b/k")
          if message.exists(_.toLowerCase.startsWith("the specified bucket does not exist")) then
            StorageErrorCode.NoSuchBucket
          else
            StorageErrorCode.NoSuchKey
        case 401 | 403 =>
          StorageErrorCode.AccessDenied
        case s =>
          reason.filter(_.nonEmpty).getOrElse(s"HTTP${s}")
    val detail = message.getOrElse(s"HTTP ${status} ${resp.reason}")
    StorageException(code, s"${uri}: ${detail}", Some(status))

  end errorOf

end GcsObjectStore

/**
  * V4 signed URLs for GCS (`GOOG4-RSA-SHA256`). See
  * https://cloud.google.com/storage/docs/access-control/signing-urls-manually
  */
object GcsSignedUrl:
  val Algorithm: String = "GOOG4-RSA-SHA256"

  /** The XML API path of an object (`/bucket/key`, keeping `/` in the key) or of its bucket */
  def path(uri: ObjectUri): String =
    val bucket = s"/${URLEncoder.encode(uri.bucket)}"
    if uri.key.isEmpty then
      bucket
    else
      s"${bucket}/${SigV4.encodePath(uri.key)}"

  /**
    * @param path
    *   the encoded path, `/bucket/key`
    * @param query
    *   extra raw query parameters to sign
    * @param headers
    *   extra headers the request must send, besides `host`
    */
  def sign(
      method: String,
      scheme: String,
      host: String,
      path: String,
      signer: GoogleSigner,
      expiresSeconds: Long,
      query: Seq[(String, String)] = Nil,
      headers: Seq[(String, String)] = Nil,
      epochMillis: Long = System.currentTimeMillis()
  ): String =
    if expiresSeconds < 1 || expiresSeconds > SigV4.MaxPresignSeconds then
      throw IllegalArgumentException(
        s"A signed URL must expire in 1 to ${SigV4.MaxPresignSeconds} seconds: ${expiresSeconds}"
      )
    val datetime = AwsTime.amzDate(epochMillis)
    val scope    = s"${datetime.take(8)}/auto/storage/goog4_request"
    val grouped  = (("host" -> host) +: headers)
      .groupBy(_._1.toLowerCase)
      .view
      .mapValues(_.map((_, v) => v.trim.replaceAll("\\s+", " ")).mkString(","))
      .toSeq
      .sortBy(_._1)
    val canonicalHeaders = grouped.map((k, v) => s"${k}:${v}\n").mkString
    val signedHeaders    = grouped.map(_._1).mkString(";")
    val cq               = SigV4.canonicalQuery(
      query ++
        Seq(
          "X-Goog-Algorithm"     -> Algorithm,
          "X-Goog-Credential"    -> s"${signer.serviceAccountEmail}/${scope}",
          "X-Goog-Date"          -> datetime,
          "X-Goog-Expires"       -> expiresSeconds.toString,
          "X-Goog-SignedHeaders" -> signedHeaders
        )
    )
    val canonicalRequest = Seq(
      method,
      path,
      cq,
      canonicalHeaders,
      signedHeaders,
      SigV4.UnsignedPayload
    ).mkString("\n")
    val stringToSign = Seq(Algorithm, datetime, scope, Sha256.hex(canonicalRequest)).mkString("\n")
    val signature    = Sha256.toHex(signer.sign(stringToSign.getBytes("UTF-8")))
    s"${scheme}://${host}${path}?${cq}&X-Goog-Signature=${signature}"

  end sign

end GcsSignedUrl

/**
  * Buffers writes into chunks and sends them with a GCS resumable upload. A stream that never fills
  * one chunk sends a single upload on close. Failures cancel the upload so no partial object
  * appears.
  */
private[storage] class GcsOutputStream(
    store: GcsObjectStore,
    uri: ObjectUri,
    contentType: String,
    chunkSize: Int
) extends ObjectOutputStream:
  private val buf     = new Array[Byte](chunkSize)
  private var used    = 0
  private var sent    = 0L // bytes the server has committed
  private var session = Option.empty[String]
  private var closed  = false
  private var failed  = false

  // A server that keeps committing nothing is not making progress
  private val maxStalls = 3

  override def write(b: Int): Unit =
    val one = Array(b.toByte)
    write(one, 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    ensureOpen()
    var i   = off
    val end = off + len
    while i < end do
      if used == chunkSize then
        guarded(flushChunk())
      val n = math.min(chunkSize - used, end - i)
      System.arraycopy(b, i, buf, used, n)
      used += n
      i += n

  private def ensureOpen(): Unit =
    if closed then
      throw java.io.IOException(s"The upload stream for ${uri} is closed")
    if failed then
      throw java.io.IOException(s"The upload to ${uri} failed")

  private def sessionUri: String = session.getOrElse {
    val s = store.startResumableUpload(uri, contentType)
    session = Some(s)
    s
  }

  /** Keeps whatever the server did not commit at the start of the buffer */
  private def keepUncommitted(committed: Long): Unit =
    val done = (committed - sent).toInt
    if done < 0 || done > used then
      throw StorageException(
        StorageErrorCode.InvalidResponse,
        s"The upload of ${uri} reported ${committed} committed bytes, outside the chunk sent"
      )
    System.arraycopy(buf, done, buf, 0, used - done)
    used -= done
    sent = committed

  private def checkProgress(committed: Long, stalls: Int): Int =
    if committed > sent then
      0
    else if stalls + 1 >= maxStalls then
      throw StorageException(
        "UploadFailed",
        s"The upload of ${uri} stopped making progress at ${committed} bytes"
      )
    else
      stalls + 1

  private def flushChunk(): Unit =
    // Google may commit only part of a chunk; the rest stays buffered and goes with the next one
    val s      = sessionUri
    var stalls = 0
    while used == chunkSize do
      store.uploadChunk(uri, s, sent, buf, used, None) match
        case Some(committed) =>
          stalls = checkProgress(committed, stalls)
          keepUncommitted(committed)
        case None =>
          throw StorageException(
            StorageErrorCode.InvalidResponse,
            s"The upload of ${uri} completed before its last chunk"
          )

  private def finish(): Unit =
    val s      = sessionUri
    val total  = sent + used
    var stalls = 0
    var done   = false
    while !done do
      store.uploadChunk(uri, s, sent, buf, used, Some(total)) match
        case None =>
          done = true
        case Some(committed) =>
          stalls = checkProgress(committed, stalls)
          keepUncommitted(committed)

  private def guarded[A](body: => A): A =
    try
      body
    catch
      case e: Throwable =>
        failed = true
        cancel()
        throw e

  override def abort(): Unit =
    if !closed then
      closed = true
      failed = true
      cancel()

  private def cancel(): Unit =
    session.foreach(store.cancelResumableUpload(uri, _))
    session = None

  override def close(): Unit =
    if !closed then
      closed = true
      if !failed then
        guarded {
          session match
            case None =>
              store.put(uri, java.util.Arrays.copyOf(buf, used), contentType)
            case Some(_) =>
              finish()
        }

end GcsOutputStream
