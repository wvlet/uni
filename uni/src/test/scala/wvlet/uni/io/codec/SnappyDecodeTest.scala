package wvlet.uni.io.codec

import scala.collection.mutable.ArrayBuffer
import wvlet.uni.test.UniTest

/**
  * Round trips through Snappy streams built tag by tag, so every tag form, offset, and length
  * reaches the decoder's fast loop and its checked tail, including copies and literals that end at
  * the last byte of the output or the input
  */
class SnappyDecodeTest extends UniTest:
  private val rnd = scala.util.Random(7)

  /** A Snappy stream and the bytes it decodes to, built one tag at a time */
  private class TagStream:
    val tags = ArrayBuffer.empty[Byte]
    val out  = ArrayBuffer.empty[Byte]
    // Output bytes that raw tags claim on top of `out`, for streams the decoder must reject
    var claimed = 0

    /** Appends tags as they are, which the stream says decode to `n` more bytes */
    def raw(bytes: Array[Byte], n: Int): TagStream =
      tags ++= bytes
      claimed += n
      this

    def literal(bytes: Array[Byte]): TagStream =
      val n = bytes.length - 1
      if n < 60 then
        tags += (n << 2).toByte
      else
        val extra = Iterator.from(1).find(k => n < (1L << (8 * k))).get
        tags += ((59 + extra) << 2).toByte
        for i <- 0 until extra do
          tags += (n >>> (8 * i)).toByte
      tags ++= bytes
      out ++= bytes
      this

    def literal(n: Int): TagStream = literal(Array.fill(n)(rnd.nextInt(256).toByte))

    /** A copy in the given form: 1 (len 4-11, offset < 2048), 2, or 4 offset bytes */
    def copy(offset: Int, len: Int, form: Int): TagStream =
      form match
        case 1 =>
          tags += (((offset >>> 8) << 5) | ((len - 4) << 2) | 1).toByte
          tags += offset.toByte
        case 2 =>
          tags += (((len - 1) << 2) | 2).toByte
          tags += offset.toByte
          tags += (offset >>> 8).toByte
        case _ =>
          tags += (((len - 1) << 2) | 3).toByte
          for i <- 0 until 4 do
            tags += (offset >>> (8 * i)).toByte
      val from = out.length - offset
      for i <- 0 until len do
        out += out(from + i)
      this

    /** The stream: the output length as a varint, then the tags */
    def bytes: Array[Byte] =
      val b = ArrayBuffer.empty[Byte]
      var n = out.length + claimed
      while n >= 0x80 do
        b += ((n & 0x7f) | 0x80).toByte
        n >>>= 7
      b += n.toByte
      (b ++ tags).toArray

  end TagStream

  private def check(s: TagStream): Unit =
    val c = s.bytes
    Snappy.decompress(c, 0, c.length, s.out.length).toSeq shouldBe s.out.toSeq

  test("copies at every offset from 1 to 64 and every length, in each form") {
    for
      form   <- Seq(1, 2, 4)
      offset <- 1 to 64
    do
      val s    = TagStream().literal(64)
      val lens =
        if form == 1 then
          4 to 11
        else
          1 to 64
      for len <- lens do
        s.copy(offset, len, form)
      // Copies that end at the last output byte take the checked tail
      check(s)
      // and with more output after them, the fast loop
      check(s.literal(200))
  }

  test("literals of every length form, at the end of the output and before more tags") {
    for n <- (1 to 70) ++ Seq(255, 256, 257, 65535, 65536, 65537, 70000) do
      check(TagStream().literal(n))
      check(TagStream().literal(n).copy(1, 64, 2).literal(100))
  }

  test("a stream at the end of its array and inside a longer one") {
    val s = TagStream().literal(10)
    for i <- 0 until 500 do
      if rnd.nextInt(3) == 0 then
        s.literal(1 + rnd.nextInt(20))
      else
        s.copy(1 + rnd.nextInt(s.out.length.min(2000)), 1 + rnd.nextInt(64), 2)
    val c = s.bytes
    check(s)
    // The stream inside a longer array, with bytes before and after it
    val padded = Array.fill[Byte](7)(1) ++ c ++ Array.fill[Byte](40)(2)
    Snappy.decompress(padded, 7, c.length, s.out.length).toSeq shouldBe s.out.toSeq
  }

  test("leaves the bytes of a longer output buffer past expectedSize as they were") {
    val s = TagStream().literal(20)
    for _ <- 0 until 50 do
      s.copy(8 + rnd.nextInt(12), 1 + rnd.nextInt(16), 2).literal(1 + rnd.nextInt(3))
    val c   = s.bytes
    val n   = s.out.length
    val out = Array.fill[Byte](n + 200)(0x5a.toByte)
    Snappy.decompressInto(c, 0, c.length, out, n)
    out.take(n).toSeq shouldBe s.out.toSeq
    out.drop(n).forall(_ == 0x5a.toByte) shouldBe true
  }

  test("rejects copies outside the output and literals past the input") {
    def corrupt(c: Array[Byte], n: Int, message: String = ""): Unit =
      val e = intercept[CodecException] {
        Snappy.decompress(c, 0, c.length, n)
      }
      e.getMessage shouldContain message
    val base = TagStream().literal(100)
    // A zero offset, and an offset before the start of the output: a 4-byte copy-2 tag
    for offset <- Seq(0, 101, 0xffff) do
      val copy = Array[Byte](((4 - 1) << 2 | 2).toByte, offset.toByte, (offset >>> 8).toByte)
      // As the last tag, which the checked tail decodes
      corrupt(TagStream().literal(100).raw(copy, 4).bytes, 104, s"copy offset ${offset} ")
      // With more tags after it, so the copy is in the fast loop
      val s = TagStream().literal(100).raw(copy, 4)
      s.literal(100)
      corrupt(s.bytes, 204, s"copy offset ${offset} ")
    // A literal longer than the rest of the input
    val c = base.bytes
    corrupt(c.dropRight(1), 100)
    // A long literal length of -1
    corrupt(Array[Byte](100, (63 << 2).toByte, -1, -1, -1, -1) ++ Array.fill[Byte](20)(0), 100)
  }

end SnappyDecodeTest
