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

import wvlet.uni.test.UniTest

class CanvasTest extends UniTest:

  // Run a check on a heap canvas and, where supported, an off-heap canvas
  private def withCanvases(size: Int)(body: Canvas => Unit): Unit =
    body(Canvas.newCanvas(size))
    if Canvas.isOffHeapSupported then
      val c = Canvas.newOffHeapCanvas(size)
      try body(c)
      finally c.close()

  private def check(c: Canvas): Unit =
    c.writeByte(0, 1.toByte)
    c.readByte(0) shouldBe 1.toByte
    c.writeByte(1, (-1).toByte)
    c.readByte(1) shouldBe (-1).toByte
    c.writeBoolean(2, true)
    c.readBoolean(2) shouldBe true
    c.writeBoolean(2, false)
    c.readBoolean(2) shouldBe false
    for v <- Seq(Short.MinValue, (-1).toShort, 0.toShort, 0x1234.toShort, Short.MaxValue) do
      c.writeShort(3, v)
      c.readShort(3) shouldBe v
      c.writeShortBigEndian(3, v)
      c.readShortBigEndian(3) shouldBe v
    for v <- Seq(Int.MinValue, -1, 0, 0x12345678, Int.MaxValue) do
      c.writeInt(5, v)
      c.readInt(5) shouldBe v
      c.writeIntBigEndian(5, v)
      c.readIntBigEndian(5) shouldBe v
    for v <- Seq(Long.MinValue, -1L, 0L, 0x123456789abcdef0L, Long.MaxValue) do
      c.writeLong(9, v)
      c.readLong(9) shouldBe v
      c.writeLongBigEndian(9, v)
      c.readLongBigEndian(9) shouldBe v
    for v <- Seq(Float.MinValue, -1.5f, 0.0f, 3.14f, Float.MaxValue) do
      c.writeFloat(17, v)
      c.readFloat(17) shouldBe v
    for v <- Seq(Double.MinValue, -1.5, 0.0, 3.14, Double.MaxValue) do
      c.writeDouble(21, v)
      c.readDouble(21) shouldBe v

  end check

  test("read and write primitive values") {
    withCanvases(64)(check)
  }

  test("encode multi-byte values in little-endian order") {
    withCanvases(16) { c =>
      c.writeInt(0, 0x01020304)
      c.readBytes(0, 4).toSeq shouldBe Seq[Byte](4, 3, 2, 1)
      c.writeIntBigEndian(0, 0x01020304)
      c.readBytes(0, 4).toSeq shouldBe Seq[Byte](1, 2, 3, 4)
      c.writeShort(4, 0x0102.toShort)
      c.readBytes(4, 2).toSeq shouldBe Seq[Byte](2, 1)
      c.writeLong(8, 0x0102030405060708L)
      c.readBytes(8, 8).toSeq shouldBe Seq[Byte](8, 7, 6, 5, 4, 3, 2, 1)
    }
  }

  test("read and write byte arrays") {
    withCanvases(16) { c =>
      val src = Array.tabulate[Byte](10)(_.toByte)
      c.writeBytes(2, src)
      c.readBytes(2, 10).toSeq shouldBe src.toSeq
      c.writeBytes(0, src, 5, 3)
      c.readBytes(0, 3).toSeq shouldBe Seq[Byte](5, 6, 7)
      val dest = Array.ofDim[Byte](4)
      c.readBytes(0, dest, 1, 3)
      dest.toSeq shouldBe Seq[Byte](0, 5, 6, 7)
    }
  }

  test("wrap a byte array without copying") {
    val arr = Array.ofDim[Byte](8)
    val c   = Canvas.wrap(arr, 2, 4)
    c.size shouldBe 4L
    c.isOffHeap shouldBe false
    c.writeIntBigEndian(0, 0x01020304)
    arr.toSeq shouldBe Seq[Byte](0, 0, 1, 2, 3, 4, 0, 0)
    c.toByteArray.toSeq shouldBe Seq[Byte](1, 2, 3, 4)
  }

  test("wrap a ByteBuffer from its position") {
    val buf = java.nio.ByteBuffer.wrap(Array[Byte](1, 2, 3, 4, 5))
    buf.position(1)
    Canvas.wrap(buf).toByteArray.toSeq shouldBe Seq[Byte](2, 3, 4, 5)
  }

  test("share memory with slices") {
    withCanvases(16) { c =>
      val s = c.slice(4, 8)
      s.size shouldBe 8L
      s.writeInt(0, 42)
      c.readInt(4) shouldBe 42
      val s2 = s.slice(4, 4)
      s2.writeInt(0, 7)
      c.readInt(8) shouldBe 7
      intercept[IndexOutOfBoundsException] {
        s.readInt(6)
      }
      intercept[IndexOutOfBoundsException] {
        c.slice(10, 8)
      }
    }
  }

  test("copy bytes between canvases of any kind") {
    val canvases =
      Seq(() => Canvas.newCanvas(16)) ++ (
        if Canvas.isOffHeapSupported then
          Seq(() => Canvas.newOffHeapCanvas(16))
        else
          Nil
      )
    for
      newSrc  <- canvases
      newDest <- canvases
    do
      val src  = newSrc()
      val dest = newDest()
      src.writeBytes(0, Array.tabulate[Byte](16)(i => (i + 1).toByte))
      dest.writeBytes(0, Array.ofDim[Byte](16))
      src.readBytes(2, dest, 4, 8)
      dest.readBytes(0, 16).toSeq shouldBe
        Seq[Byte](0, 0, 0, 0, 3, 4, 5, 6, 7, 8, 9, 10, 0, 0, 0, 0)
      dest.writeBytes(0, src, 0, 2)
      dest.readBytes(0, 2).toSeq shouldBe Seq[Byte](1, 2)
      src.close()
      dest.close()
  }

  test("copy overlapping ranges of the same canvas") {
    withCanvases(8) { c =>
      c.writeBytes(0, Array[Byte](1, 2, 3, 4, 5, 6, 7, 8))
      c.readBytes(0, c, 2, 4)
      c.toByteArray.toSeq shouldBe Seq[Byte](1, 2, 1, 2, 3, 4, 7, 8)
    }
  }

  test("reject out-of-range access") {
    withCanvases(8) { c =>
      intercept[IndexOutOfBoundsException] {
        c.readInt(5)
      }
      intercept[IndexOutOfBoundsException] {
        c.writeLong(1, 0L)
      }
      intercept[IndexOutOfBoundsException] {
        c.readByte(-1)
      }
      intercept[IndexOutOfBoundsException] {
        c.readByte(8)
      }
      intercept[IndexOutOfBoundsException] {
        c.writeBytes(4, Array.ofDim[Byte](5))
      }
      intercept[IndexOutOfBoundsException] {
        c.readBytes(0, Array.ofDim[Byte](4), 2, 4)
      }
      intercept[IndexOutOfBoundsException] {
        c.writeBytes(0, Array.ofDim[Byte](4), 3, 2)
      }
      intercept[IndexOutOfBoundsException] {
        c.readBytes(4, Canvas.newCanvas(8), 0, 5)
      }
    }
  }

  test("track and free off-heap memory") {
    if !Canvas.isOffHeapSupported then
      intercept[UnsupportedOperationException] {
        Canvas.newOffHeapCanvas(8)
      }
      skip("Off-heap canvases need Java 22 or later on the JVM")
    val allocator = OffHeapMemoryAllocator()
    val c1        = allocator.newCanvas(100)
    val c2        = allocator.newCanvas(28)
    c1.isOffHeap shouldBe true
    c1.size shouldBe 100L
    allocator.allocatedMemorySize shouldBe 128L
    c1.close()
    allocator.allocatedMemorySize shouldBe 28L
    // Closing twice is safe
    c1.close()
    allocator.allocatedMemorySize shouldBe 28L
    intercept[IllegalStateException] {
      c1.readInt(0)
    }
    allocator.close()
    allocator.allocatedMemorySize shouldBe 0L
    intercept[IllegalStateException] {
      c2.slice(0, 4).writeInt(0, 1)
    }
  }

  test("allocate an empty off-heap canvas") {
    if !Canvas.isOffHeapSupported then
      skip("Off-heap canvases need Java 22 or later on the JVM")
    val c = Canvas.newOffHeapCanvas(0)
    c.size shouldBe 0L
    c.toByteArray.length shouldBe 0
    c.close()
    intercept[IllegalArgumentException] {
      Canvas.newOffHeapCanvas(-1)
    }
  }

end CanvasTest
