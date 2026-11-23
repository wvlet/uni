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
package wvlet.uni.cloud.storage

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import wvlet.uni.http.HttpContent
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.cloud.storage.StorageSupport.*
import wvlet.uni.util.Base64
import wvlet.uni.util.URLEncoder

/**
  * An [[ObjectStore]] for Azure Blob Storage, addressed as `az://container/path/to/blob` (or
  * `azure://`). The storage account comes from the [[AzureConfig]]. Requests are signed with Shared
  * Key, carry a SAS token, or send a Microsoft Entra ID bearer token.
  *
  * Redirects are never followed: a redirect would resend the signed headers to another host.
  */
class AzureObjectStore(val config: AzureConfig) extends ObjectStore with LogSupport:
  import AzureObjectStore.*

  /** The storage account name */
  val account: String = config.resolvedAccount

  /** The Blob service URL */
  val endpoint: String = config.resolvedEndpoint

  private val http     = config.httpClient.noFollowRedirects
  private val clients  = Clients(http)
  private val maxChunk = maxRangeChunk(http)

  override def close(): Unit = clients.close()

  private val (endpointScheme, endpointAuthority, endpointPath) =
    val i = endpoint.indexOf("://")
    if i <= 0 then
      throw IllegalArgumentException(s"Azure endpoint must be an http(s) URL: ${endpoint}")
    val rest  = endpoint.substring(i + 3)
    val slash = rest.indexOf('/')
    if slash < 0 then
      (endpoint.substring(0, i).toLowerCase, rest, "")
    else
      (
        endpoint.substring(0, i).toLowerCase,
        rest.substring(0, slash),
        rest.substring(slash).stripSuffix("/")
      )

  // ---- Addressing and signing ----

  private def checkScheme(uri: ObjectUri): Unit =
    if !Schemes.contains(uri.scheme) then
      throw IllegalArgumentException(
        s"Azure Blob URIs use az://container/blob (or azure://): ${uri}"
      )

  /** The encoded path for a blob, a container (empty key), or the service (empty container) */
  private[storage] def pathOf(uri: ObjectUri): String =
    if uri.bucket.isEmpty then
      s"${endpointPath}/"
    else
      checkScheme(uri)
      val container = s"${endpointPath}/${SigV4.encodePath(uri.bucket)}"
      if uri.key.isEmpty then
        container
      else
        s"${container}/${SigV4.encodePath(uri.key)}"

  /** The blob's URL, without credentials */
  def url(uri: ObjectUri): String = s"${endpointScheme}://${endpointAuthority}${pathOf(uri)}"

  def url(uri: String): String = url(ObjectUri.parse(uri))

  private def credentials: Option[AzureCredentials] = config.credentials.credentials

  private[storage] def buildRequest(call: StorageCall): Request = buildRequest(
    call,
    credentials,
    System.currentTimeMillis()
  )

  private[storage] def buildRequest(
      call: StorageCall,
      creds: Option[AzureCredentials],
      nowMillis: Long
  ): Request =
    val path = pathOf(call.uri)
    val sas  = creds.collect { case s: AzureCredentials.Sas =>
      s.query
    }
    val q    = (Seq(SigV4.canonicalQuery(call.query)) ++ sas).filter(_.nonEmpty).mkString("&")
    val wire =
      s"${endpointScheme}://${endpointAuthority}${path}${
          if q.isEmpty then
            ""
          else
            s"?${q}"
        }"
    val headers =
      call.headers ++ call.contentType.map("Content-Type" -> _) ++
        Seq("x-ms-version" -> AzureAuth.Version, "x-ms-date" -> AwsTime.httpDate(nowMillis))
    var req = Request(call.method, wire)
    headers.foreach((k, v) => req = req.setHeader(k, v))
    creds.foreach {
      case AzureCredentials.SharedKey(_, key) =>
        val sts = AzureAuth.stringToSign(
          call.method.name,
          headers,
          call.body.length.toLong,
          account,
          path,
          call.query
        )
        req = req.setHeader("Authorization", AzureAuth.authorization(account, key, sts))
      case AzureCredentials.Bearer(token, _) =>
        if endpointScheme != "https" then
          throw StorageException(
            CloudErrorCode.InvalidConfig,
            s"Entra ID tokens are sent only over HTTPS; the endpoint is ${endpoint}"
          )
        req = req.setHeader("Authorization", s"Bearer ${token}")
      case _: AzureCredentials.Sas =>
      // Already in the query
    }
    if call.body.nonEmpty then
      req = req.withContent(HttpContent.bytes(call.body))
    req

  end buildRequest

  // ---- Sending ----

  private[storage] def send(call: StorageCall): Response =
    val req = buildRequest(call)
    try clients.sync.send(req)
    catch unwrapHttpError

  private def sendAsync(call: StorageCall): Rx[Response] = Rx
    .single(())
    .flatMap(_ => clients.async.send(buildRequest(call)))
    .recover(unwrapHttpError)

  private[storage] def checked(call: StorageCall, resp: Response): Response =
    if resp.isSuccessful then
      resp
    else
      throw errorOf(call.uri, resp)

  // ---- Operations ----

  override def head(uri: ObjectUri): Option[ObjectInfo] = headResult(
    uri,
    send(StorageCall(HttpMethod.HEAD, uri))
  )

  override def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]] = sendAsync(
    StorageCall(HttpMethod.HEAD, uri)
  ).map(headResult(uri, _))

  private def headResult(uri: ObjectUri, resp: Response): Option[ObjectInfo] =
    if resp.statusCode == 404 then
      None
    else if !resp.isSuccessful then
      throw errorOf(uri, resp)
    else
      Some(
        ObjectInfo(
          uri,
          resp.header("Content-Length").flatMap(_.trim.toLongOption).getOrElse(0L),
          resp.header("ETag"),
          resp.header("Last-Modified"),
          resp.header("Content-Type"),
          version = resp.header("ETag")
        )
      )

  override def get(uri: ObjectUri): Array[Byte] =
    if CloudEnv.isNode then
      // One response may not exceed maxResponseBytes there, so read the size and then ranges.
      // A failed HEAD keeps Azure's error code, which it sends in a header.
      val call = StorageCall(HttpMethod.HEAD, uri)
      val size = checked(call, send(call))
        .header("Content-Length")
        .flatMap(_.trim.toLongOption)
        .getOrElse(0L)
      getRange(uri, 0, size)
    else
      val call = StorageCall(HttpMethod.GET, uri)
      bodyOf(checked(call, send(call)))

  override def getAsync(uri: ObjectUri): Rx[Array[Byte]] =
    val call = StorageCall(HttpMethod.GET, uri)
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

  // x-ms-range keeps the signed Range line empty
  private def rangeCall(uri: ObjectUri, offset: Long, length: Long): StorageCall = StorageCall(
    HttpMethod.GET,
    uri,
    headers = Seq("x-ms-range" -> s"bytes=${offset}-${offset + length - 1}")
  )

  private def putCall(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String,
      condition: Seq[(String, String)] = Nil
  ): StorageCall = StorageCall(
    HttpMethod.PUT,
    uri,
    headers = Seq("x-ms-blob-type" -> "BlockBlob") ++ condition,
    body = content,
    contentType = Some(contentType)
  )

  // A lost race: 409 BlobAlreadyExists for If-None-Match (some emulators answer 412), and 412
  // ConditionNotMet for If-Match on a changed or missing blob. If-Match keeps 409 as an error,
  // since Put Blob also answers 409 for failures such as an immutability policy. Other 404s,
  // such as ContainerNotFound, still raise.
  private def conditionalResult(
      call: StorageCall,
      resp: Response,
      lostRace: Set[Int]
  ): Option[String] =
    if lostRace.contains(resp.statusCode) then
      None
    else
      checked(call, resp)
      Some(
        resp
          .header("ETag")
          .getOrElse(
            throw StorageException(
              CloudErrorCode.InvalidResponse,
              s"No ETag in the answer for ${call.uri}"
            )
          )
      )

  override def putIfAbsent(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String
  ): Option[String] =
    val call = putCall(uri, content, contentType, Seq("If-None-Match" -> "*"))
    conditionalResult(call, send(call), Set(409, 412))

  override def putIfAbsentAsync(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String
  ): Rx[Option[String]] =
    val call = putCall(uri, content, contentType, Seq("If-None-Match" -> "*"))
    sendAsync(call).map(conditionalResult(call, _, Set(409, 412)))

  override def putIfMatch(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String
  ): Option[String] =
    val call = putCall(uri, content, contentType, Seq("If-Match" -> version))
    conditionalResult(call, send(call), Set(412))

  override def putIfMatchAsync(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String
  ): Rx[Option[String]] =
    val call = putCall(uri, content, contentType, Seq("If-Match" -> version))
    sendAsync(call).map(conditionalResult(call, _, Set(412)))

  override def put(uri: ObjectUri, content: Array[Byte], contentType: String): Unit =
    val call = putCall(uri, content, contentType)
    checked(call, send(call))

  override def putAsync(uri: ObjectUri, content: Array[Byte], contentType: String): Rx[Unit] =
    val call = putCall(uri, content, contentType)
    sendAsync(call).map(r => checked(call, r)).map(_ => ())

  override def delete(uri: ObjectUri): Unit =
    val call = StorageCall(HttpMethod.DELETE, uri)
    deleteResult(call, send(call))

  override def deleteAsync(uri: ObjectUri): Rx[Unit] =
    val call = StorageCall(HttpMethod.DELETE, uri)
    sendAsync(call).map(deleteResult(call, _))

  // A 404 means the blob is already gone
  private def deleteResult(call: StorageCall, resp: Response): Unit =
    if resp.statusCode != 404 then
      checked(call, resp)

  override def list(uri: ObjectUri, recursive: Boolean): Seq[ObjectInfo] =
    val result = Seq.newBuilder[ObjectInfo]
    var marker = Option.empty[String]
    var more   = true
    while more do
      val call = listCall(uri, recursive, marker)
      val page = parseListing(uri, bodyOf(checked(call, send(call))))
      result ++= page.entries
      marker = page.nextToken
      more = marker.isDefined
    result.result()

  override def listAsync(uri: ObjectUri, recursive: Boolean): Rx[Seq[ObjectInfo]] =
    def loop(marker: Option[String], acc: Vector[ObjectInfo]): Rx[Seq[ObjectInfo]] =
      val call = listCall(uri, recursive, marker)
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

  private def listCall(uri: ObjectUri, recursive: Boolean, marker: Option[String]): StorageCall =
    val query =
      Seq("restype" -> "container", "comp" -> "list") ++
        Option(uri.key).filter(_.nonEmpty).map("prefix" -> _) ++ (
          if recursive then
            Nil
          else
            Seq("delimiter" -> "/")
        ) ++ marker.map("marker" -> _)
    StorageCall(HttpMethod.GET, uri.copy(key = ""), query = query)

  override def newOutputStream(uri: ObjectUri, contentType: String): ObjectOutputStream =
    AzureOutputStream(this, uri, contentType, config.blockSizeBytes)

  // ---- Block uploads (used by AzureOutputStream) ----

  private[storage] def putBlock(uri: ObjectUri, blockId: String, bytes: Array[Byte]): Unit =
    val call = StorageCall(
      HttpMethod.PUT,
      uri,
      query = Seq("comp" -> "block", "blockid" -> blockId),
      body = bytes,
      contentType = Some(ObjectStore.Binary)
    )
    checked(call, send(call))

  private[storage] def putBlockList(
      uri: ObjectUri,
      blockIds: Seq[String],
      contentType: String
  ): Unit =
    val ids  = blockIds.map(id => s"<Latest>${XmlReader.escape(id)}</Latest>").mkString
    val body = s"""<?xml version="1.0" encoding="utf-8"?><BlockList>${ids}</BlockList>"""
    val call = StorageCall(
      HttpMethod.PUT,
      uri,
      query = Seq("comp" -> "blocklist"),
      headers = Seq("x-ms-blob-content-type" -> contentType),
      body = body.getBytes("UTF-8"),
      contentType = Some("application/xml")
    )
    checked(call, send(call))

  /**
    * Creates a container, for example in a fresh Azurite. A container that already exists is fine.
    */
  def createContainer(container: String): Unit =
    val call = StorageCall(
      HttpMethod.PUT,
      ObjectUri(container, "", "az"),
      query = Seq("restype" -> "container"),
      contentType = Some(ObjectStore.Binary)
    )
    val resp = send(call)
    if resp.statusCode != 409 then
      checked(call, resp)

  // ---- SAS URLs ----

  @volatile
  private var delegationKey: Option[UserDelegationKey] = None

  /**
    * A SAS URL that lets anyone holding it send `method` (GET, HEAD, PUT, or DELETE) to the blob
    * until it expires (at most 7 days). An upload to a PUT URL must send `x-ms-blob-type:
    * BlockBlob`, as Azure's Put Blob requires. With a shared key, this is a service SAS; with Entra
    * ID credentials, a user delegation SAS (one extra request fetches the delegation key, which is
    * reused while it lasts). A store that holds only a SAS token can't narrow it, so share that
    * token's URL instead.
    */
  override def presign(uri: ObjectUri, method: HttpMethod, expiresSeconds: Long): String =
    checkScheme(uri)
    if expiresSeconds <= 0 || expiresSeconds > AzureAuth.MaxPresignSeconds then
      throw IllegalArgumentException(
        s"expiresSeconds must be 1 to ${AzureAuth.MaxPresignSeconds}: ${expiresSeconds}"
      )
    val now = System.currentTimeMillis()
    // A user delegation SAS can't outlive its key, which is valid for 7 days less a minute
    val untilMillis = math.min(now + expiresSeconds * 1000L, now + MaxKeyMillis)
    val expiry      = AwsTime.isoSeconds(untilMillis)
    val perms       = AzureAuth.permissions(method)
    val resource    = AzureAuth.sasResource(account, uri.bucket, uri.key)
    val params      =
      credentials match
        case Some(AzureCredentials.SharedKey(_, key)) =>
          val sts = AzureAuth.serviceSasStringToSign(perms, "", expiry, resource)
          Seq(
            "sp"  -> perms,
            "se"  -> expiry,
            "sv"  -> AzureAuth.Version,
            "sr"  -> "b",
            "sig" -> AzureAuth.sign(key, sts)
          )
        case Some(_: AzureCredentials.Bearer) =>
          val key = userDelegationKey(untilMillis)
          val sts = AzureAuth.userDelegationStringToSign(perms, "", expiry, resource, key)
          Seq(
            "sp"    -> perms,
            "se"    -> expiry,
            "skoid" -> key.signedOid,
            "sktid" -> key.signedTid,
            "skt"   -> key.signedStart,
            "ske"   -> key.signedExpiry,
            "sks"   -> key.signedService,
            "skv"   -> key.signedVersion,
            "sv"    -> AzureAuth.Version,
            "sr"    -> "b",
            "sig"   -> AzureAuth.sign(key.value, sts)
          )
        case Some(_: AzureCredentials.Sas) =>
          throw StorageException(
            CloudErrorCode.NoCredentials,
            "A store with only a SAS token can't sign narrower SAS URLs; share the URL with " +
              "your SAS token instead, or use an account key or Entra ID credentials"
          )
        case None =>
          throw StorageException(
            CloudErrorCode.NoCredentials,
            "Presigning needs credentials; this store is anonymous"
          )
    val q = params.map((k, v) => s"${k}=${URLEncoder.encode(v)}").mkString("&")
    s"${url(uri)}?${q}"

  end presign

  /** A delegation key valid past `untilMillis`, fetched with the Entra ID token when needed */
  private def userDelegationKey(untilMillis: Long): UserDelegationKey = synchronized {
    delegationKey.filter(_.expiryMillis >= untilMillis) match
      case Some(k) =>
        k
      case None =>
        val now = System.currentTimeMillis()
        // Azure accepts keys up to 7 days from now; leave a minute for clock skew
        val start  = AwsTime.isoSeconds(now - 60 * 1000L)
        val expiry = AwsTime.isoSeconds(now + MaxKeyMillis)
        val body   =
          s"""<?xml version="1.0" encoding="utf-8"?><KeyInfo><Start>${start}</Start><Expiry>${expiry}</Expiry></KeyInfo>"""
        val call = StorageCall(
          HttpMethod.POST,
          ObjectUri("", "", "az"),
          query = Seq("restype" -> "service", "comp" -> "userdelegationkey"),
          body = body.getBytes("UTF-8"),
          contentType = Some("application/xml")
        )
        val key = parseDelegationKey(bodyOf(checked(call, send(call))))
        delegationKey = Some(key)
        key
  }

