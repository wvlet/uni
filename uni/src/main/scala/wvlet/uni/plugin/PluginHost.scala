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

import scala.collection.mutable

/**
  * Activates [[Plugin]]s and owns the typed registries they contribute to, one per
  * [[ExtensionPoint]]. This is the runtime an app embeds; the plugin testing harness
  * ([[wvlet.uni.plugin.testing.PluginTestHost]]) drives the very same host so tests exercise the
  * real activation path.
  *
  * Activation is eager and synchronous (as in VSCode's `activate`). Contributions to a keyed point
  * are host-global, so a duplicate key — within a plugin or across plugins — is rejected, which
  * surfaces plugin conflicts in tests instead of at runtime.
  */
class PluginHost:
  private case class Contribution(pluginId: String, key: Option[String], value: Any)

  private val contributionMap = mutable
    .LinkedHashMap
    .empty[ExtensionPoint[?], mutable.ListBuffer[Contribution]]

  private val deactivateHooks = mutable.ListBuffer.empty[() => Unit]
  private val _activated      = mutable.ListBuffer.empty[String]

  private def contextFor(pluginId: String): PluginContext =
    new PluginContext:
      override def contribute[A](point: ExtensionPoint[A])(value: A): Unit =
        val entries = contributionMap.getOrElseUpdate(point, mutable.ListBuffer.empty)
        val key     = point.keyOf.map(_(value))
        for
          k        <- key
          existing <- entries.find(_.key.contains(k))
        do
          throw IllegalArgumentException(
            s"${point.name} '${k}' is already contributed by plugin '${existing
                .pluginId}' (attempted by plugin '${pluginId}')"
          )
        entries += Contribution(pluginId, key, value)

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

  /** All values contributed to the given extension point, in contribution order. */
  def contributions[A](point: ExtensionPoint[A]): Seq[A] = contributionMap
    .get(point)
    .map(_.map(_.value.asInstanceOf[A]).toSeq)
    .getOrElse(Seq.empty)

  /** Look up a keyed point's contribution by key. Always `None` for unkeyed points. */
  def contribution[A](point: ExtensionPoint[A], key: String): Option[A] = contributionMap
    .get(point)
    .flatMap(_.find(_.key.contains(key)))
    .map(_.value.asInstanceOf[A])

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
    contributionMap.clear()
    _activated.clear()

end PluginHost
