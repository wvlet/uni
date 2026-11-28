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

import java.util.concurrent.CountDownLatch

class FfmCanvasTest extends UniTest:
  private def requireFfm(): Unit =
    if !Canvas.isOffHeapSupported then
      skip("Off-heap canvases need Java 22 or later on the JVM")

  // Both allocation paths: malloc (when native access is enabled) and the zero-filling arena
  private def paths: Seq[(String, Boolean)] =
    val fallback = Seq("arena" -> false)
    if FfmCanvas.usesMalloc then
      ("malloc" -> true) +: fallback
    else
      fallback

  private def allocate(allocator: OffHeapMemoryAllocator, size: Long, useMalloc: Boolean): Canvas =
    FfmCanvas.allocate(allocator, size, useMalloc)

  test("use malloc when the JVM enables native access") {
    requireFfm()
    // ./sbt runs with --enable-native-access=ALL-UNNAMED, so CI covers the malloc path
    debug(s"malloc: ${FfmCanvas.usesMalloc}")
    FfmCanvas.usesMalloc shouldBe classOf[FfmCanvas].getModule.isNativeAccessEnabled
  }

  test("read, write, and track memory on every allocation path") {
    requireFfm()
    paths.foreach { (_, useMalloc) =>
      val allocator = OffHeapMemoryAllocator()
      val c         = allocate(allocator, 1 << 20, useMalloc)
      allocator.allocatedMemorySize shouldBe (1L << 20)
      c.writeLong(0, 0x0102030405060708L)
      c.readLong(0) shouldBe 0x0102030405060708L
      c.fill(8, 1000, 5.toByte)
      c.readBytes(8, 1000).forall(_ == 5.toByte) shouldBe true
      intercept[IndexOutOfBoundsException] {
        c.readByte(1 << 20)
      }
      c.close()
      allocator.allocatedMemorySize shouldBe 0L
      intercept[IllegalStateException] {
        c.readLong(0)
      }
      intercept[IllegalStateException] {
        c.slice(0, 8)
      }
    }
  }

  test("allocate empty canvases on every path") {
    requireFfm()
    paths.foreach { (_, useMalloc) =>
      val c = allocate(OffHeapMemoryAllocator.default, 0, useMalloc)
      c.size shouldBe 0L
      c.close()
    }
  }

  test("close by the allocator frees malloc memory") {
    requireFfm()
    paths.foreach { (_, useMalloc) =>
      val allocator = OffHeapMemoryAllocator()
      val cs        = (0 until 10).map(_ => allocate(allocator, 4096, useMalloc))
      allocator.allocatedMemorySize shouldBe 40960L
      allocator.close()
      allocator.allocatedMemorySize shouldBe 0L
      cs.foreach { c =>
        intercept[IllegalStateException] {
          c.readInt(0)
        }
      }
    }
  }

  test("a close racing with readers on other threads never crashes the JVM") {
    requireFfm()
    paths.foreach { (_, useMalloc) =>
      (0 until 20).foreach { _ =>
        val c       = allocate(OffHeapMemoryAllocator.default, 1 << 16, useMalloc)
        val started = CountDownLatch(2)
        val readers = (0 until 2).map { _ =>
          val t = Thread { () =>
            started.countDown()
            try
              while true do
                c.readLong(1024)
            catch
              case _: IllegalStateException =>
                // The shared arena rejects access once it is closed
                ()
          }
          t.start()
          t
        }
        started.await()
        c.close()
        readers.foreach(_.join())
      }
    }
  }

end FfmCanvasTest
