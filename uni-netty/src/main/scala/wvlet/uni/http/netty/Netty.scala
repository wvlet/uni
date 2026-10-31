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
package wvlet.uni.http.netty

/**
  * Entry point for Netty-specific server settings, the JVM counterpart of `Http.server`:
  * {{{
  * // Netty-only options for Http.server, e.g. bound in a JVM Design
  * Http.server.withPort(8080).withBackend(Netty.server.withHandlerExecutorThreads(8))
  *
  * // Or a Netty server on its own
  * Netty.server.withPort(8080).withHandler(handler).start()
  * }}}
  */
object Netty:
  def server: NettyServerConfig = NettyServerConfig()
