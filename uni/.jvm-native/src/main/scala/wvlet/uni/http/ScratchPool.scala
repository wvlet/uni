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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
  * Keeps up to `max` reusable objects (buffers, compressors) for the request path, shared by every
  * thread. On Scala Native's Boehm GC, every array above ~400 bytes takes its global allocation
  * lock, so a response that allocates fresh buffers serializes the workers. A pool rather than a
  * ThreadLocal, because the JVM server runs each connection on its own virtual thread: per-thread
  * objects would grow with the number of connections.
  */
private[http] final class ScratchPool[A <: AnyRef](max: Int, create: () => A):
  private val free  = ConcurrentLinkedQueue[A]()
  private val count = AtomicInteger(0)

  def borrow(): A =
    val a = free.poll()
    if a == null then
      create()
    else
      count.decrementAndGet()
      a

  /** Return `a` to the pool. False when the pool is full: the caller disposes of `a` then. */
  def release(a: A): Boolean =
    if count.incrementAndGet() <= max then
      free.offer(a)
      true
    else
      count.decrementAndGet()
      false

end ScratchPool

private[http] object ScratchPool:
  /** Enough for one object per busy worker */
  val DefaultMax: Int = math.max(4, Runtime.getRuntime.availableProcessors())