end AzureObjectStore

object AzureObjectStore:
  private[storage] def quotedETag(etag: String): String =
    if etag.startsWith("\"") then
      etag
    else
      s"\"${etag}\""

  /** One request to the Blob service, before signing. `query` holds raw (not encoded) values. */
  private[storage] case class StorageCall(
      method: HttpMethod,
      uri: ObjectUri,
      query: Seq[(String, String)] = Nil,
      headers: Seq[(String, String)] = Nil,
      body: Array[Byte] = Array.emptyByteArray,
      contentType: Option[String] = None
  )

  /** One page of a listing, and the marker for the next page */
  private[storage] case class ListPage(entries: Seq[ObjectInfo], nextToken: Option[String])

  // How long a user delegation key is requested for: Azure's 7-day limit, less a minute of skew
  private val MaxKeyMillis: Long = AzureAuth.MaxPresignSeconds * 1000L - 60 * 1000L

  /** URI schemes this store accepts */
  val Schemes: Set[String] = Set("az", "azure")

  private[storage] def parseListing(prefix: ObjectUri, body: Array[Byte]): ListPage =
    val xml     = XmlReader.parse(utf8(body))
    val blobs   = xml.child("Blobs").map(_.children).getOrElse(Nil)
    val entries = blobs.flatMap { e =>
      e.name match
        case "Blob" =>
          val props                           = e.child("Properties")
          def prop(n: String): Option[String] = props.flatMap(_.childText(n)).filter(_.nonEmpty)
          Some(
            ObjectInfo(
              prefix.copy(key = nameOf(e)),
              prop("Content-Length").flatMap(_.trim.toLongOption).getOrElse(0L),
              prop("Etag"),
              prop("Last-Modified"),
              prop("Content-Type"),
              // Listings send the ETag without the quotes HEAD and PUT answers have
              version = prop("Etag").map(quotedETag)
            )
          )
        case "BlobPrefix" =>
          Some(ObjectInfo(prefix.copy(key = nameOf(e)), 0L, isDirectory = true))
        case _ =>
          None
    }
    // Objects first, then directories, as S3 listings return them
    val (dirs, objects) = entries.partition(_.isDirectory)
    ListPage(objects ++ dirs, xml.childText("NextMarker").map(_.trim).filter(_.nonEmpty))

  private def nameOf(e: XmlReader.Elem): String =
    e.child("Name") match
      case Some(n) if n.attribute("Encoded").exists(_.equalsIgnoreCase("true")) =>
        AzureAuth.percentDecode(n.text)
      case Some(n) =>
        n.text
      case None =>
        ""

  private[storage] def parseDelegationKey(body: Array[Byte]): UserDelegationKey =
    val xml                         = XmlReader.parse(utf8(body))
    def field(name: String): String = xml
      .childText(name)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(
        throw StorageException(
          CloudErrorCode.InvalidResponse,
          s"No ${name} in the Get User Delegation Key answer"
        )
      )
    UserDelegationKey(
      field("SignedOid"),
      field("SignedTid"),
      field("SignedStart"),
      field("SignedExpiry"),
      field("SignedService"),
      field("SignedVersion"),
      field("Value")
    )

  /**
    * Maps a failed response to a [[StorageException]] with Azure's error code: from the `<Error>`
    * body, or from `x-ms-error-code`, which HEAD responses carry instead of a body
    */
  private[storage] def errorOf(uri: ObjectUri, resp: Response): StorageException =
    val status = resp.statusCode
    val text   = resp.contentAsBytes.map(utf8).getOrElse("")
    val xml    =
      if text.contains("<Error") then
        try
          Some(XmlReader.parse(text))
        catch
          case _: StorageException =>
            None
      else
        None
    val code = xml
      .flatMap(_.childText("Code"))
      .filter(_.nonEmpty)
      .orElse(resp.header("x-ms-error-code").filter(_.nonEmpty))
      .getOrElse(
        status match
          case 404 =>
            StorageErrorCode.NoSuchKey
          case 403 =>
            CloudErrorCode.AccessDenied
          case s =>
            s"HTTP${s}"
      )
    // Azure appends RequestId and Time lines to the message
    val msg = xml
      .flatMap(_.childText("Message"))
      .flatMap(_.linesIterator.map(_.trim).find(_.nonEmpty))
      .getOrElse(s"HTTP ${status} ${resp.reason}".trim)
    StorageException(code, s"${uri}: ${code} ${msg}", Some(status))

  end errorOf

