package wvlet.uni.parquet.codec

import wvlet.uni.test.UniTest

/**
  * Wide copies may write past the end of a copy, so these tests fill whole arrays with random copy
  * programs and compare against byte-at-a-time copies, including copies that end at the array's
  * last byte.
  */
class WideCopyTest extends UniTest:
  private val rnd = scala.util.Random(7)

  test("le64 reads 8 bytes at every offset, up to the end of the array") {
    for size <- 8 to 40 do
      val b = Array.fill[Byte](size)(rnd.nextInt().toByte)
      for i <- 0 to size - 8 do
        var want = 0L
        for k <- 7 to 0 by -1 do
          want = (want << 8) | (b(i + k) & 0xffL)
        Word8.le64(b, i) shouldBe want
  }

  test("matchLength counts equal bytes up to the limit, at every offset") {
    for size <- 1 to 48 do
      // Few distinct bytes, so matches of every length turn up
      val b = Array.fill[Byte](size)(rnd.nextInt(2).toByte)
      for
        x     <- 0 until size
        y     <- x + 1 until size
        limit <- y to size
      do
        var want = 0
        while y + want < limit && b(x + want) == b(y + want) do
          want += 1
        Word8.matchLength(b, x, y, limit) shouldBe want
  }

  /** A copy program: (isMatch, length, offset), filling exactly `size` bytes */
  private def program(size: Int): Seq[(Boolean, Int, Int)] =
    val ops = Seq.newBuilder[(Boolean, Int, Int)]
    var op  = 0
    while op < size do
      val len = math.min(size - op, rnd.nextInt(65))
      if op >= 1 && rnd.nextBoolean() then
        ops += ((true, len, 1 + rnd.nextInt(math.min(op, 24))))
      else
        ops += ((false, len, 0))
      op += len
    ops.result()

  private def run(size: Int, src: Array[Byte], wide: Boolean): Array[Byte] =
    val out = new Array[Byte](size)
    var op  = 0
    var sp  = 0
    for (isMatch, len, offset) <- program(size) do
      if isMatch then
        if wide then
          WideCopy.matchCopy(out, op, offset, len)
        else
          var i = 0
          while i < len do
            out(op + i) = out(op - offset + i)
            i += 1
      else if wide then
        WideCopy.literal(src, sp, out, op, len)
        sp += len
      else
        System.arraycopy(src, sp, out, op, len)
        sp += len
      op += len
    out

  test("wide copies match byte copies up to the end of the output") {
    for size <- (0 to 40) ++ Seq(100, 1000, 4096) do
      for trial <- 0 until 20 do
        // The literal source is exactly as long as the program needs at most, so literal reads
        // near its end take the fallback too
        val src  = Array.fill(size)(rnd.nextInt(256).toByte)
        val seed = rnd.nextLong()
        rnd.setSeed(seed)
        val expected = run(size, src, wide = false)
        rnd.setSeed(seed)
        val actual = run(size, src, wide = true)
        actual.toSeq shouldBe expected.toSeq
  }

  test("snappy decodes short copies near the end of the output") {
    // Literal "abcdefghij", then copies of 1-16 bytes at offsets 1-10, the last ending at the
    // final output byte
    val ops    = (1 to 16).map(len => (len, 1 + len % 10))
    val stream = Array.newBuilder[Byte]
    stream += ((10 - 1) << 2).toByte
    stream ++= "abcdefghij".getBytes("UTF-8")
    for (len, offset) <- ops do
      // The 2-byte offset form: tag (len - 1) << 2 | 2, then the offset as 16-bit little endian
      stream += (((len - 1) << 2) | 2).toByte
      stream += offset.toByte
      stream += 0.toByte
    // The expected bytes, copied one at a time (copies may overlap themselves)
    val ref = new Array[Byte](10 + ops.map(_._1).sum)
    System.arraycopy("abcdefghij".getBytes("UTF-8"), 0, ref, 0, 10)
    var op = 10
    for (len, offset) <- ops do
      for i <- 0 until len do
        ref(op + i) = ref(op - offset + i)
      op += len
    val body       = stream.result()
    val header     = varint(ref.length)
    val compressed = header ++ body
    Snappy.decompress(compressed, 0, compressed.length, ref.length).toSeq shouldBe ref.toSeq
  }

  private def varint(n: Int): Array[Byte] =
    val b = Array.newBuilder[Byte]
    var v = n
    while v >= 0x80 do
      b += ((v & 0x7f) | 0x80).toByte
      v >>>= 7
    b += v.toByte
    b.result()

end WideCopyTest
