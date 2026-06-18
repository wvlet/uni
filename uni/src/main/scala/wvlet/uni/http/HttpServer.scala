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
  * Platform-neutral HTTP server lifecycle. Each backend (Netty on JVM, Node.js on Scala.js, and a
  * future posix-socket server on Native) provides its own implementation, but applications interact
  * with the server through this common interface.
  */
trait HttpServer extends AutoCloseable:

  /**
    * The bound local address as `host:port`. On an ephemeral binding (port 0) this reflects the
    * actually-assigned port once the server has started.
    */
  def localAddress: String

  /**
    * The actually-bound local port. Resolves the OS-assigned port when started with port 0.
    */
  def localPort: Int

  def isRunning: Boolean

  def stop(): Unit

  /**
    * Block until the server terminates. On runtimes without blocking semantics (e.g. Node.js) this
    * returns immediately; the event loop keeps the process alive while the server is listening.
    */
  def awaitTermination(): Unit

  override def close(): Unit = stop()

end HttpServer
