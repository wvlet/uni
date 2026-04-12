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
package wvlet.uni.io

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
  * Scala Native implementation of IOWatch using a polling-based approach. Periodically scans the
  * directory and compares file modification times to detect changes.
  */
private[io] object IOWatchNative extends IOWatchBase:

  override def watch(path: IOPath, options: WatchOptions)(handler: WatchEvent => Unit): IOWatcher =
    val rootFile = File(path.path)
    if !rootFile.isDirectory then
      throw IOOperationException(s"Watch path is not a directory: ${path.path}")

    val running = AtomicBoolean(true)

    @volatile
    var snapshot = scanDirectory(rootFile, options.recursive)

    val thread = Thread(() =>
      while running.get() do
        try
          Thread.sleep(options.pollingIntervalMs)
          if running.get() then
            val current = scanDirectory(rootFile, options.recursive)

            current.foreach { (filePath, modTime) =>
              snapshot.get(filePath) match
                case None =>
                  handler(WatchEvent(WatchEventType.Created, IOPath.parse(filePath)))
                case Some(oldModTime) if oldModTime != modTime =>
                  handler(WatchEvent(WatchEventType.Modified, IOPath.parse(filePath)))
                case _ => // No change
            }

            snapshot.foreach { (filePath, _) =>
              if !current.contains(filePath) then
                handler(WatchEvent(WatchEventType.Deleted, IOPath.parse(filePath)))
            }

            snapshot = current
        catch
          case _: InterruptedException =>
            running.set(false)
    )
    thread.setDaemon(true)
    thread.setName(s"io-watch-${path.path}")
    thread.start()

    new IOWatcher:
      override def close(): Unit =
        running.set(false)
        thread.interrupt()
        thread.join(1000)

  end watch

  private def scanDirectory(dir: File, recursive: Boolean): Map[String, Long] =
    val result = Map.newBuilder[String, Long]

    def scan(d: File): Unit =
      val children = d.listFiles()
      if children != null then
        children.foreach { child =>
          result += (child.getAbsolutePath -> child.lastModified())
          if recursive && child.isDirectory then
            scan(child)
        }

    scan(dir)
    result.result()

end IOWatchNative
