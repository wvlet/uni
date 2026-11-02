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
  * Entry points for Netty, the JVM backend of `Http.server`.
  *
  * For a JVM-only app, `Netty.server` builds a Netty server directly, with every setting in one
  * typed config:
  * {{{
  * Netty.server.withPort(8080).withHandlerExecutorThreads(8).withRxHandler(handler).start()
  * }}}
  *
  * For cross-platform code, use `Http.server` and pass Netty-only settings as `Netty.options`. Set
  * the shared settings (port, handler, size limits, ...) on `Http.server` itself:
  * {{{
  * Http.server.withPort(8080).withBackend(Netty.options.withHandlerExecutorThreads(8))
  * }}}
  */
object Netty:
  /**
    * A Netty server config for JVM-only code. Unlike `Http.server`, it needs no ServiceLoader
    * lookup, exposes every Netty setting, and `start()` returns a `NettyHttpServer`.
    */
  def server: NettyServerConfig = NettyServerConfig()

  /**
    * Netty-only options for `Http.server.withBackend(...)`
    */
  def options: NettyOptions = NettyOptions.empty
