package wvlet.uni.parquet

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
  val objects      = mutable.TreeMap.empty[String, Array[Byte]]
  private var puts = 0

  private def syncCall(name: String): Nothing =
    throw UnsupportedOperationException(s"${name} is sync; the async writer must not call it")

  def keys(prefix: String): Seq[String] = objects.keys.filter(_.startsWith(prefix)).toSeq

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
    bytes.length.toLong
  )

  override def headAsync(uri: ObjectUri): Rx[Option[ObjectInfo]] = Rx.single(
    objects.get(uri.toString).map(info(uri.toString, _))
  )

  override def getAsync(uri: ObjectUri): Rx[Array[Byte]] = Rx.single(
    objects.getOrElse(uri.toString, throw java.util.NoSuchElementException(uri.toString))
  )

  override def getRangeAsync(uri: ObjectUri, offset: Long, length: Long): Rx[Array[Byte]] =
    getAsync(uri).map(b => b.slice(offset.toInt, (offset + length).toInt))

  override def putAsync(uri: ObjectUri, content: Array[Byte], contentType: String): Rx[Unit] = Rx
    .single {
      if failPuts.exists(puts >= _) then
        throw java.io.IOException(s"put failed: ${uri}")
      puts += 1
      objects(uri.toString) = content.clone()
    }

  override def listAsync(uri: ObjectUri, recursive: Boolean): Rx[Seq[ObjectInfo]] = Rx.single(
    objects.iterator.filter(_._1.startsWith(uri.toString)).map((k, v) => info(k, v)).toSeq
  )

  override def deleteAsync(uri: ObjectUri): Rx[Unit] = Rx.single(objects.remove(uri.toString))

end MemoryObjectStore
