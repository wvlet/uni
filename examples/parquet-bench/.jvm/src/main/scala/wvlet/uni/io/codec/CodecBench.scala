package wvlet.uni.io.codec

import java.nio.file.{Files, Paths}

/**
  * Isolated codec benchmark: splits a file into 1 MiB chunks, compresses each with snappy-java and
  * zstd-jni, then times Uni's decoders against the native ones. It also times uni's Snappy
  * compressor against snappy-java's, after checking that snappy-java reads uni's output.
  *
  * {{{
  * java -cp examples/parquet-bench/out/lib/(all jars) wvlet.uni.io.codec.CodecBench <file> [rounds] [name filter]
  * }}}
  */
object CodecBench:
  private val ChunkSize = 1 << 20

  def main(args: Array[String]): Unit =
    val data   = Files.readAllBytes(Paths.get(args(0)))
    val rounds = args.lift(1).map(_.toInt).getOrElse(15)
    val filter = args.lift(2).getOrElse("")
    val chunks = data.grouped(ChunkSize).toArray
    val snappy = chunks.map(c => org.xerial.snappy.Snappy.compress(c))
    val zstd   = chunks.map(c => com.github.luben.zstd.Zstd.compress(c, 3))
    println(
      s"${chunks.length} chunks, snappy ${snappy.map(_.length).sum} bytes, zstd ${zstd
          .map(_.length)
          .sum} bytes"
    )

    def check(name: String, decode: Int => Array[Byte]): Unit =
      var i = 0
      while i < chunks.length do
        if !java.util.Arrays.equals(decode(i), chunks(i)) then
          sys.error(s"${name}: chunk ${i} differs")
        i += 1

    check("uni snappy", i => Snappy.decompress(snappy(i), 0, snappy(i).length, chunks(i).length))
    check("uni zstd", i => Zstd.decompress(zstd(i), 0, zstd(i).length, chunks(i).length))

    def time(name: String)(decode: Int => Array[Byte]): Unit = if name.contains(filter) then
      val times = Array.newBuilder[Double]
      var r     = 0
      while r < rounds do
        val start = System.nanoTime()
        var i     = 0
        while i < chunks.length do
          decode(i)
          i += 1
        times += (System.nanoTime() - start) / 1e6
        r += 1
      val sorted = times.result().drop(rounds / 3).sorted
      println(f"${name}%-16s median ${sorted(sorted.length / 2)}%7.1f ms  min ${sorted.head}%7.1f ms")

    time("uni snappy")(i => Snappy.decompress(snappy(i), 0, snappy(i).length, chunks(i).length))
    time("snappy-java")(i => org.xerial.snappy.Snappy.uncompress(snappy(i)))
    time("uni zstd")(i => Zstd.decompress(zstd(i), 0, zstd(i).length, chunks(i).length))
    time("zstd-jni")(i => com.github.luben.zstd.Zstd.decompress(zstd(i), chunks(i).length))

    // Compression: snappy-java must read what uni writes
    val uniSnappy = chunks.map(c => SnappyCompressor.compress(c, c.length))
    check("snappy-java reads uni", i => org.xerial.snappy.Snappy.uncompress(uniSnappy(i)))
    println(s"uni snappy compress: ${uniSnappy.map(_.length).sum} bytes")
    time("uni snappy compress")(i => SnappyCompressor.compress(chunks(i), chunks(i).length))
    time("snappy-java compress")(i => org.xerial.snappy.Snappy.compress(chunks(i)))

  end main

end CodecBench
