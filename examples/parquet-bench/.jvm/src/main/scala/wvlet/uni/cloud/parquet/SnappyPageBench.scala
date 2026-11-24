package wvlet.uni.cloud.parquet

import java.nio.file.{Files, Paths}
import wvlet.uni.cloud.parquet.format.*
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol
import wvlet.uni.io.codec.Snappy

/**
  * Times uni's Snappy decoder on the compressed pages of real Parquet files, such as TPC-H
  * lineitem, against snappy-java. Unlike [[wvlet.uni.io.codec.CodecBench]], which compresses 1 MiB
  * chunks itself, this decodes the pages the file's writer made, so the mix of literal and copy
  * lengths matches what a scan sees.
  *
  * {{{
  * java -cp examples/parquet-bench/out/lib/(all jars) wvlet.uni.cloud.parquet.SnappyPageBench <file.parquet>... [--rounds N]
  * }}}
  */
object SnappyPageBench:

  /** One compressed page: src(offset) to src(offset + length - 1) decode to rawSize bytes */
  private case class Page(src: Array[Byte], offset: Int, length: Int, rawSize: Int)

  def main(args: Array[String]): Unit =
    val rounds = args.sliding(2).collectFirst { case Array("--rounds", n) => n.toInt }.getOrElse(15)
    val files  = args.filter(a => a.endsWith(".parquet"))
    val pages  = files.flatMap(snappyPages)
    val raw    = pages.map(_.rawSize.toLong).sum
    println(
      f"${pages.length} Snappy pages, ${pages.map(_.length.toLong).sum / 1e6}%.1f MB compressed, ${
          raw / 1e6
        }%.1f MB raw"
    )
    if args.contains("--stats") then
      tagStats(pages)
    val out = new Array[Byte](pages.map(_.rawSize).max)

    // Check against snappy-java first
    if !args.contains("--no-check") then pages.foreach { p =>
      Snappy.decompressInto(p.src, p.offset, p.length, out, p.rawSize)
      val expected = org.xerial.snappy.Snappy.uncompress(java.util.Arrays.copyOfRange(p.src, p.offset, p.offset + p.length))
      if !java.util.Arrays.equals(out, 0, p.rawSize, expected, 0, expected.length) then
        sys.error(s"page at ${p.offset} differs")
    }

    def time(name: String)(decode: Page => Unit): Unit =
      val times = Array.newBuilder[Double]
      var r     = 0
      while r < rounds do
        val start = System.nanoTime()
        var i     = 0
        while i < pages.length do
          decode(pages(i))
          i += 1
        times += (System.nanoTime() - start) / 1e6
        r += 1
      val sorted = times.result().drop(rounds / 3).sorted
      val median = sorted(sorted.length / 2)
      println(
        f"${name}%-12s median ${median}%7.1f ms  min ${sorted.head}%7.1f ms  ${raw / median / 1e3}%6.0f MB/s"
      )

    val direct = new Array[Byte](out.length)
    time("uni snappy")(p => Snappy.decompressInto(p.src, p.offset, p.length, out, p.rawSize))
    time("snappy-java")(p =>
      org.xerial.snappy.Snappy.uncompress(p.src, p.offset, p.length, direct, 0)
    )
    time("uni snappy")(p => Snappy.decompressInto(p.src, p.offset, p.length, out, p.rawSize))

  end main

  /**
    * Prints how the pages' tags split into literals and copies, by length and offset, and how many
    * output bytes each kind produces: the shape the decoder's fast paths should fit
    */
  private def tagStats(pages: Seq[Page]): Unit =
    def bucket(n: Int): Int =
      if n <= 8 then 0
      else if n <= 16 then 1
      else if n <= 32 then 2
      else if n <= 64 then 3
      else 4
    val names       = Seq("1-8", "9-16", "17-32", "33-64", "65+")
    val litCount    = new Array[Long](5)
    val litBytes    = new Array[Long](5)
    val copyCount   = new Array[Long](5)
    val copyBytes   = new Array[Long](5)
    val offsetCount = new Array[Long](5)
    for p <- pages do
      val src = p.src
      var ip  = p.offset
      val end = p.offset + p.length
      while (src(ip) & 0x80) != 0 do
        ip += 1
      ip += 1
      while ip < end do
        val tag = src(ip) & 0xff
        ip += 1
        if (tag & 3) == 0 then
          var len = tag >>> 2
          if len >= 60 then
            val n = len - 59
            len = 0
            for i <- 0 until n do
              len |= (src(ip + i) & 0xff) << (8 * i)
            ip += n
          len += 1
          litCount(bucket(len)) += 1
          litBytes(bucket(len)) += len
          ip += len
        else
          val (len, offset) =
            (tag & 3) match
              case 1 =>
                val r = (4 + ((tag >>> 2) & 7), ((tag >>> 5) << 8) | (src(ip) & 0xff))
                ip += 1
                r
              case 2 =>
                val r = ((tag >>> 2) + 1, (src(ip) & 0xff) | ((src(ip + 1) & 0xff) << 8))
                ip += 2
                r
              case _ =>
                val r = ((tag >>> 2) + 1, wvlet.uni.io.codec.Bytes.intLE(src, ip))
                ip += 4
                r
          copyCount(bucket(len)) += 1
          copyBytes(bucket(len)) += len
          offsetCount(bucket(offset)) += 1
      end while
    end for
    println(f"${"length"}%-8s ${"literals"}%12s ${"lit bytes"}%12s ${"copies"}%12s ${"copy bytes"}%12s ${"offsets"}%12s")
    for i <- 0 until 5 do
      println(
        f"${names(i)}%-8s ${litCount(i)}%12d ${litBytes(i)}%12d ${copyCount(i)}%12d ${copyBytes(i)}%12d ${offsetCount(i)}%12d"
      )

  end tagStats

  /** The Snappy-compressed pages of a file, read from its column chunks */
  private def snappyPages(path: String): Seq[Page] =
    val bytes          = Files.readAllBytes(Paths.get(path))
    val (footerAt, len) = ParquetFile.footerRange(bytes, bytes.length.toLong, path)
    val meta           = ParquetFile.parseFooter(bytes, footerAt.toInt, len)
    val pages          = Seq.newBuilder[Page]
    for
      rg <- meta.rowGroups
      c  <- rg.columns
      if c.codec == CompressionCodec.SNAPPY
    do
      var pos = c.startOffset.toInt
      val end = pos + c.totalCompressedSize.toInt
      while pos < end do
        val r = ByteReader(bytes, pos, bytes.length)
        val h = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
        pos = r.pos
        val levels = h.dataPageV2.filter(_ => h.pageType == PageType.DATA_PAGE_V2) match
          case Some(v2) =>
            if v2.isCompressed then v2.definitionLevelsByteLength + v2.repetitionLevelsByteLength
            else -1
          case None =>
            0
        if levels >= 0 && h.uncompressedSize - levels > 0 && h.pageType != PageType.INDEX_PAGE then
          pages += Page(bytes, pos + levels, h.compressedSize - levels, h.uncompressedSize - levels)
        pos += h.compressedSize
    end for
    pages.result()

  end snappyPages

end SnappyPageBench
