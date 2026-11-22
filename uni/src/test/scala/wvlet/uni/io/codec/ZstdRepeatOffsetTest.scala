package wvlet.uni.io.codec

import wvlet.uni.test.UniTest

/**
  * Repeat-offset cases that zstd's compressor rarely emits, so the codec fixtures miss them. Each
  * frame is built by hand: a raw block, then a compressed block whose sequences use RLE tables, so
  * the sequence bitstream holds only each sequence's one offset bit.
  */
class ZstdRepeatOffsetTest extends UniTest:

  private val prefix = "0123456789abcdef".getBytes("UTF-8")

  /**
    * A frame with `prefix` in a raw block, then one sequence per offset value (2 or 3, offset code
    * 1) with `ll` literals each and a 3-byte match. The initial repeat offsets are 1, 4, 8.
    */
  private def frame(ll: Int, offsetValues: Seq[Int]): Array[Byte] =
    val literals = Array.tabulate[Byte](ll * offsetValues.size)(i => ('A' + i).toByte)
    // Backward bitstream: the decoder reads the first sequence's bit first, so it is written last
    var bits  = 0
    var nBits = 0
    for v <- offsetValues.reverse do
      bits |= (v - 2) << nBits
      nBits += 1
    bits |= 1 << nBits // end marker
    val stream = Array.tabulate[Byte]((nBits + 8) / 8)(i => ((bits >>> (8 * i)) & 0xff).toByte)
    val llCode = ll // literal-length codes 0-15 are the length itself
    val body   =
      Array[Byte]((literals.length << 3).toByte) ++ literals ++
        Array[Byte](offsetValues.size.toByte, 0x54, llCode.toByte, 1, 0) ++ stream
    val size = prefix.length + offsetValues.size * (ll + 3)
    def blockHeader(last: Boolean, blockType: Int, size: Int): Array[Byte] =
      val h =
        (size << 3) |
          (blockType << 1) | (
            if last then
              1
            else
              0
          )
      Array[Byte](h.toByte, (h >>> 8).toByte, (h >>> 16).toByte)
    Array[Byte](0x28, 0xb5.toByte, 0x2f, 0xfd.toByte, 0x20, size.toByte) ++
      blockHeader(false, 0, prefix.length) ++ prefix ++ blockHeader(true, 2, body.length) ++ body

  /** The expected output: each sequence copies `ll` literals, then 3 bytes from `offset` back */
  private def expected(ll: Int, offsets: Seq[Int]): Array[Byte] =
    val out = scala.collection.mutable.ArrayBuffer.from(prefix)
    var lit = 0
    for o <- offsets do
      for _ <- 0 until ll do
        out += ('A' + lit).toByte
        lit += 1
      for _ <- 0 until 3 do
        out += out(out.length - o)
    out.toArray

  private def decode(f: Array[Byte], size: Int): Array[Byte] = Zstd.decompress(f, 0, f.length, size)

  test("offset values 2 and 3 with literals pick repeat offsets 2 and 3") {
    // reps 1,4,8 -> 2: rep 4 (4,1,8) -> 2: rep 1 (1,4,8) -> 3: rep 8 (8,1,4)
    val want = expected(1, Seq(4, 1, 8))
    decode(frame(1, Seq(2, 2, 3)), want.length).toSeq shouldBe want.toSeq
  }

  test("offset values 2 and 3 without literals pick repeat offset 3 and repeat offset 1 minus 1") {
    // reps 1,4,8 -> 2: rep 8 (8,1,4) -> 3: 8 - 1 (7,8,1) -> 2: rep 1 (1,7,8)
    val want = expected(0, Seq(8, 7, 1))
    decode(frame(0, Seq(2, 3, 2)), want.length).toSeq shouldBe want.toSeq
  }

  test("repeat offset 1 minus 1 rejects a zero offset") {
    // reps 1,4,8 -> 3 without literals: 1 - 1 = 0
    val e = intercept[CodecException] {
      decode(frame(0, Seq(3)), prefix.length + 3)
    }
    e.getMessage shouldContain "offset"
  }

end ZstdRepeatOffsetTest