end AzureObjectStore

/**
  * Buffers writes into blocks and uploads each with Put Block as it fills; `close()` commits them
  * with Put Block List. A stream that never fills one block sends a single Put Blob. `abort()`, or
  * a failure, skips the commit: Azure has no call to drop uncommitted blocks, and discards them
  * after 7 days. They never become a blob, and an existing blob stays as it was.
  */
private[storage] class AzureOutputStream(
    store: AzureObjectStore,
    uri: ObjectUri,
    contentType: String,
    blockSize: Int
) extends ObjectOutputStream:
  // Azure needs every block id of a blob to have the same length, including blocks left by
  // earlier uploads. 36 bytes before Base64 matches the Azure SDKs, which use UUID strings.
  private val uploadTag = f"${scala.util.Random.nextInt()}%08x"
  private val ids       = Seq.newBuilder[String]
  private val buf       = new Array[Byte](blockSize)
  private var used      = 0
  private var blocks    = 0
  private var closed    = false
  private var failed    = false

  override def write(b: Int): Unit =
    val one = Array(b.toByte)
    write(one, 0, 1)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    ensureOpen()
    var i   = off
    val end = off + len
    while i < end do
      if used == blockSize then
        guarded(flushBlock())
      val n = math.min(blockSize - used, end - i)
      System.arraycopy(b, i, buf, used, n)
      used += n
      i += n

  private def ensureOpen(): Unit =
    if closed then
      throw java.io.IOException(s"The upload stream for ${uri} is closed")
    if failed then
      throw java.io.IOException(s"The upload to ${uri} failed")

  private def flushBlock(): Unit =
    blocks += 1
    if blocks > AzureConfig.MaxBlocks then
      throw StorageException(
        CloudErrorCode.InvalidConfig,
        s"${uri} needs more than ${AzureConfig
            .MaxBlocks} blocks; raise AzureConfig.withBlockSizeBytes"
      )
    val id = AzureOutputStream.blockId(uploadTag, blocks)
    store.putBlock(uri, id, java.util.Arrays.copyOf(buf, used))
    ids += id
    used = 0

  private def guarded[A](body: => A): A =
    try
      body
    catch
      case e: Throwable =>
        failed = true
        throw e

  /** Discards the upload: nothing written so far is published */
  override def abort(): Unit =
    closed = true
    failed = true

  override def close(): Unit =
    if !closed then
      closed = true
      if !failed then
        guarded {
          if blocks == 0 then
            store.put(uri, java.util.Arrays.copyOf(buf, used), contentType)
          else
            if used > 0 then
              flushBlock()
            store.putBlockList(uri, ids.result(), contentType)
        }

end AzureOutputStream

private[storage] object AzureOutputStream:
  /** Base64 of `<tag>-<number>`, always 36 bytes before encoding */
  def blockId(tag: String, number: Int): String = Base64.encode(
    f"${tag}-${number}%027d".getBytes("UTF-8")
  )
