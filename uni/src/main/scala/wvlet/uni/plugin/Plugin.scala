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
  * Context handed to a [[Plugin]]'s [[Plugin.activate]], through which the plugin contributes
  * values to the host's [[ExtensionPoint]]s. Modeled on VSCode's `ExtensionContext` + registration
  * API: a plugin does not return its contributions, it registers them on the context.
  *
  * The context itself is deliberately minimal — a single generic [[contribute]] plus a teardown
  * hook. Ergonomic registration methods for specific contribution kinds are provided as extension
  * methods next to their extension points (e.g. `registerCommand` via [[wvlet.uni.plugin.Command]],
  * `registerRpcRouter` via `wvlet.uni.http.rpc.RPCPlugin`).
  */
trait PluginContext:
  /**
    * Contribute a value to an extension point. For keyed points, a duplicate key (within or across
    * plugins) is an error.
    */
  def contribute[A](point: ExtensionPoint[A])(value: A): Unit

  /** Register a hook to run when the host is deactivated. Hooks run in reverse (FILO) order. */
  def onDeactivate(hook: () => Unit): Unit

/**
  * A pluggable unit of a Uni application.
  *
  * Mirrors VSCode's `activate(context)` contract: at activation time the plugin contributes to the
  * host's extension points and registers teardown hooks through the supplied [[PluginContext]]. The
  * host ([[PluginHost]]) owns the registries; the plugin only registers into them.
  *
  * {{{
  *   import wvlet.uni.plugin.Command.registerCommand
  *   import wvlet.uni.http.rpc.RPCPlugin.registerRpcRouter
  *
  *   class NotesPlugin extends Plugin:
  *     def id = "notes"
  *     def activate(ctx: PluginContext): Unit =
  *       ctx.registerRpcRouter(RPCRouter.of[NotesApi](NotesApiImpl()))
  *       ctx.registerCommand("notes.new")(_ => createNote())
  *       ctx.onDeactivate(() => flushToDisk())
  * }}}
  */
trait Plugin:
  /** Stable, unique identifier for this plugin. */
  def id: String

  /** Contribute to the host's extension points through the context. */
  def activate(context: PluginContext): Unit
