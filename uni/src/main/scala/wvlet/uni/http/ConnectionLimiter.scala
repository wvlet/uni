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

import java.util.concurrent.atomic.AtomicInteger

/**
  * Counts open long-lived connections (Server-Sent Events streams, WebSockets) against a limit, so
  * a server can answer 503 instead of letting idle streams exhaust its threads or memory.
  */
private[uni] class ConnectionLimiter(val limit: Int):
  private val active = AtomicInteger(0)

  /** Take a slot. Returns false, taking nothing, when `limit` slots are already in use. */
  def tryAcquire(): Boolean =
    if active.incrementAndGet() > limit then
      active.decrementAndGet()
      false
    else
      true

  def release(): Unit = active.decrementAndGet()

  def activeCount: Int = active.get()

end ConnectionLimiter
