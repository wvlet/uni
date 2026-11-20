package wvlet.uni.parquet.codec

import java.io.File
import java.nio.file.Files

/**
  * Isolated ZSTD decompression benchmark on Scala Native, the counterpart of the JVM's CodecBench.
  * Native has no zstd-jni, so the chunks are compressed beforehand with the zstd command:
  *
  * {{{
  * split -b 1048576 <file> <dir>/chunk- && zstd -3 -q <dir>/chunk-*
  * uni-example-parquet-bench codec <dir> [rounds]
  * }}}
  */
object CodecBench:

  def main(args: Array[String]): Unit =
    val dir    = File(args(0))
    val rounds = args.lift(1).map(_.toInt).getOrElse(15)
    val files  = Option(dir.listFiles()).getOrElse(Array.empty[File])
    val names  = files.map(_.getName).filter(_.endsWith(".zst")).sorted
    if names.isEmpty || rounds < 1 then
      sys.error(s"No .zst chunks in ${dir} or rounds < 1: run split and zstd -3 first")
    val chunks = names.map(n => Files.readAllBytes(File(dir, n.stripSuffix(".zst")).toPath))
    val zstd   = names.map(n => Files.readAllBytes(File(dir, n).toPath))
    println(s"${chunks.length} chunks, zstd ${zstd.map(_.length).sum} bytes")

    var i = 0
    while i < chunks.length do
      val out = Zstd.decompress(zstd(i), 0, zstd(i).length, chunks(i).length)
      if !java.util.Arrays.equals(out, chunks(i)) then
        sys.error(s"uni zstd: chunk ${i} differs")
      i += 1

    val times = Array.newBuilder[Double]
    var r     = 0
    while r < rounds do
      val start = System.nanoTime()
      var i     = 0
      while i < chunks.length do
        Zstd.decompress(zstd(i), 0, zstd(i).length, chunks(i).length)
        i += 1
      times += (System.nanoTime() - start) / 1e6
      r += 1
    val sorted = times.result().drop(rounds / 3).sorted
    println(
      f"uni zstd         median ${sorted(sorted.length / 2)}%7.1f ms  min ${sorted.head}%7.1f ms"
    )

  end main

end CodecBench
