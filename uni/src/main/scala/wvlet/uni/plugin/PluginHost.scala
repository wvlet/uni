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

import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}

import scala.collection.mutable

/**
  * Activates [[Plugin]]s and owns the registries they contribute to: a host-global command registry
  * and the set of RPC routers. This is the runtime an app embeds; the plugin testing harness
  * ([[wvlet.uni.plugin.testing.PluginTestHost]]) drives the very same host so tests exercise the
  * real activation path.
  *
  * Activation is eager and synchronous (as in VSCode's `activate`). Contributions are flat and
  * host-global, so a duplicate command id — within a plugin or across plugins — is rejected, which
  * surfaces plugin conflicts in tests instead of at runtime.
  */
class PluginHost:
  private val commands        = mutable.LinkedHashMap.empty[String, Seq[Any] => Any]
  private val _routers        = mutable.ListBuffer.empty[RPCRouter]
  private val deactivateHooks = mutable.ListBuffer.empty[() => Unit]
  private val _activated      = mutable.ListBuffer.empty[String]

  private def contextFor(pluginId: String): PluginContext =
    new PluginContext:
      override def registerCommand(id: String)(handler: Seq[Any] => Any): Unit =
        if commands.contains(id) then
          throw IllegalArgumentException(
            s"Command '${id}' is already registered (attempted by plugin '${pluginId}')"
          )
        commands(id) = handler

      override def registerRpcRouter(router: RPCRouter): Unit = _routers += router

      override def onDeactivate(hook: () => Unit): Unit = deactivateHooks += hook

  /** Activate a single plugin. Returns this host for chaining. */
  def activate(plugin: Plugin): PluginHost =
    if _activated.contains(plugin.id) then
      throw IllegalArgumentException(s"Plugin '${plugin.id}' is already activated")
    plugin.activate(contextFor(plugin.id))
    _activated += plugin.id
    this

  /** Activate several plugins in order. */
  def activateAll(plugins: Plugin*): PluginHost =
    plugins.foreach(activate)
    this

  /** Ids of plugins activated so far, in activation order. */
  def activatedPlugins: Seq[String] = _activated.toSeq

  /** All registered command ids. */
  def commandIds: Set[String] = commands.keySet.toSet

  def hasCommand(id: String): Boolean = commands.contains(id)

  /**
    * Invoke a registered command by id with the given arguments, returning its result.
    *
    * @throws java.util.NoSuchElementException
    *   if no command is registered under `id`.
    */
  def executeCommand(id: String, args: Any*): Any =
    commands.get(id) match
      case Some(handler) =>
        handler(args.toSeq)
      case None =>
        throw java
          .util
          .NoSuchElementException(
            s"No command registered with id '${id}'. Available: ${commandIds
                .toSeq
                .sorted
                .mkString(", ")}"
          )

  /** All RPC routers contributed by activated plugins, in registration order. */
  def routers: Seq[RPCRouter] = _routers.toSeq

  /** An [[RPCDispatcher]] over all contributed routers, ready to serve (e.g. via Electron IPC). */
  def dispatcher: RPCDispatcher = RPCDispatcher(routers*)

  /**
    * Run deactivation hooks (FILO) and clear all contributions, returning the host to a pristine,
    * re-activatable state. Hook failures are swallowed so teardown always completes.
    */
  def deactivate(): Unit =
    deactivateHooks
      .reverseIterator
      .foreach { hook =>
        try
          hook()
        catch
          case _: Throwable =>
            ()
      }
    deactivateHooks.clear()
    commands.clear()
    _routers.clear()
    _activated.clear()

end PluginHost
