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
package wvlet.uni.http

import scala.scalajs.js

/**
  * Helpers for loading Node.js built-in modules from Scala.js.
  */
private[http] object NodeModules:

  /**
    * Load a Node built-in module lazily at call time rather than via a static `@JSImport`.
    * `process.getBuiltinModule` (Node 20.16+ / 22.3+) works in both CommonJS and ESM; the global
    * `require` is the fallback for older Node / NoModule builds. Being a runtime call, this is
    * invisible to browser bundlers — a browser bundle still builds and simply never reaches here
    * because [[isNode]] is false.
    */
  def builtin(name: String): js.Dynamic =
    val process = js.Dynamic.global.process
    if !js.isUndefined(process) && !js.isUndefined(process.getBuiltinModule) then
      process.applyDynamic("getBuiltinModule")(name)
    else
      js.Dynamic.global.applyDynamic("require")(name)

  /**
    * True on a Node-compatible runtime, detected by `process.versions.node`. Bun and Deno both set
    * it (they target Node compatibility); browsers — including web workers — lack it.
    */
  def isNode: Boolean =
    !js.isUndefined(js.Dynamic.global.process) &&
      !js.isUndefined(js.Dynamic.global.process.versions) &&
      !js.isUndefined(js.Dynamic.global.process.versions.node)

end NodeModules
