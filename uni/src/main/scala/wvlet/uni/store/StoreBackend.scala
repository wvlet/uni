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
package wvlet.uni.store

import wvlet.uni.io.{FileSystem, IOPath, IOWatch, WriteMode}
import wvlet.uni.rx.Cancelable

import scala.collection.mutable

/**
  * Key-value storage of serialized [[SettingStore]] values
  */
trait StoreBackend:
  def read(key: String): Option[String]
  def write(key: String, value: String): Unit
  def remove(key: String): Unit

  /**
    * Keep unreadable data for inspection instead of overwriting it, and remove it from `key`
    */
  def moveAside(key: String): Unit

  /**
    * Call `onChange` when another process or tab may have changed `key`. The caller reads the new
    * data with [[read]].
    */
  def watch(key: String)(onChange: () => Unit): Cancelable = Cancelable.empty

/**
  * Stores each key in `<dir>/<key>.json`. A write goes to a temporary file first and then replaces
  * the target, so a crash during a write never leaves a partially written file.
  */
class FileStoreBackend(val dir: IOPath) extends StoreBackend:
  private def pathOf(key: String): IOPath = dir / s"${key}.json"

  override def read(key: String): Option[String] =
    val path = pathOf(key)
    if FileSystem.isFile(path) then
      Some(FileSystem.readString(path))
    else
      None

  override def write(key: String, value: String): Unit =
    FileSystem.createDirectoryIfNotExists(dir)
    val tmp = dir / s".${key}.json.tmp-${scala.util.Random.alphanumeric.take(8).mkString}"
    try
      FileSystem.writeString(tmp, value, WriteMode.CreateNew)
      FileSystem.move(tmp, pathOf(key), overwrite = true)
    finally
      FileSystem.deleteIfExists(tmp)

  override def remove(key: String): Unit = FileSystem.deleteIfExists(pathOf(key))

  override def moveAside(key: String): Unit =
    val path = pathOf(key)
    if FileSystem.exists(path) then
      FileSystem.move(path, dir / s"${key}.json.corrupt-${System.currentTimeMillis()}")

  override def watch(key: String)(onChange: () => Unit): Cancelable =
    FileSystem.createDirectoryIfNotExists(dir)
    val fileName = s"${key}.json"
    val watcher  =
      IOWatch.watch(dir) { event =>
        if event.path.fileName == fileName then
          onChange()
      }
    Cancelable(() => watcher.close())

end FileStoreBackend

/**
  * Keeps values in memory only. Useful for tests.
  */
class InMemoryStoreBackend extends StoreBackend:
  private val entries = mutable.Map.empty[String, String]

  override def read(key: String): Option[String]       = synchronized(entries.get(key))
  override def write(key: String, value: String): Unit = synchronized(entries(key) = value)
  override def remove(key: String): Unit               = synchronized(entries.remove(key))

  override def moveAside(key: String): Unit = synchronized {
    entries
      .remove(key)
      .foreach { v =>
        entries(s"${key}.corrupt-${System.currentTimeMillis()}") = v
      }
  }

  /**
    * All stored entries, including moved-aside ones
    */
  def snapshot: Map[String, String] = synchronized(entries.toMap)
