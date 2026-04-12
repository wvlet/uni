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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private[io] object IOWatchJvm extends IOWatchBase:

  override def watch(path: IOPath, options: WatchOptions)(handler: WatchEvent => Unit): IOWatcher =
    val nioPath = Paths.get(path.path)
    if !Files.isDirectory(nioPath) then
      throw IOOperationException(s"Watch path is not a directory: ${path.path}")

    val watchService = FileSystems.getDefault.newWatchService()
    val keyToPath    = java.util.concurrent.ConcurrentHashMap[WatchKey, Path]()
    val running      = AtomicBoolean(true)

    def registerDirectory(dir: Path): Unit =
      val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
      keyToPath.put(key, dir)

    def registerTree(root: Path): Unit = Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if !running.get() then
            FileVisitResult.TERMINATE
          else
            registerDirectory(dir)
            FileVisitResult.CONTINUE

        override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
          FileVisitResult.CONTINUE
    )

    if options.recursive then
      registerTree(nioPath)
    else
      registerDirectory(nioPath)
    val thread  = Thread(() =>
      while running.get() do
        try
          val key = watchService.poll(options.pollingIntervalMs, TimeUnit.MILLISECONDS)
          if key != null then
            val dir = keyToPath.get(key)
            if dir != null then
              val events = key.pollEvents()
              events.forEach { event =>
                val kind = event.kind()
                if kind != OVERFLOW then
                  val context   = event.context().asInstanceOf[Path]
                  val fullPath  = dir.resolve(context)
                  val ioPath    = IOPath.parse(fullPath.toString)
                  val eventType =
                    if kind == ENTRY_CREATE then
                      WatchEventType.Created
                    else if kind == ENTRY_MODIFY then
                      WatchEventType.Modified
                    else
                      WatchEventType.Deleted

                  handler(WatchEvent(eventType, ioPath))

                  // If recursive and a new directory was created, register its entire subtree
                  if options.recursive && kind == ENTRY_CREATE then
                    try
                      if Files.isDirectory(fullPath) then
                        registerTree(fullPath)
                    catch
                      case _: java.io.IOException => // Directory may have been removed
                else
                  handler(WatchEvent(WatchEventType.Overflow, IOPath.parse(dir.toString)))
              }
              if !key.reset() then
                keyToPath.remove(key)
            end if
          end if
        catch
          case _: java.nio.file.ClosedWatchServiceException =>
            running.set(false)
          case _: InterruptedException =>
            running.set(false)
    )
    thread.setDaemon(true)
    thread.setName(s"io-watch-${path.path}")
    thread.start()

    new IOWatcher:
      override def close(): Unit =
        running.set(false)
        watchService.close()
        thread.interrupt()
        thread.join(1000)

  end watch

end IOWatchJvm
