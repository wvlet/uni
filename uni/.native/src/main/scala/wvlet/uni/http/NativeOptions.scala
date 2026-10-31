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

/**
  * Scala Native-only options for `Http.server`, created with [[Native.options]]. Each setter is
  * checked when called, and the options are applied to the `NativeServerConfig` built from
  * `Http.server`'s settings.
  */
case class NativeOptions private (updates: Seq[NativeServerConfig => NativeServerConfig])
    extends ServerOptions:

  // Run the update on a default config now, so invalid values fail at the call site
  private def add(update: NativeServerConfig => NativeServerConfig): NativeOptions =
    update(NativeServerConfig())
    NativeOptions(updates :+ update)

  private[http] def applyTo(config: NativeServerConfig): NativeServerConfig =
    updates.foldLeft(config)((c, update) => update(c))

  def withBacklog(backlog: Int): NativeOptions              = add(_.withBacklog(backlog))
  def withWorkerThreads(threads: Int): NativeOptions        = add(_.withWorkerThreads(threads))
  def withHandlerTimeoutMillis(millis: Long): NativeOptions = add(
    _.withHandlerTimeoutMillis(millis)
  )

  def withIdleTimeoutMillis(millis: Int): NativeOptions  = add(_.withIdleTimeoutMillis(millis))
  def withReadTimeoutMillis(millis: Int): NativeOptions  = add(_.withReadTimeoutMillis(millis))
  def withMaxStreamingConnections(n: Int): NativeOptions = add(_.withMaxStreamingConnections(n))

end NativeOptions

object NativeOptions:
  private[http] val empty: NativeOptions = NativeOptions(Nil)
