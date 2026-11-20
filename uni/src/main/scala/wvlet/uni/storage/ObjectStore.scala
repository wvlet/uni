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

import wvlet.uni.rx.Rx

/**
  * An object location such as `s3://bucket/path/to/key`. The scheme only names the location; the
  * store's endpoint decides which service answers, so `s3://` also addresses R2, GCS, or MinIO
  * buckets through an [[S3ObjectStore]] configured for them.
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
  */
case class ObjectInfo(
    uri: ObjectUri,
    size: Long,
    etag: Option[String] = None,
    lastModified: Option[String] = None,
    contentType: Option[String] = None,
    isDirectory: Boolean = false
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

  def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]]
  def getAsync(uri: ObjectUri): Rx[Array[Byte]]
  def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]]
  def putAsync(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String = ObjectStore.Binary
  ): Rx[Unit]

  def listAsync(uri: ObjectUri, recursive: Boolean = true): Rx[Seq[ObjectInfo]]
  def deleteAsync(uri: ObjectUri): Rx[Unit]

  // String overloads for `s3://bucket/key`
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

  override def close(): Unit = ()

end ObjectStore

object ObjectStore:
  val Binary: String = "application/octet-stream"

  /** An S3 (or S3-compatible) store. See [[S3Config]] for R2, GCS, and MinIO presets. */
  def s3(config: S3Config = S3Config.default): S3ObjectStore = S3ObjectStore(config)

/**
  * An upload stream. `close()` publishes the object; `abort()` discards everything written so far,
  * and no object appears.
  */
abstract class ObjectOutputStream extends java.io.OutputStream:
  def abort(): Unit

/** Error codes. S3 codes such as `NoSuchKey` or `AccessDenied` pass through as sent. */
object StorageErrorCode:
  val NoSuchKey: String       = "NoSuchKey"
  val NoSuchBucket: String    = "NoSuchBucket"
  val AccessDenied: String    = "AccessDenied"
  val WrongRegion: String     = "WrongRegion"
  val NoCredentials: String   = "NoCredentials"
  val InvalidResponse: String = "InvalidResponse"

/**
  * An object store refused or failed a request
  *
  * @param code
  *   the store's error code, such as `NoSuchKey`, `AccessDenied`, or `SlowDown`
  * @param statusCode
  *   the HTTP status, when the store answered
  */
class StorageException(
    val code: String,
    message: String,
    val statusCode: Option[Int] = None,
    cause: Throwable = null
) extends Exception(message, cause):
  def isNotFound: Boolean = code == StorageErrorCode.NoSuchKey || statusCode.contains(404)
