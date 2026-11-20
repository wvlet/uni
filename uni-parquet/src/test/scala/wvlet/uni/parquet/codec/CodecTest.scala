package wvlet.uni.parquet.codec

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.parquet.ParquetException
import wvlet.uni.test.UniTest

/** Rebuilds the inputs of scripts/gen-codec-files.py */
object CodecTestData:
  private val words = Seq(
    "parquet",
    "column",
    "row",
    "group",
    "page",
    "dictionary",
    "encoding",
    "scala",
    "uni",
    "value",
    "level",
    "null",
    "struct",
    "list",
    "map",
    "compression",
    "zstd",
    "snappy",
    "brotli",
    "lz4",
    "the",
    "a",
    "of",
    "and",
    "to",
    "in",
    "is",
    "for"
  )

  private final class Lcg(seed: Long):
    private var s    = seed
    def next(): Long =
      s = s * 6364136223846793005L + 1442695040888963407L
      s >>> 33

  def text(n: Int, seed: Long): Array[Byte] =
    val g   = Lcg(seed)
    val out = java.io.ByteArrayOutputStream()
    while out.size() < n do
      val w = words((g.next() % words.size).toInt).getBytes("UTF-8")
      out.write(w, 0, w.length)
      out.write(
        if g.next() % 11 == 0 then
          '\n'
        else
          ' '
      )
    java.util.Arrays.copyOf(out.toByteArray, n)

  def randomBytes(n: Int, seed: Long): Array[Byte] =
    val g = Lcg(seed)
    Array.fill(n)((g.next() & 0xff).toByte)

  val inputs: Seq[(String, () => Array[Byte])] = Seq(
    "empty" -> (() => Array.emptyByteArray),
    "mixed" -> (
      () =>
        text(100000, 3) ++ randomBytes(10000, 4) ++ Array.fill(140000)('z'.toByte) ++ text(50000, 1)
    ),
    "random" -> (() => randomBytes(20000, 2)),
    "repeat" -> (() => Array.fill(300000)('x'.toByte)),
    "small"  -> (() => "hello".getBytes("UTF-8")),
    "text"   -> (() => text(300000, 1))
  )

  def file(name: String): Array[Byte] =
    val roots = Seq(
      "uni-parquet/src/test/resources/codec",
      "src/test/resources/codec",
      "../src/test/resources/codec"
    )
    roots
      .map(r => IOPath.parse(s"${r}/${name}"))
      .find(FileSystem.exists)
      .map(FileSystem.readBytes)
      .getOrElse(throw IllegalArgumentException(s"Codec fixture ${name} not found"))

end CodecTestData

class CodecTest extends UniTest:
  FileSystemInit.init()
  import CodecTestData.*

  private def check(file: String, input: Array[Byte])(
      decode: (Array[Byte], Int) => Array[Byte]
  ): Unit =
    val compressed = CodecTestData.file(file)
    val out        = decode(compressed, input.length)
    out.length shouldBe input.length
    java.util.Arrays.equals(out, input) shouldBe true

  for (name, gen) <- inputs do
    lazy val input = gen()
    for tag <- Seq("1", "3", "9", "19", "22", "long", "nocheck", "frames") do
      test(s"zstd ${name}.${tag}") {
        check(s"${name}.${tag}.zst", input)((c, n) => Zstd.decompress(c, 0, c.length, n))
      }

  test("zstd rejects corrupt input without crashing") {
    val good = file("text.3.zst")
    val n    = text(300000, 1).length
    val rnd  = scala.util.Random(42)
    for i <- 0 until 200 do
      val bad = good.clone()
      // Flip a few bytes after the frame header
      for _ <- 0 until 3 do
        val p = 6 + rnd.nextInt(bad.length - 6)
        bad(p) = (bad(p) ^ (1 + rnd.nextInt(255))).toByte
      try
        Zstd.decompress(bad, 0, bad.length, n)
      catch
        case _: ParquetException =>
        // expected
    // Truncated input
    for len <- Seq(0, 3, 5, 10, good.length / 2, good.length - 1) do
      intercept[ParquetException] {
        Zstd.decompress(good, 0, len, n)
      }
  }

  test("snappy decodes literals and overlapping copies") {
    // "abcabcabcabc": a literal "abc", then a 9-byte copy at offset 3 (the 1-byte offset form)
    val compressed = Array[Byte](12, 0x08, 'a', 'b', 'c', ((9 - 4) << 2 | 1).toByte, 3)
    String(Snappy.decompress(compressed, 0, compressed.length, 12), "UTF-8") shouldBe "abcabcabcabc"
    intercept[ParquetException] {
      Snappy.decompress(compressed, 0, compressed.length, 11)
    }
  }

  test("lz4 decodes a block with an overlapping match") {
    // Token: 3 literals and match length 4 + 5, then offset 3, then an empty last sequence
    val block = Array[Byte](0x35, 'a', 'b', 'c', 3, 0, 0x00)
    String(Lz4.decompress(block, 0, block.length, 12), "UTF-8") shouldBe "abcabcabcabc"
  }

end CodecTest
