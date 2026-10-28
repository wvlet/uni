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
package wvlet.uni.config

import wvlet.uni.http.NodeModules
import wvlet.uni.io.IOPath
import wvlet.uni.rx.Cancelable

import scala.scalajs.js

/**
  * The Node.js counterpart of [[FileStoreBackend]], with the same file layout. It loads `fs` at
  * runtime so that linking it into a browser bundle does not add a Node-only import.
  */
class NodeFileStoreBackend(val dir: IOPath) extends StoreBackend:
  private def fs: js.Dynamic              = NodeModules.builtin("fs")
  private def pathOf(key: String): String = (dir / s"${key}.json").path

  override def read(key: String): Option[String] =
    val path = pathOf(key)
    if fs.existsSync(path).asInstanceOf[Boolean] then
      Some(fs.readFileSync(path, "utf8").toString)
    else
      None

  override def write(key: String, value: String): Unit =
    fs.mkdirSync(dir.path, js.Dynamic.literal(recursive = true))
    val tmp = (dir / s".${key}.json.tmp-${scala.util.Random.alphanumeric.take(8).mkString}").path
    try
      fs.writeFileSync(tmp, value, js.Dynamic.literal(encoding = "utf8", flag = "wx"))
      fs.renameSync(tmp, pathOf(key))
    finally
      if fs.existsSync(tmp).asInstanceOf[Boolean] then
        fs.unlinkSync(tmp)

  override def remove(key: String): Unit =
    val path = pathOf(key)
    if fs.existsSync(path).asInstanceOf[Boolean] then
      fs.unlinkSync(path)

  override def moveAside(key: String): Unit =
    val path = pathOf(key)
    if fs.existsSync(path).asInstanceOf[Boolean] then
      fs.renameSync(path, s"${path}.corrupt-${System.currentTimeMillis()}")

  override def watch(key: String)(onChange: () => Unit): Cancelable =
    fs.mkdirSync(dir.path, js.Dynamic.literal(recursive = true))
    val fileName = s"${key}.json"
    val watcher  = fs.watch(
      dir.path,
      (_: String, name: js.Any) =>
        if name != null && name.toString == fileName then
          onChange()
    )
    Cancelable(() => watcher.close())

end NodeFileStoreBackend
