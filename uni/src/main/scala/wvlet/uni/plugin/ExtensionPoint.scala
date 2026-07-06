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
package wvlet.uni.plugin

/**
  * A typed contribution slot that plugins contribute values to and hosts consume.
  *
  * The core plugin model knows nothing about *what* plugins contribute; each layer defines its own
  * extension points next to the contributed types (e.g. `RPCPlugin.routerPoint` in
  * `wvlet.uni.http.rpc` for RPC routers, [[Command.point]] for commands), keeping the dependency
  * arrows pointing into `wvlet.uni.plugin` rather than out of it.
  *
  * Points are compared by identity, so define each one as a shared `val` or `object`:
  *
  * {{{
  *   val viewPoint: ExtensionPoint[ViewFactory] = ExtensionPoint("app.view")
  *
  *   // In a plugin:
  *   context.contribute(viewPoint)(myViewFactory)
  *   // In the host application:
  *   host.contributions(viewPoint).foreach(render)
  * }}}
  *
  * @param name
  *   human-readable name used in error messages
  * @param keyOf
  *   when set, each contribution's key must be unique host-wide; a duplicate key — within a plugin
  *   or across plugins — is rejected at activation time, so conflicts surface in tests rather than
  *   at runtime.
  */
class ExtensionPoint[A](val name: String, val keyOf: Option[A => String] = None):
  override def toString: String = s"ExtensionPoint(${name})"

object ExtensionPoint:
  /**
    * An extension point whose contributions are identified by a unique string key, such as a
    * command id. Duplicate keys are rejected when a plugin is activated.
    */
  def keyed[A](name: String)(key: A => String): ExtensionPoint[A] = ExtensionPoint(name, Some(key))
