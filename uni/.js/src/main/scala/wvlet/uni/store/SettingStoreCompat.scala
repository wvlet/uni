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

import wvlet.uni.http.NodeModules
import wvlet.uni.io.IOPath

import scala.scalajs.js

/**
  * Scala.js code here must not reach `wvlet.uni.io.FileSystem`: its static `@JSImport("fs")` would
  * break every browser bundle that uses SettingStore. Node modules are loaded at runtime with
  * [[NodeModules.builtin]] instead.
  */
private[store] object SettingStoreCompat:
  def env(name: String): Option[String] =
    if NodeModules.isNode then
      val value = js.Dynamic.global.process.env.selectDynamic(name)
      if js.isUndefined(value) || value == null then
        None
      else
        Some(value.toString).filter(_.nonEmpty)
    else
      None

  def homeDir: IOPath =
    if NodeModules.isNode then
      IOPath.parse(NodeModules.builtin("os").homedir().toString)
    else
      IOPath.parse("/")

  def osName: String =
    if NodeModules.isNode then
      js.Dynamic.global.process.platform.toString match
        case "darwin" =>
          "mac"
        case "win32" =>
          "windows"
        case _ =>
          "other"
    else
      "other"

  def defaultBackend(config: SettingStoreConfig): StoreBackend =
    if NodeModules.isNode then
      NodeFileStoreBackend(config.directory)
    else
      LocalStorageBackend(config.appName)

end SettingStoreCompat
