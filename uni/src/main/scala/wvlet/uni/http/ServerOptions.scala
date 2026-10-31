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
  * Options for one HTTP server backend, passed to [[HttpServerConfig.withBackend]]: `Netty.options`
  * on the JVM and `Native.options` on Scala Native. They hold only that backend's own settings; the
  * settings every backend shares (port, handler, size limits, ...) are set on `Http.server`.
  */
trait ServerOptions
