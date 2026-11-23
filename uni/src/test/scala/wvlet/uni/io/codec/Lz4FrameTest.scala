package wvlet.uni.io.codec

import wvlet.uni.test.UniTest

class Lz4FrameTest extends UniTest:

  test("xxHash32 matches the reference values") {
    XxHash32.hash(Array.emptyByteArray, 0, 0, 0) shouldBe 0x02cc5d05
    XxHash32.hash("abc".getBytes, 0, 3, 0) shouldBe 0x32d153ff
    val text = "Nobody inspects the spammish repetition".getBytes
    XxHash32.hash(text, 0, text.length, 0) shouldBe 0xe2293b2f
  }

  test("round-trip frames of every size") {
    val rnd = scala.util.Random(7)
    for n <- Seq(0, 1, 15, 100, 70000, (4 << 20) + 123) do
      // Half compressible text, half random bytes
      val src =
        Array.tabulate[Byte](n) { i =>
          if i % 2 == 0 then
            (i % 31).toByte
          else
            rnd.nextInt().toByte
        }
      val frame = Lz4Frame.compress(src, 0, n)
      Lz4Frame.decompress(frame, 0, frame.length, n).toSeq shouldBe src.toSeq
      Lz4Frame.decompress(frame, 0, frame.length, -1).length shouldBe n
  }

  test("compress a slice of an array") {
    val src   = Array.tabulate[Byte](1000)(i => (i % 7).toByte)
    val frame = Lz4Frame.compress(src, 100, 500)
    (frame.length < 500) shouldBe true
    Lz4Frame.decompress(frame, 0, frame.length, 500).toSeq shouldBe src.slice(100, 600).toSeq
  }

  test("reject a bad magic number") {
    intercept[CodecException] {
      Lz4Frame.decompress(Array[Byte](1, 2, 3, 4, 0, 0, 0), 0, 7, -1)
    }
  }

end Lz4FrameTest
