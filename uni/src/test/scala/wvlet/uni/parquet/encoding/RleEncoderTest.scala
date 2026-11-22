package wvlet.uni.parquet.encoding

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

end RleEncoderTest
