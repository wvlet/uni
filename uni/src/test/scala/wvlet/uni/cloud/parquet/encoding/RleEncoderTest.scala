package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.test.UniTest

class RleEncoderTest extends UniTest:
  private val rnd = scala.util.Random(42)

  test("RLE/bit-packing hybrid round trip") {
    for bitWidth <- Seq(1, 3, 8, 13, 31) do
      val max    = (1 << bitWidth) - 1
      val values =
        Array.tabulate(1003) { i =>
          if i % 100 < 40 then
            1
          else
            rnd.nextInt(max)
        }
      val out = ByteBuf()
      RleEncoder.encode(values, values.length, bitWidth, out)
      val bytes   = out.toByteArray
      val decoded = new Array[Int](values.length)
      RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth).read(decoded, 0, decoded.length)
      decoded.toSeq shouldBe values.toSeq
  }

  test("read the values at sparse offsets, skipping the others") {
    for bitWidth <- Seq(1, 3, 8, 13, 31, 32) do
      val values =
        Array.tabulate(5003) { i =>
          if i % 1000 < 400 then
            7 & ((1 << math.min(bitWidth, 30)) - 1)
          else if bitWidth == 32 then
            rnd.nextInt()
          else
            rnd.nextInt(1 << math.min(bitWidth, 30))
        }
      val out = ByteBuf()
      RleEncoder.encode(values, values.length, bitWidth, out)
      val bytes = out.toByteArray
      for step <- Seq(1, 2, 7, 64, 999) do
        val dec = RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth)
        // Two calls in a row: offsets count from where the first one stopped
        val first  = (0 until 2500 by step).toArray
        val second = (0 until 2503 by step).toArray
        val a      = first.clone()
        dec.readAt(a, a.length)
        a.toSeq shouldBe first.toSeq.map(values(_))
        val base = first.last + 1
        val b    = second.clone()
        dec.readAt(b, b.length)
        b.toSeq shouldBe second.toSeq.map(o => values(base + o))
  }

  test("read levels as bytes, counting those at the maximum level") {
    for maxLevel <- Seq(1, 2, 5, 127) do
      val bitWidth = BitPacking.bitWidthOf(maxLevel)
      // Runs of equal levels (RLE) mixed with scattered ones (bit-packed)
      val levels =
        Array.tabulate(5003) { i =>
          if i % 1000 < 300 then
            maxLevel
          else
            rnd.nextInt(maxLevel + 1)
        }
      val out = ByteBuf()
      RleEncoder.encode(levels, levels.length, bitWidth, out)
      val bytes = out.toByteArray
      val dst   = new Array[Byte](levels.length + 3)
      // Read in uneven pieces, so pieces end inside runs
      val dec   = RleBitPackedDecoder(bytes, 0, bytes.length, bitWidth)
      var i     = 0
      var count = 0
      for k <- Iterator.continually(Seq(1, 7, 333, 64)).flatten.takeWhile(_ => i < levels.length) do
        val m = math.min(k, levels.length - i)
        count += dec.readLevels(dst, 3 + i, m, maxLevel)
        i += m
      dst.drop(3).map(_.toInt).toSeq shouldBe levels.toSeq
      count shouldBe levels.count(_ == maxLevel)
  }

  test("reject levels past the maximum") {
    val out = ByteBuf()
    RleEncoder.encode(Array(0, 1, 3, 1, 0, 2, 1, 1), 8, 2, out)
    val bytes = out.toByteArray
    intercept[wvlet.uni.cloud.parquet.ParquetException] {
      RleBitPackedDecoder(bytes, 0, bytes.length, 2).readLevels(new Array[Byte](8), 0, 8, 2)
    }
  }

end RleEncoderTest
