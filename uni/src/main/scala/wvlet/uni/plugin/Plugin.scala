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

import wvlet.uni.http.rpc.RPCRouter

/**
  * Context handed to a [[Plugin]]'s [[Plugin.activate]], through which the plugin contributes
  * commands and RPC services to the host. Modeled on VSCode's `ExtensionContext` + registration
  * API: a plugin does not return its contributions, it registers them on the context.
  */
trait PluginContext:
  /**
    * Register a command callable by `id` via [[PluginHost.executeCommand]]. Command ids are
    * host-global; registering a duplicate id (within or across plugins) is an error.
    */
  def registerCommand(id: String)(handler: Seq[Any] => Any): Unit

  /** Contribute an RPC service router, typically built with `RPCRouter.of[T](impl)`. */
  def registerRpcRouter(router: RPCRouter): Unit

  /** Register a hook to run when the host is deactivated. Hooks run in reverse (FILO) order. */
  def onDeactivate(hook: () => Unit): Unit

/**
  * A pluggable unit of a Uni desktop app — the extension model underpinning Uni's plugin testing
  * layer.
  *
  * Mirrors VSCode's `activate(context)` contract: at activation time the plugin contributes its
  * commands, RPC services, and teardown hooks through the supplied [[PluginContext]]. The host
  * ([[PluginHost]]) owns the registries; the plugin only registers into them.
  *
  * {{{
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

  /** Contribute commands, RPC services, and deactivation hooks through the context. */
  def activate(context: PluginContext): Unit
