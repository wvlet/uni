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
  * Netty-only options for `Http.server`, for code that runs on the JVM. Set the shared settings
  * (port, handler, size limits, ...) on `Http.server` itself:
  * {{{
  * Http.server.withPort(8080).withBackend(Netty.options.withHandlerExecutorThreads(8))
  * }}}
  */
object Netty:
  def options: NettyOptions = NettyOptions.empty
