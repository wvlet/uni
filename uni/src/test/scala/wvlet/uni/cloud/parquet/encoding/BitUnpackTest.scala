package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.IntBuffer
import wvlet.uni.cloud.parquet.column.LongBuffer
import wvlet.uni.cloud.parquet.column.BinaryBuffer
import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.test.UniTest

/**
  * Bit unpacking reads most values with one 64-bit load and the last few bit by bit, and the RLE
  * decoder unpacks bit-packed runs as they are read. Both must match a bit-by-bit reference, at any
  * start bit and near the end of the data.
  */
class BitUnpackTest extends UniTest:
  private val rnd = scala.util.Random(7)

  /** Value i of `bitWidth` bits at `firstBit + i * bitWidth`, reading missing bytes as 0 */
  private def reference(
      src: Array[Byte],
      offset: Int,
      limit: Int,
      bitWidth: Int,
      firstBit: Long,
      n: Int
  ): Seq[Int] = (0 until n).map { i =>
    var v = 0L
    var j = 0
    while j < bitWidth do
      val bit  = firstBit + i.toLong * bitWidth + j
      val byte = offset + (bit >>> 3)
      val b    =
        if byte < limit then
          (src(byte.toInt) >>> (bit & 7).toInt) & 1
        else
          0
      v |= b.toLong << j
      j += 1
    v.toInt
  }

  test("unpack every width at any start bit, up to and past the end of the data") {
    val src = Array.fill[Byte](300)(rnd.nextInt(256).toByte)
    for
      bitWidth <- 1 to 32
      offset   <- Seq(0, 5)
      firstBit <- Seq(0L, 3L, 13L)
      limit    <- Seq(src.length, src.length - 3, offset + 9)
    do
      // Ask for more values than the data holds, so the last ones read zeros
      val n   = ((limit - offset) * 8 / bitWidth) + 3
      val dst = new Array[Int](n + 2)
      BitPacking.unpackInts(src, offset, limit, bitWidth, dst, 2, n, firstBit)
      dst.drop(2).toSeq shouldBe reference(src, offset, limit, bitWidth, firstBit, n)
  }

  private def encode(values: Array[Int], bitWidth: Int): Array[Byte] =
    val out = ByteBuf()
    RleEncoder.encode(values, values.length, bitWidth, out)
    out.toByteArray

  // Long RLE runs mixed with long bit-packed runs
  private def indices(n: Int, dictSize: Int): Array[Int] =
    Array.tabulate(n) { i =>
      if (i / 500) % 3 == 0 then
        (i / 500)  % dictSize
      else
        rnd.nextInt(dictSize)
    }

  test("read bit-packed runs in pieces that end inside runs") {
    for bitWidth <- Seq(1, 2, 7, 12, 20, 32) do
      val max =
        if bitWidth == 32 then
          Int.MaxValue
        else
          (1 << bitWidth) - 1
      val values =
        Array.tabulate(4001)(i =>
          if i % 900 < 200 then
            1
          else
            rnd.nextInt(max)
        )
      val bytes = encode(values, bitWidth)
      val dec   = RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth)
      val dst   = new Array[Int](values.length)
      var i     = 0
      for k <- Iterator.continually(Seq(1, 9, 1000, 37)).flatten.takeWhile(_ => i < values.length)
      do
        val m = math.min(k, values.length - i)
        dec.read(dst, i, m)
        i += m
      dst.toSeq shouldBe values.toSeq
  }

  test("look up dictionary indices from RLE and bit-packed runs") {
    val dictSize = 300
    val dict     = LongBuffer(dictSize)
    (0 until dictSize).foreach(i => dict.add(i * 1000003L))
    val idx   = indices(6007, dictSize)
    val bytes = encode(idx, BitPacking.bitWidthOf(dictSize - 1))
    for block <- Seq(1, 64, 1024) do
      val out = LongBuffer(16)
      RleBitPackedDecoder(bytes, 0, bytes.length, BitPacking.bitWidthOf(dictSize - 1))
        .readDictionary(out, dict, idx.length, new Array[Int](block))
      out.values.take(out.size).toSeq shouldBe idx.map(_ * 1000003L).toSeq
  }

  test("look up binary dictionary entries, keeping their indices") {
    val dict = BinaryBuffer(8)
    (0 until 5).foreach(i => dict.add(s"v${i}".getBytes("UTF-8"), 0, 2))
    val idx   = indices(2000, 5)
    val bytes = encode(idx, 3)
    val out   = BinaryBuffer(16)
    RleBitPackedDecoder(bytes, 0, bytes.length, 3).readDictionary(
      out,
      dict,
      idx.length,
      new Array[Int](100)
    )
    (0 until idx.length).map(i => String(out.bytes(i), "UTF-8")) shouldBe
      idx.map(k => s"v${k}").toSeq
    (0 until idx.length).map(out.dictIndex) shouldBe idx.toSeq
  }

  test("reject dictionary indices out of range, in RLE and bit-packed runs") {
    val dict = IntBuffer(4)
    (0 until 5).foreach(dict.add)
    // An RLE run of 7, then scattered values with one 6
    val runs   = Array.fill(100)(7)
    val packed =
      Array.tabulate(100)(i =>
        if i == 57 then
          6
        else
          i % 5
      )
    for values <- Seq(runs, packed) do
      val bytes = encode(values, 3)
      intercept[ParquetException] {
        RleBitPackedDecoder(bytes, 0, bytes.length, 3).readDictionary(
          IntBuffer(16),
          dict,
          values.length,
          new Array[Int](32)
        )
      }
  }

end BitUnpackTest
