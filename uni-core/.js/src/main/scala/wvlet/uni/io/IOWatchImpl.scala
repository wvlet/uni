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

import scala.scalajs.js

/**
  * Node.js fs.watch facade.
  */
@js.native
private[io] trait NodeFSWatcher extends js.Object:
  def close(): Unit = js.native

private[io] object NodeFSWatchModule:
  def watch(
      path: String,
      options: js.Object,
      listener: js.Function2[String, String, Unit]
  ): NodeFSWatcher = NodeFSModule
    .asInstanceOf[js.Dynamic]
    .watch(path, options, listener)
    .asInstanceOf[NodeFSWatcher]

private[io] object IOWatchJS extends IOWatchBase:

  private def supportsRecursiveWatch: Boolean =
    val platform = NodeOSModule.platform()
    platform == "darwin" || platform == "win32"

  override def watch(path: IOPath, options: WatchOptions)(handler: WatchEvent => Unit): IOWatcher =
    if !FileSystem.isNode then
      throw UnsupportedOperationException("IOWatch is not supported in browser environments")

    val isDir =
      try
        NodeFSModule.statSync(path.path).isDirectory()
      catch
        case _: Throwable =>
          false
    if !isDir then
      throw IOOperationException(s"Watch path is not a directory: ${path.path}")

    if options.recursive && !supportsRecursiveWatch then
      throw UnsupportedOperationException(
        "Recursive watching is not supported on this platform in Node.js. " +
          "Use non-recursive watching or switch to JVM."
      )

    val watchOptions = js.Dynamic.literal(recursive = options.recursive, persistent = true)

    val listener: js.Function2[String, String, Unit] =
      (eventType: String, filename: String) =>
        if filename != null then
          try
            val fullPath       = IOPath.parse(NodePathModule.join(path.path, filename))
            val watchEventType =
              eventType match
                case "rename" =>
                  if NodeFSModule.existsSync(fullPath.path) then
                    WatchEventType.Created
                  else
                    WatchEventType.Deleted
                case "change" =>
                  WatchEventType.Modified
                case _ =>
                  WatchEventType.Modified
            handler(WatchEvent(watchEventType, fullPath))
          catch
            case _: Throwable =>
              ()

    val fsWatcher = NodeFSWatchModule.watch(path.path, watchOptions, listener)

    new IOWatcher:
      override def close(): Unit = fsWatcher.close()

  end watch

end IOWatchJS
