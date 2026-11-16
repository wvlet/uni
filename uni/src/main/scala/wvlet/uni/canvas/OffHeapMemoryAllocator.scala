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
package wvlet.uni.canvas

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
  * Allocates off-heap canvases and keeps track of the memory they still hold.
  *
  *   - `close()` on a canvas frees its memory right away.
  *   - On the JVM and Scala.js, memory of a canvas that becomes unreachable without `close()` is
  *     freed after GC. Scala Native frees memory only through `close()`.
  *   - Closing the allocator frees every canvas it allocated that is still open.
  */
class OffHeapMemoryAllocator extends AutoCloseable:
  private val live      = ConcurrentHashMap[Long, Allocation]()
  private val totalSize = AtomicLong(0)
  private val nextId    = AtomicLong(0)

  /**
    * The total size in bytes of the canvases allocated here and not yet freed
    */
  def allocatedMemorySize: Long = totalSize.get()

  /**
    * Allocate a new off-heap canvas of `size` bytes. The memory is not guaranteed to be
    * zero-filled.
    *
    * @throws UnsupportedOperationException
    *   if this platform cannot allocate off-heap memory (JVMs older than Java 22)
    */
  def newCanvas(size: Long): Canvas =
    if size < 0 then
      throw IllegalArgumentException(s"Canvas size must not be negative: ${size}")
    CanvasCompat
      .unsupportedReason
      .foreach { reason =>
        throw UnsupportedOperationException(reason)
      }
    CanvasCompat.allocate(this, size)

  /**
    * Record memory that a platform backend allocated, and return the handle that frees it
    */
  private[canvas] def register(size: Long, free: () => Unit): Allocation =
    val a = Allocation(nextId.incrementAndGet(), size, free, this)
    live.put(a.id, a)
    totalSize.addAndGet(size)
    a

  private[canvas] def unregister(a: Allocation): Unit =
    if live.remove(a.id) != null then
      totalSize.addAndGet(-a.size)

  /**
    * Free every canvas allocated here that is still open
    */
  override def close(): Unit = live.values().asScala.toList.foreach(_.release())

end OffHeapMemoryAllocator

object OffHeapMemoryAllocator:
  /**
    * The allocator behind [[Canvas.newOffHeapCanvas]]
    */
  lazy val default: OffHeapMemoryAllocator = OffHeapMemoryAllocator()

/**
  * One block of off-heap memory. It holds no reference to the canvases that use it, so a GC hook
  * registered on a canvas owner can still free it.
  */
private[canvas] final class Allocation(
    val id: Long,
    val size: Long,
    free: () => Unit,
    allocator: OffHeapMemoryAllocator
):
  private val released = AtomicBoolean(false)

  def isReleased: Boolean = released.get()

  /**
    * Free the memory once. Later calls do nothing.
    */
  def release(): Unit =
    if released.compareAndSet(false, true) then
      allocator.unregister(this)
      free()

  def ensureOpen(): Unit =
    if released.get() then
      throw IllegalStateException("This off-heap canvas is already closed")

end Allocation

/**
  * The object that every canvas and slice over one allocation references. Platforms register their
  * GC hook on it, so the memory is freed only after no canvas can reach it.
  */
private[canvas] final class MemoryOwner(val allocation: Allocation)
