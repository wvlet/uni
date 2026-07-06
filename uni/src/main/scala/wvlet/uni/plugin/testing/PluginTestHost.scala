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
package wvlet.uni.plugin.testing

import wvlet.uni.plugin.{Plugin, PluginHost}

/**
  * Activate plugins in isolation for testing — the plugin layer of Uni's testing pyramid (unit → UI
  * → Electron → plugin).
  *
  * Each helper builds a fresh [[PluginHost]] and activates the given plugin(s) through the real
  * activation path, returning the host so a test can assert on its extension-point contributions
  * (`host.contributions(point)`, or point-specific views such as `commandIds` / `rpcRouters`),
  * invoke commands (`executeCommand`), and verify teardown (`deactivate()`). Because the host is
  * cross-platform, plugin tests run on the JVM with no browser or Electron runtime.
  *
  * {{{
  *   import wvlet.uni.plugin.Command.*
  *
  *   val host = PluginTestHost.activate(NotesPlugin())
  *   host.commandIds shouldContain "notes.new"
  *   host.executeCommand("notes.new") shouldBe expectedNote
  * }}}
  */
object PluginTestHost:

  /** Activate a single plugin against a fresh host and return it for assertions. */
  def activate(plugin: Plugin): PluginHost = PluginHost().activate(plugin)

  /**
    * Activate several plugins together against one host — useful for testing inter-plugin
    * interactions such as conflicting command ids.
    */
  def activateAll(plugins: Plugin*): PluginHost = PluginHost().activateAll(plugins*)
