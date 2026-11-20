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
package wvlet.uni.storage

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

/**
  * Reads environment variables for credential and region lookup. Node.js has `process.env`;
  * browsers have no environment, so every lookup is empty there.
  */
private[storage] object StorageEnv:
  def isNode: Boolean =
    js.typeOf(g.process) != "undefined" && js.typeOf(g.process.versions) != "undefined" &&
      js.typeOf(g.process.versions.node) != "undefined"

  def isBrowser: Boolean = !isNode

  def isWindows: Boolean = isNode && g.process.platform.asInstanceOf[String] == "win32"

  def get(name: String): Option[String] =
    if isNode then
      val value = g.process.env.selectDynamic(name)
      if js.isUndefined(value) || value == null then
        None
      else
        Some(value.toString).filter(_.nonEmpty)
    else
      None
