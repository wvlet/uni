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

import wvlet.uni.io.IOPath

private[store] object SettingStoreCompat:
  def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)

  def homeDir: IOPath = IOPath.homeDir

  def osName: String =
    val os = System.getProperty("os.name", "").toLowerCase
    if os.contains("mac") || os.contains("darwin") then
      "mac"
    else if os.contains("win") then
      "windows"
    else
      "other"

  def defaultBackend(config: SettingStoreConfig): StoreBackend = FileStoreBackend(config.directory)
