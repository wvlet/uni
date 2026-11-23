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

import wvlet.uni.http.HttpMethod
import wvlet.uni.rx.Rx

/**
  * An object location such as `s3://bucket/path/to/key` or `gs://bucket/path/to/key`. The scheme
  * only names the location; the store decides which service answers, so `s3://` also addresses R2
  * or MinIO buckets through an [[S3ObjectStore]] configured for them, `gs://` goes to Google Cloud
  * Storage through a [[GcsObjectStore]], and `az://container/blob` to Azure Blob Storage through an
  * [[AzureObjectStore]], whose [[AzureConfig]] names the storage account.
  */
case class ObjectUri(bucket: String, key: String, scheme: String = "s3"):
  /** A key under this one, joined with `/` */
  def resolve(child: String): ObjectUri =
    val sep =
      if key.isEmpty || key.endsWith("/") then
        ""
      else
        "/"
    copy(key = s"${key}${sep}${child.stripPrefix("/")}")

  override def toString: String = s"${scheme}://${bucket}/${key}"

object ObjectUri:
  /** Parses `scheme://bucket/key` (for example `s3://`, `gs://`, or `r2://`) */
  def parse(uri: String): ObjectUri =
    val i = uri.indexOf("://")
    if i <= 0 then
      throw IllegalArgumentException(s"Not an object URI (expected s3://bucket/key): ${uri}")
    val scheme        = uri.substring(0, i)
    val rest          = uri.substring(i + 3)
    val slash         = rest.indexOf('/')
    val (bucket, key) =
      if slash < 0 then
        (rest, "")
      else
        (rest.substring(0, slash), rest.substring(slash + 1))
    if bucket.isEmpty then
      throw IllegalArgumentException(s"No bucket name in ${uri}")
    ObjectUri(bucket, key, scheme)

/**
  * An object, or a common prefix (a "directory") when `isDirectory` is true
  *
  * @param lastModified
  *   the store's timestamp as sent (ISO-8601 in listings, RFC 1123 in HEAD responses)
  * @param version
  *   the token [[ObjectStore.putIfMatch]] compares: the ETag on S3 and Azure, the generation on
  *   GCS. Pass it back unchanged; `head` and `list` give the same token for the same object.
  */
case class ObjectInfo(
    uri: ObjectUri,
    size: Long,
    etag: Option[String] = None,
    lastModified: Option[String] = None,
    contentType: Option[String] = None,
    isDirectory: Boolean = false,
    version: Option[String] = None
)

/**
  * Reads and writes objects in an object store. The synchronous methods run on the JVM, Scala
  * Native, and Node.js; the `...Async` methods run everywhere, including browsers.
  *
  * Missing objects and refused requests raise [[StorageException]]; `head` returns `None` for a
  * missing object instead.
  */
