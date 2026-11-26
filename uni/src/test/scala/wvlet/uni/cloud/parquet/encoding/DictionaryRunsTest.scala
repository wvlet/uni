package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.column.DoubleBuffer
import wvlet.uni.cloud.parquet.column.FloatBuffer
import wvlet.uni.cloud.parquet.column.IntBuffer
import wvlet.uni.cloud.parquet.column.LongBuffer
import wvlet.uni.cloud.parquet.column.ValueBuffer
import wvlet.uni.test.UniTest

import scala.collection.mutable.ArrayBuffer

/**
  * Fixed-size buffers look up dictionary indices run by run. Writers such as DuckDB emit RLE runs
  * shorter than 8 values, mixed with bit-packed groups; the values must come out the same as one
  * lookup per index, both packed and in row slots between nulls.
  */
class DictionaryRunsTest extends UniTest:
  private val rnd      = scala.util.Random(11)
  private val dictSize = 50
  private val bitWidth = BitPacking.bitWidthOf(dictSize - 1)

  /** RLE runs of 1 to 20 values and bit-packed groups, as (indices, encoded bytes) */
  private def encodeRuns(n: Int): (Array[Int], Array[Byte]) =
    val idx                  = ArrayBuffer.empty[Int]
    val out                  = ArrayBuffer.empty[Byte]
    def varint(x: Int): Unit =
      var v = x
      while (v & ~0x7f) != 0 do
        out += ((v & 0x7f) | 0x80).toByte
        v >>>= 7
      out += v.toByte
    while idx.size < n do
      if rnd.nextInt(4) == 0 then
        // Bit-packed groups of 8 indices
        val groups = 1 + rnd.nextInt(3)
        val vs     = Array.fill(groups * 8)(rnd.nextInt(dictSize))
        varint((groups << 1) | 1)
        var acc  = 0L
        var bits = 0
        for v <- vs do
          acc |= v.toLong << bits
          bits += bitWidth
          while bits >= 8 do
            out += (acc & 0xff).toByte
            acc >>>= 8
            bits -= 8
        if bits > 0 then
          out += (acc & 0xff).toByte
        idx ++= vs
      else
        val len = 1 + rnd.nextInt(20)
        val v   = rnd.nextInt(dictSize)
        varint(len << 1)
        out += v.toByte
        idx ++= Seq.fill(len)(v)
    (idx.toArray, out.toArray)

  end encodeRuns

  private def check[B <: ValueBuffer](
      dict: B,
      empty: () => B,
      get: (B, Int) => Any,
      zero: Any
  ): Unit =
    for withNulls <- Seq(false, true) do
      val (idx, bytes) = encodeRuns(5000)
      val n            = idx.length
      // Rows: the n values, with nulls among them in slot mode
      // About one row in 11 is null, a run of nulls at times
      val nulls = ArrayBuffer.empty[Int]
      if withNulls then
        var values = 0
        var r      = 0
        while values < n do
          if rnd.nextInt(11) == 0 then
            nulls ++= (r until r + 1 + rnd.nextInt(3))
            r = nulls.last + 1
          else
            values += 1
            r += 1
      val rows = n + nulls.length
      val out  = empty()
      out.reserve(rows)
      if withNulls then
        out.beginSlots(nulls.toArray, nulls.length)
      // Pages read in a few calls, with a small index block
      val dec  = RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth)
      var read = 0
      for k <- Iterator.continually(Seq(1, 700, 33, 2000)).flatten.takeWhile(_ => read < n) do
        val m = math.min(k, n - read)
        dec.readDictionary(out, dict, m, new Array[Int](64))
        read += m
      if withNulls then
        out.endSlots(rows) shouldBe n
      out.size shouldBe rows
      val isNull   = nulls.toSet
      var v        = 0
      val actual   = (0 until rows).map(r => get(out, r))
      val expected = (0 until rows).map { r =>
        if isNull(r) then
          zero
        else
          v += 1
          get(dict, idx(v - 1))
      }
      actual shouldBe expected

  test("look up short RLE runs and bit-packed indices into int buffers") {
    val d = IntBuffer(dictSize)
    (0 until dictSize).foreach(i => d.add(i * 7 + 1))
    check[IntBuffer](d, () => IntBuffer(16), (b, i) => b.values(i), 0)
  }

  test("look up short RLE runs and bit-packed indices into long buffers") {
    val d = LongBuffer(dictSize)
    (0 until dictSize).foreach(i => d.add(i * 1000003L + 1))
    check[LongBuffer](d, () => LongBuffer(16), (b, i) => b.values(i), 0L)
  }

  test("look up short RLE runs and bit-packed indices into float buffers") {
    val d = FloatBuffer(dictSize)
    (0 until dictSize).foreach(i => d.add(i * 0.5f + 1))
    check[FloatBuffer](d, () => FloatBuffer(16), (b, i) => b.values(i), 0.0f)
  }

  test("look up short RLE runs and bit-packed indices into double buffers") {
    val d = DoubleBuffer(dictSize)
    (0 until dictSize).foreach(i => d.add(i * 0.25 + 1))
    check[DoubleBuffer](d, () => DoubleBuffer(16), (b, i) => b.values(i), 0.0)
  }

end DictionaryRunsTest
