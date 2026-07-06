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
  * A VSCode-style command: a host-global handler callable by id, e.g. from a menu, a keybinding, or
  * a test. Contributed through [[Command.point]]; the point is keyed by command id, so duplicate
  * ids (within or across plugins) are rejected at activation time.
  */
case class Command(id: String, handler: Seq[Any] => Any)

object Command:
  /** Extension point through which plugins contribute commands. */
  val point: ExtensionPoint[Command] = ExtensionPoint.keyed[Command]("plugin.command")(_.id)

  extension (context: PluginContext)
    /** Register a command callable by `id` via [[executeCommand]]. */
    def registerCommand(id: String)(handler: Seq[Any] => Any): Unit =
      context.contribute(point)(Command(id, handler))

  extension (host: PluginHost)
    /** All registered command ids. */
    def commandIds: Set[String] = host.contributions(point).map(_.id).toSet

    def hasCommand(id: String): Boolean = host.contribution(point, id).isDefined

    /**
      * Invoke a registered command by id with the given arguments, returning its result.
      *
      * @throws java.util.NoSuchElementException
      *   if no command is registered under `id`.
      */
    def executeCommand(id: String, args: Any*): Any =
      host.contribution(point, id) match
        case Some(command) =>
          command.handler(args.toSeq)
        case None =>
          throw java
            .util
            .NoSuchElementException(
              s"No command registered with id '${id}'. Available: ${host
                  .commandIds
                  .toSeq
                  .sorted
                  .mkString(", ")}"
            )

end Command
