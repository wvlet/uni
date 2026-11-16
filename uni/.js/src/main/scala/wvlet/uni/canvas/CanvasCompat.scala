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

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

private[canvas] object CanvasCompat:
  val unsupportedReason: Option[String] = None

  // Frees the memory of canvases that become unreachable without close()
  private lazy val registry = js.FinalizationRegistry[MemoryOwner, Allocation, Any] {
    (a: Allocation) =>
      a.release()
  }

  def allocate(allocator: OffHeapMemoryAllocator, size: Long): Canvas =
    val buffer     = JSArrayBuffer(size.toDouble)
    val allocation = allocator.register(size, () => JSCanvas.detach(buffer))
    val owner      = MemoryOwner(allocation)
    registry.register(owner, allocation)
    JSCanvas(buffer, 0, size, owner)

/**
  * A canvas over a JavaScript `ArrayBuffer`, outside the Scala heap. Offsets are JS numbers, so a
  * canvas can be as large as the JS engine allows.
  */
private[canvas] final class JSCanvas(
    private[canvas] val buffer: JSArrayBuffer,
    // The byte offset of this canvas in the buffer
    private[canvas] val base: Double,
    override val size: Long,
    owner: MemoryOwner
) extends Canvas:
  private val view = JSDataView(buffer, base, size.toDouble)

  override def isOffHeap: Boolean = true

  // Check the range and that the memory is not freed yet, then return the view offset
  private inline def at(offset: Long, length: Long): Double =
    checkRange(offset, length)
    owner.allocation.ensureOpen()
    offset.toDouble

  override def readByte(offset: Long): Byte   = view.getInt8(at(offset, 1))
  override def readShort(offset: Long): Short = view.getInt16(at(offset, 2), true)
  override def readInt(offset: Long): Int     = view.getInt32(at(offset, 4), true)

  override def readLong(offset: Long): Long =
    val o  = at(offset, 8)
    val lo = view.getInt32(o, true)
    val hi = view.getInt32(o + 4, true)
    (hi.toLong << 32) | (lo.toLong & 0xffffffffL)

  override def readBytes(offset: Long, dest: Array[Byte], destOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(dest, destOffset, length)
    val o = at(offset, length)
    var i = 0
    while i < length do
      dest(destOffset + i) = view.getInt8(o + i)
      i += 1

  override def readBytes(offset: Long, dest: Canvas, destOffset: Long, length: Long): Unit =
    dest match
      case j: JSCanvas =>
        val src = at(offset, length)
        val dst = j.at(destOffset, length)
        // TypedArray.set copies correctly even when both views share the buffer
        JSInt8Array(j.buffer, j.base + dst, length.toDouble).set(
          JSInt8Array(buffer, base + src, length.toDouble)
        )
      case _ =>
        super.readBytes(offset, dest, destOffset, length)

  override def writeByte(offset: Long, v: Byte): Unit   = view.setInt8(at(offset, 1), v)
  override def writeShort(offset: Long, v: Short): Unit = view.setInt16(at(offset, 2), v, true)
  override def writeInt(offset: Long, v: Int): Unit     = view.setInt32(at(offset, 4), v, true)

  override def writeLong(offset: Long, v: Long): Unit =
    val o = at(offset, 8)
    view.setInt32(o, v.toInt, true)
    view.setInt32(o + 4, (v >>> 32).toInt, true)

  override def writeBytes(offset: Long, src: Array[Byte], srcOffset: Int, length: Int): Unit =
    Canvas.checkArrayRange(src, srcOffset, length)
    val o = at(offset, length)
    var i = 0
    while i < length do
      view.setInt8(o + i, src(srcOffset + i))
      i += 1

  override def slice(offset: Long, length: Long): Canvas =
    at(offset, length)
    JSCanvas(buffer, base + offset.toDouble, length, owner)

  override def close(): Unit = owner.allocation.release()

end JSCanvas

private[canvas] object JSCanvas:
  /**
    * Detach the buffer so the engine can free its memory before GC. `ArrayBuffer.transfer` needs
    * ES2024 (Node.js 21+); older engines free the memory once the buffer is unreachable.
    */
  def detach(buffer: JSArrayBuffer): Unit =
    val b = buffer.asInstanceOf[js.Dynamic]
    if !js.isUndefined(b.transfer) then
      b.transfer(0)

// Facades that take byte offsets as JS numbers, so canvases can grow beyond Int.MaxValue

@js.native
@JSGlobal("ArrayBuffer")
private[canvas] class JSArrayBuffer(length: Double) extends js.Object

@js.native
@JSGlobal("DataView")
private[canvas] class JSDataView(buffer: JSArrayBuffer, byteOffset: Double, byteLength: Double)
    extends js.Object:
  def getInt8(byteOffset: Double): Byte                                       = js.native
  def getInt16(byteOffset: Double, littleEndian: Boolean): Short              = js.native
  def getInt32(byteOffset: Double, littleEndian: Boolean): Int                = js.native
  def setInt8(byteOffset: Double, value: Byte): Unit                          = js.native
  def setInt16(byteOffset: Double, value: Short, littleEndian: Boolean): Unit = js.native
  def setInt32(byteOffset: Double, value: Int, littleEndian: Boolean): Unit   = js.native

@js.native
@JSGlobal("Int8Array")
private[canvas] class JSInt8Array(buffer: JSArrayBuffer, byteOffset: Double, length: Double)
    extends js.Object:
  def set(source: JSInt8Array): Unit = js.native
