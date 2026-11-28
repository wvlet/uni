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

/**
  * Compares the cost of getting a buffer and of first touching it, for the numbers in
  * adr/2026-11-27-canvas-malloc-allocation.md:
  *
  * {{{
  * ./sbt "uniJVM/Test/runMain wvlet.uni.canvas.CanvasAllocBench"
  * }}}
  *
  *   - alloc: get a buffer and free it (heap arrays are left to GC)
  *   - touch: get a buffer, write one byte per 4 KB page, and free it
  *
  * `slab` slices a buffer out of one pre-allocated canvas, the cost an engine-owned pool pays.
  */
object CanvasAllocBench:
  private val sizes = Seq(64 * 1024L, 1024 * 1024L, 64 * 1024 * 1024L)
  private val page  = 4096

  private var sink = 0L

  private def touch(c: Canvas): Unit =
    var o = 0L
    while o < c.size do
      c.writeByte(o, 1)
      o += page
    sink += c.readByte(0)

  private def touch(a: Array[Byte]): Unit =
    var o = 0
    while o < a.length do
      a(o) = 1
      o += page
    sink += a(0)

  // The median time of one call, in microseconds
  private def measure(size: Long)(body: => Unit): Double =
    val iterations = math.max(4, math.min(2000, (256L * 1024 * 1024 / size).toInt))
    // Warm up the JIT
    (0 until iterations).foreach(_ => body)
    val samples = (0 until 15).map { _ =>
      val start = System.nanoTime()
      var i     = 0
      while i < iterations do
        body
        i += 1
      (System.nanoTime() - start).toDouble / iterations / 1000.0
    }
    samples.sorted.apply(samples.size / 2)

  def main(args: Array[String]): Unit =
    val allocator = OffHeapMemoryAllocator()
    val slab      = FfmCanvas.allocate(allocator, sizes.max, true)
    println(s"Java ${Runtime.version()}, malloc available: ${FfmCanvas.usesMalloc}")
    println("| Size | Operation | new Array[Byte] | Arena (zero-fill) | malloc | slab slice |")
    println("|------|-----------|----------------:|------------------:|-------:|-----------:|")
    for
      size <- sizes
      op   <- Seq("alloc", "touch")
    do
      val doTouch = op == "touch"
      val heap    =
        measure(size) {
          val a = Array.ofDim[Byte](size.toInt)
          if doTouch then
            touch(a)
          else
            sink += a.length
        }
      def offHeap(useMalloc: Boolean) =
        measure(size) {
          val c = FfmCanvas.allocate(allocator, size, useMalloc)
          if doTouch then
            touch(c)
          c.close()
        }
      val arena  = offHeap(false)
      val malloc =
        if FfmCanvas.usesMalloc then
          f"${offHeap(true)}%.2f"
        else
          "-"
      val sliced =
        measure(size) {
          val c = slab.slice(0, size)
          if doTouch then
            touch(c)
        }
      println(
        f"| ${size /
            1024}%,d KB | ${op} | ${heap}%.2f µs | ${arena}%.2f µs | ${malloc} µs | ${sliced}%.2f µs |"
      )
    end for
    slab.close()
    if sink == 42 then
      println(sink)

  end main

end CanvasAllocBench
