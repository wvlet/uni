package wvlet.uni.cloud.parquet

import wvlet.uni.http.HttpMethod
import wvlet.uni.rx.Rx
import wvlet.uni.storage.ObjectInfo
import wvlet.uni.storage.ObjectOutputStream
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri

import scala.collection.mutable

/**
  * A working object store held in memory, for tests of the async dataset writer. Only the async
  * methods work, as in a browser, so a test fails if the writer makes a sync call. `failPuts` makes
  * every put after that many succeed fail.
  */
class MemoryObjectStore(var failPuts: Option[Int] = None) extends ObjectStore:
  private val objects  = mutable.TreeMap.empty[String, Array[Byte]]
  private val versions = mutable.Map.empty[String, String]
  private var puts     = 0

  private def syncCall(name: String): Nothing =
    throw UnsupportedOperationException(s"${name} is sync; the async writer must not call it")

  def keys(prefix: String): Seq[String] = synchronized(
    objects.keys.filter(_.startsWith(prefix)).toSeq
  )

  override def head(uri: ObjectUri): Option[ObjectInfo]                          = syncCall("head")
  override def get(uri: ObjectUri): Array[Byte]                                  = syncCall("get")
  override def getRange(uri: ObjectUri, offset: Long, length: Long): Array[Byte] = syncCall(
    "getRange"
  )

  override def put(uri: ObjectUri, content: Array[Byte], contentType: String): Unit = syncCall(
    "put"
  )

  override def list(uri: ObjectUri, recursive: Boolean): Seq[ObjectInfo] = syncCall("list")
  override def delete(uri: ObjectUri): Unit                              = syncCall("delete")
  override def newOutputStream(uri: ObjectUri, contentType: String): ObjectOutputStream = syncCall(
    "newOutputStream"
  )

  override def presign(uri: ObjectUri, method: HttpMethod, expiresSeconds: Long): String = syncCall(
    "presign"
  )

  private def info(key: String, bytes: Array[Byte]): ObjectInfo = ObjectInfo(
    ObjectUri.parse(key),
    bytes.length.toLong,
    version = versions.get(key)
  )

  // Puts run on several threads on the JVM, so every access to the objects holds the lock
  def size: Int = synchronized(objects.size)

  override def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]] = Rx.single(
    synchronized(objects.get(uri.toString)).map(info(uri.toString, _))
  )

  override def getAsync(uri: ObjectUri): Rx[Array[Byte]] = Rx.single(
    synchronized(objects.get(uri.toString)).getOrElse(
      throw java.util.NoSuchElementException(uri.toString)
    )
  )

  override def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]] =
    getAsync(uri).map(b => b.slice(offset.toInt, (offset + length).toInt))

  override def putAsync(uri: ObjectUri, content: Array[Byte], contentType: String): Rx[Unit] = Rx
    .single {
      synchronized {
        if failPuts.exists(puts >= _) then
          throw java.io.IOException(s"put failed: ${uri}")
        store(uri.toString, content)
        ()
      }
    }

  override def putIfAbsent(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String
  ): Option[String] = syncCall("putIfAbsent")

  override def putIfMatch(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String
  ): Option[String] = syncCall("putIfMatch")

  // The version of a stored object is the put count when it was written
  private def store(key: String, content: Array[Byte]): String =
    puts += 1
    objects(key) = content.clone()
    versions(key) = puts.toString
    puts.toString

  override def putIfMatchAsync(
      uri: ObjectUri,
      content: Array[Byte],
      version: String,
      contentType: String
  ): Rx[Option[String]] = Rx.single {
    synchronized {
      if versions.get(uri.toString).contains(version) then
        Some(store(uri.toString, content))
      else
        None
    }
  }

  /** Called before each `putIfAbsentAsync`, so a test can commit a racing version first */
  var beforeCreate: ObjectUri => Unit = _ => ()

  override def putIfAbsentAsync(
      uri: ObjectUri,
      content: Array[Byte],
      contentType: String
  ): Rx[Option[String]] = Rx.single {
    beforeCreate(uri)
    val created = synchronized {
      if objects.contains(uri.toString) then
        None
      else
        Some(store(uri.toString, content))
    }
    if created.isDefined && loseCreateResponse then
      throw java.io.IOException(s"connection reset after creating ${uri}")
    created
  }

  /** Makes a create that succeeded fail on the client, as when its response is lost */
  var loseCreateResponse: Boolean = false

  def putObject(key: String, content: Array[Byte]): Unit = synchronized {
    objects(key) = content.clone()
  }

  def getObject(key: String): Option[Array[Byte]] = synchronized(objects.get(key))

  override def listAsync(uri: ObjectUri, recursive: Boolean): Rx[Seq[ObjectInfo]] = Rx.single(
    synchronized(
      objects.iterator.filter(_._1.startsWith(uri.toString)).map((k, v) => info(k, v)).toSeq
    )
  )

  override def deleteAsync(uri: ObjectUri): Rx[Unit] = Rx.single(
    synchronized {
      objects.remove(uri.toString)
      versions.remove(uri.toString)
      ()
    }
  )

end MemoryObjectStore
