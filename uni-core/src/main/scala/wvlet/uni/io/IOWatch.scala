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

/**
  * Type of file system watch event.
  */
enum WatchEventType:
  case Created
  case Modified
  case Deleted
  case Overflow

/**
  * A file system watch event indicating a change to a file or directory.
  */
case class WatchEvent(eventType: WatchEventType, path: IOPath)

/**
  * Options for configuring file system watching.
  */
case class WatchOptions(
    /** Watch subdirectories recursively */
    recursive: Boolean = false,
    /** Polling interval in milliseconds for platforms that use polling */
    pollingIntervalMs: Long = 500
):
  def withRecursive(value: Boolean): WatchOptions   = copy(recursive = value)
  def withPollingIntervalMs(ms: Long): WatchOptions = copy(pollingIntervalMs = ms)

object WatchOptions:
  val default: WatchOptions = WatchOptions()

/**
  * A handle to a running file system watcher. Call `close()` to stop watching.
  */
trait IOWatcher extends AutoCloseable:
  def close(): Unit

/**
  * Cross-platform file system watcher. Watches a directory for file creation, modification, and
  * deletion events.
  *
  * Usage:
  * {{{
  * import wvlet.uni.io.*
  *
  * val watcher = IOWatch.watch(IOPath("my-dir")) { event =>
  *   println(s"${event.eventType}: ${event.path}")
  * }
  * // ... later
  * watcher.close()
  * }}}
  *
  * Platform implementations:
  *   - JVM: Uses `java.nio.file.WatchService`
  *   - Node.js: Uses `fs.watch`
  *   - Native: Uses polling-based approach
  *   - Browser: Not supported
  */
/**
  * Base trait for platform-specific watch implementations.
  */
trait IOWatchBase:

  /**
    * Watch a directory for file system events.
    *
    * @param path
    *   the directory to watch
    * @param options
    *   watch configuration
    * @param handler
    *   callback invoked for each watch event
    * @return
    *   an IOWatcher handle; call `close()` to stop watching
    */
  def watch(path: IOPath, options: WatchOptions = WatchOptions.default)(
      handler: WatchEvent => Unit
  ): IOWatcher

/**
  * Cross-platform file system watcher. Watches a directory for file creation, modification, and
  * deletion events.
  *
  * Usage:
  * {{{
  * import wvlet.uni.io.*
  *
  * val watcher = IOWatch.watch(IOPath("my-dir")) { event =>
  *   println(s"${event.eventType}: ${event.path}")
  * }
  * // ... later
  * watcher.close()
  * }}}
  *
  * Platform implementations:
  *   - JVM: Uses `java.nio.file.WatchService`
  *   - Node.js: Uses `fs.watch`
  *   - Native: Uses polling-based approach
  *   - Browser: Not supported
  */
object IOWatch extends IOWatchBase:
  import scala.compiletime.uninitialized

  @volatile
  private var _impl: IOWatchBase = uninitialized

  private[io] def setImplementation(impl: IOWatchBase): Unit = _impl = impl

  private def impl: IOWatchBase =
    if _impl == null then
      throw IllegalStateException(
        "IOWatch not initialized. Platform-specific initialization required."
      )
    _impl

  override def watch(path: IOPath, options: WatchOptions)(handler: WatchEvent => Unit): IOWatcher =
    impl.watch(path, options)(handler)

end IOWatch