trait ObjectStore extends AutoCloseable:
  def head(uri: ObjectUri): Option[ObjectInfo]

  /** The whole object */
  def get(uri: ObjectUri): Array[Byte]

  /** `length` bytes starting at `offset` (fewer at the end of the object) */
  def getRange(uri: ObjectUri, offset: Long, length: Long): Array[Byte]

  def put(uri: ObjectUri, content: Array[Byte], contentType: String = ObjectStore.Binary): Unit

  /**
    * Creates the object only if nothing is stored at `uri` yet. Returns the new object's version
    * (see [[ObjectInfo.version]]) when this call created it, and `None` when an object was already
    * there. Of several concurrent calls for one key, at most one returns a version, so this can
    * commit a version file or take a lease (S3 `If-None-Match: *`, GCS `ifGenerationMatch=0`, Azure
    * `If-None-Match: *`).
    */
  def putIfAbsent(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String = ObjectStore.Binary
  ): Option[String]

  /**
    * Replaces the object only if its current version is still `version` (from
    * [[ObjectInfo.version]] or an earlier put). Returns the new version when this call replaced it,
    * and `None` when the object changed or no longer exists. Of several concurrent calls with the
    * same version, at most one succeeds, so this can swap a pointer or renew a lease (S3 and Azure
    * `If-Match`, GCS `ifGenerationMatch`). Other failures raise [[StorageException]].
    *
    * Call `head` before `get` when you read the object to change: if another writer lands in
    * between, the swap fails instead of committing changes to stale bytes.
    */
  def putIfMatch(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String = ObjectStore.Binary
  ): Option[String]

  /**
    * Objects under the prefix `uri`. With `recursive = false`, keys are grouped at the next `/` and
    * each group is returned once as a directory entry.
    */
  def list(uri: ObjectUri, recursive: Boolean = true): Seq[ObjectInfo]

  /** Deletes the object. Deleting a missing object succeeds. */
  def delete(uri: ObjectUri): Unit

  /**
    * A stream that uploads what is written to it. The object appears when the stream is closed.
    * Call `abort()` instead of `close()` to discard a partial upload after an error.
    */
  def newOutputStream(uri: ObjectUri, contentType: String = ObjectStore.Binary): ObjectOutputStream

  /**
    * A URL that lets anyone holding it send `method` to the object until it expires (at most 7
    * days). Use GET to share a download, PUT to accept an upload (a browser can `fetch` it; Azure
    * also needs an `x-ms-blob-type: BlockBlob` header on the upload). Fails with
    * [[wvlet.uni.cloud.CloudErrorCode.NoCredentials]] when the store's credentials can't sign.
    */
  def presign(
      uri: ObjectUri,
      method: HttpMethod = HttpMethod.GET,
      expiresSeconds: Long = 3600
  ): String

  def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]]
  def getAsync(uri: ObjectUri): Rx[Array[Byte]]
  def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]]
  def putAsync(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String = ObjectStore.Binary
  ): Rx[Unit]

  def putIfAbsentAsync(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String = ObjectStore.Binary
  ): Rx[Option[String]]

  def putIfMatchAsync(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String = ObjectStore.Binary
  ): Rx[Option[String]]

  def listAsync(uri: ObjectUri, recursive: Boolean = true): Rx[Seq[ObjectInfo]]
  def deleteAsync(uri: ObjectUri): Rx[Unit]

  // String overloads for `s3://bucket/key` or `gs://bucket/key`
  def head(uri: String): Option[ObjectInfo]                          = head(ObjectUri.parse(uri))
  def get(uri: String): Array[Byte]                                  = get(ObjectUri.parse(uri))
  def getRange(uri: String, offset: Long, length: Long): Array[Byte] = getRange(
    ObjectUri.parse(uri),
    offset,
    length
  )

  def put(uri: String, content: Array[Byte]): Unit = put(ObjectUri.parse(uri), content)
  def list(uri: String): Seq[ObjectInfo]           = list(ObjectUri.parse(uri))
  def delete(uri: String): Unit                    = delete(ObjectUri.parse(uri))

  def newOutputStream(uri: String): ObjectOutputStream = newOutputStream(ObjectUri.parse(uri))
  def presign(uri: String): String                     = presign(ObjectUri.parse(uri))

  override def close(): Unit = ()

end ObjectStore

object ObjectStore:
  val Binary: String = "application/octet-stream"

  /** An S3 (or S3-compatible) store. See [[S3Config]] for R2, GCS, and MinIO presets. */
  def s3(config: S3Config = S3Config.default): S3ObjectStore = S3ObjectStore(config)

  /**
    * Google Cloud Storage through its JSON API, with OAuth credentials found in Application Default
    * Credentials order. See [[GcsConfig]].
    */
  def gcs(config: GcsConfig = GcsConfig.default): GcsObjectStore = GcsObjectStore(config)

  /**
    * Azure Blob Storage. The default config reads `AZURE_STORAGE_CONNECTION_STRING`, or the account
    * from `AZURE_STORAGE_ACCOUNT` with the Azure credential chain. See [[AzureConfig]].
    */
  def azure(config: AzureConfig = AzureConfig.default): AzureObjectStore = AzureObjectStore(config)

/**
  * An upload stream. `close()` publishes the object; `abort()` discards everything written so far,
  * and no object appears.
  */
abstract class ObjectOutputStream extends java.io.OutputStream:
  def abort(): Unit

/**
  * Error codes. S3 codes such as `NoSuchKey` or `AccessDenied` pass through as sent; GCS errors map
  * to the same codes, or pass Google's `reason` (such as `rateLimitExceeded`) through; Azure codes
  * such as `BlobNotFound` pass through as sent. [[StorageException.isNotFound]] works on every
  * store. Codes shared with credential providers, such as `AccessDenied` and `NoCredentials`, are
  * in `wvlet.uni.cloud.CloudErrorCode`.
  */
object StorageErrorCode:
  val NoSuchKey: String    = "NoSuchKey"
  val NoSuchBucket: String = "NoSuchBucket"
  val WrongRegion: String  = "WrongRegion"

/**
  * An object store refused or failed a request
  *
  * @param code
  *   the store's error code, such as `NoSuchKey`, `AccessDenied`, or `SlowDown`
  * @param statusCode
  *   the HTTP status, when the store answered
  */
class StorageException(
    code: String,
    message: String,
    statusCode: Option[Int] = None,
    cause: Throwable = null
) extends CloudException(code, message, statusCode, cause):
  def isNotFound: Boolean = code == StorageErrorCode.NoSuchKey || statusCode.contains(404)
