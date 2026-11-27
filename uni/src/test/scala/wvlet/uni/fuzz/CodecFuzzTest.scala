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
package wvlet.uni.fuzz

import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.codec.*
import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.FuzzConfig

/**
  * Fuzz Uni's decompressors, which read Parquet pages, Avro blocks, and HTTP bodies from anywhere.
  * Corrupt input must fail with [[CodecException]], never an index or allocation error, and what a
  * compressor writes must decompress to the same bytes.
  *
  * A decoder target's input is a 2-byte big-endian expected size followed by the compressed bytes,
  * so the fuzzer also explores sizes that don't match the data.
  */
class CodecFuzzTest extends UniTest:
  FileSystemInit.init()

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(
      // The decoders are shared Scala code; Native runs them in a slow debug build
      if isScalaNative then
        300
      else
        2000
    )
    .withCorpusDir("uni/src/test/resources/fuzz")
    .withExpectedCases { case _: CodecException =>
    }

  private def sized(expectedSize: Int, compressed: Array[Byte]): Array[Byte] =
    Array((expectedSize >>> 8).toByte, expectedSize.toByte) ++ compressed

  private def decode(target: String, seeds: Seq[Array[Byte]])(
      f: (Array[Byte], Int, Int, Int) => Any
  ): Unit =
    fuzz(target, fuzzConfig.withSeedBytes(seeds*)) { bytes =>
      if bytes.length >= 2 then
        val size = ((bytes(0) & 0xff) << 8) | (bytes(1) & 0xff)
        f(bytes, 2, bytes.length - 2, size)
    }

  private val hello = "hello".getBytes("UTF-8")
  private val text  = CodecTestData.text(2000, 1)
  // Short runs and repeats, so compressors emit both literals and matches
  private val plain = Seq(Array.emptyByteArray, hello, text, Array.fill(300)('x'.toByte))

  private def fixtures(names: String*): Seq[Array[Byte]] = names.map(n =>
    sized(hello.length, CodecTestData.file(n))
  )

  test("Snappy rejects corrupt input with CodecException") {
    decode(
      "snappy-decode",
      plain.map(p => sized(p.length, SnappyCompressor.compress(p, p.length)))
    )(Snappy.decompress)
  }

  test("LZ4 rejects corrupt input with CodecException") {
    decode("lz4-decode", plain.map(p => sized(p.length, Lz4Compressor.compress(p, p.length))))(
      Lz4.decompress
    )
  }

  test("LZ4 frames reject corrupt input with CodecException") {
    decode("lz4-frame-decode", plain.map(p => sized(p.length, Lz4Frame.compress(p, 0, p.length))))(
      Lz4Frame.decompress
    )
  }

  test("zstd rejects corrupt input with CodecException") {
    val seeds =
      plain.map(p => sized(p.length, ZstdCompressor.compress(p, p.length))) ++
        fixtures("small.19.zst", "small.frames.zst", "small.long.zst", "small.nocheck.zst")
    decode("zstd-decode", seeds)(Zstd.decompress)
  }

  test("zstd frames of unknown size reject corrupt input with CodecException") {
    val seeds = plain.map(p => sized(0, ZstdCompressor.compress(p, p.length)))
    decode("zstd-decode-unknown-size", seeds)((b, off, len, _) =>
      Zstd.decompressUnknownSize(b, off, len)
    )
  }

  test("gzip rejects corrupt input with CodecException") {
    decode("gzip-decode", fixtures("small.gz", "small.1.gz"))((b, off, len, size) =>
      Inflate.gunzip(b.slice(off, off + len), size)
    )
  }

  test("DEFLATE rejects corrupt input with CodecException") {
    // The raw DEFLATE stream inside a gzip member with no optional header fields
    val seeds = fixtures("small.gz", "small.1.gz", "text.gz")
      .map(_.drop(2))
      .filter(g => g(3) == 0)
      .map(g => sized(hello.length, g.slice(10, g.length - 8)))
    decode("deflate-decode", seeds)(Inflate.inflate)
  }

  test("Brotli rejects corrupt input with CodecException") {
    decode("brotli-decode", fixtures("small.q1.br", "small.q5.br", "small.q11.br", "small.w24.br"))(
      Brotli.decompress
    )
  }

  // Literal runs, byte runs, and repeats of earlier output, so matches of every length and
  // distance appear
  private val data: Gen[Array[Byte]] =
    val literal = Gen.listOf(Gen.chooseNum(0, 255)).map(_.map(_.toByte).toArray)
    val run     =
      for
        b <- Gen.chooseNum(0, 255)
        n <- Gen.chooseNum(1, 400)
      yield Array.fill(n)(b.toByte)
    val repeat =
      for
        dist <- Gen.chooseNum(1, 70000)
        n    <- Gen.chooseNum(4, 300)
      yield (dist, n)
    Gen
      .listOf(
        Gen.frequency(3 -> literal.map(Left(_)), 2 -> run.map(Left(_)), 3 -> repeat.map(Right(_)))
      )
      .map { parts =>
        val out = java.io.ByteArrayOutputStream()
        parts.foreach {
          case Left(bytes) =>
            out.write(bytes, 0, bytes.length)
          case Right((dist, n)) =>
            val sofar = out.toByteArray
            if sofar.nonEmpty then
              val from = math.max(0, sofar.length - dist)
              for i <- 0 until n do
                out.write(sofar(from + i % (sofar.length - from)))
        }
        out.toByteArray
      }

  end data

  private def roundTrip(
      target: String
  )(compress: Array[Byte] => Array[Byte])(decompress: (Array[Byte], Int) => Array[Byte]): Unit =
    fuzzGen(target, data) { in =>
      val out = decompress(compress(in), in.length)
      if !java.util.Arrays.equals(out, in) then
        fail(s"decompressed ${out.length} bytes differ from the ${in.length} compressed")
    }

  test("Snappy decompresses what it compresses") {
    roundTrip("snappy-round-trip")(b => SnappyCompressor.compress(b, b.length))((c, n) =>
      Snappy.decompress(c, 0, c.length, n)
    )
  }

  test("LZ4 decompresses what it compresses") {
    roundTrip("lz4-round-trip")(b => Lz4Compressor.compress(b, b.length))((c, n) =>
      Lz4.decompress(c, 0, c.length, n)
    )
  }

  test("LZ4 frames decompress what they compress") {
    roundTrip("lz4-frame-round-trip")(b => Lz4Frame.compress(b, 0, b.length))((c, n) =>
      Lz4Frame.decompress(c, 0, c.length, n)
    )
  }

  test("zstd decompresses what it compresses, at every level") {
    fuzzGen("zstd-round-trip", data.zip(Gen.chooseNum(1, 22))) { (in, level) =>
      val c   = ZstdCompressor.compress(in, in.length, level)
      val out = Zstd.decompress(c, 0, c.length, in.length)
      if !java.util.Arrays.equals(out, in) then
        fail(s"level ${level}: decompressed bytes differ from the ${in.length} compressed")
      val any = Zstd.decompressUnknownSize(c, 0, c.length)
      if !java.util.Arrays.equals(any, in) then
        fail(s"level ${level}: decompressUnknownSize differs from the ${in.length} compressed")
    }
  }

end CodecFuzzTest
