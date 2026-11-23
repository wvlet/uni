package wvlet.uni.parquet

import wvlet.uni.io.CRC32

/**
  * Row deletes of an Iceberg table: position-delete files (Parquet rows of a data file path and a
  * row position, format version 2) and deletion vectors (a roaring bitmap of row positions in a
  * Puffin file, format version 3). Equality deletes are refused by the caller.
  */
private[parquet] object IcebergDeletes:

  /** A live delete file of a snapshot */
  case class DeleteFile(
      /** The resolved path of the delete file */
      path: String,
      /** `parquet` for position deletes, `puffin` for a deletion vector */
      format: String,
      /** The data sequence number: it deletes rows of data files whose number is not above it */
      sequenceNumber: Long,
      /** The resolved data file it applies to; required for deletion vectors */
      referencedDataFile: Option[String],
      /** The deletion vector's blob in the Puffin file */
      contentOffset: Option[Long],
      contentSize: Option[Long]
  ):
    def isDeletionVector: Boolean = format.equalsIgnoreCase("puffin")

  /** Data file path -> deleted row positions */
  type Positions = Map[String, Array[Long]]

  /**
    * The deleted positions of each data file, from the delete files and their bytes. `dataFiles`
    * gives each data file's sequence number; a delete applies to a data file whose sequence number
    * is not above its own.
    */
  def positions(
      deletes: IndexedSeq[DeleteFile],
      bytes: Map[String, Array[Byte]],
      dataFiles: Map[String, Long],
      resolve: String => String
  ): Positions =
    val byFile = scala
      .collection
      .mutable
      .HashMap
      .empty[String, scala.collection.mutable.ArrayBuilder.ofLong]
    def add(dataPath: String, del: DeleteFile, pos: Long): Unit =
      dataFiles.get(dataPath) match
        case Some(seq) if seq <= del.sequenceNumber =>
          byFile.getOrElseUpdate(dataPath, scala.collection.mutable.ArrayBuilder.ofLong()) += pos
        case _ =>
          ()
    deletes.foreach { d =>
      val b = bytes(d.path)
      if d.isDeletionVector then
        val target = d
          .referencedDataFile
          .getOrElse(throw ParquetException(s"Deletion vector ${d.path} names no data file"))
        val offset = d
          .contentOffset
          .getOrElse(throw ParquetException(s"Deletion vector in ${d.path} has no content_offset"))
        val size = d
          .contentSize
          .getOrElse(
            throw ParquetException(s"Deletion vector in ${d.path} has no content_size_in_bytes")
          )
        if dataFiles.get(target).exists(_ <= d.sequenceNumber) then
          deletionVector(b, offset, size, d.path).foreach(add(target, d, _))
      else if d.format.equalsIgnoreCase("parquet") then
        val f = Parquet.open(b)
        try f
            .readBatches(ParquetReadOptions.default.withColumns("file_path", "pos"))
            .foreach { batch =>
              val paths = batch.column("file_path")
              val pos   = batch.column("pos")
              var i     = 0
              while i < batch.numRows do
                val p = paths.getValue(i)
                val n = pos.getValue(i)
                if p == null || n == null then
                  throw ParquetException(s"Position delete file ${d.path} has a null row")
                val target = resolve(p.toString)
                if d.referencedDataFile.forall(_ == target) then
                  add(target, d, n.asInstanceOf[Long])
                i += 1
            }
        finally f.close()
      else
        throw ParquetException(
          s"Delete file ${d.path} is ${d
              .format}; Uni reads Parquet position deletes and Puffin deletion vectors"
        )
      end if
    }
    byFile
      .view
      .mapValues { b =>
        val a = b.result()
        java.util.Arrays.sort(a)
        distinct(a)
      }
      .toMap

  end positions

  private def distinct(a: Array[Long]): Array[Long] =
    if a.length < 2 then
      a
    else
      var n = 1
      var i = 1
      while i < a.length do
        if a(i) != a(n - 1) then
          a(n) = a(i)
          n += 1
        i += 1
      if n == a.length then
        a
      else
        java.util.Arrays.copyOf(a, n)

  // ---- Deletion vectors ----

  private val Magic = Array(0xd1, 0xd3, 0x39, 0x64).map(_.toByte)

  /**
    * The positions of a `deletion-vector-v1` blob: a big-endian length of the magic and the bitmap,
    * the magic `D1 D3 39 64`, a portable 64-bit roaring bitmap, and a big-endian CRC-32 of the
    * magic and the bitmap
    */
  def deletionVector(file: Array[Byte], offset: Long, size: Long, path: String): Array[Long] =
    def fail(msg: String): Nothing =
      throw ParquetException(s"Invalid deletion vector in ${path} at ${offset}: ${msg}")
    if offset < 0 || size < 12 || offset + size > file.length then
      fail(s"the blob of ${size} bytes is outside the file of ${file.length} bytes")
    val start = offset.toInt
    val len   = beInt(file, start)
    if len.toLong + 8 != size then
      fail(s"length ${len} doesn't match the blob size ${size}")
    if !(0 until 4).forall(i => file(start + 4 + i) == Magic(i)) then
      fail("bad magic")
    val crc = beInt(file, start + 4 + len)
    if CRC32.compute(file, start + 4, len) != crc then
      fail("checksum mismatch")
    Roaring64(file, start + 8, start + 4 + len, fail).read()

  private def beInt(b: Array[Byte], i: Int): Int =
    ((b(i) & 0xff) << 24) | ((b(i + 1) & 0xff) << 16) | ((b(i + 2) & 0xff) << 8) | (b(i + 3) & 0xff)

  /** Reads the portable serialization of a 64-bit roaring bitmap from `b(pos until end)` */
  private class Roaring64(b: Array[Byte], start: Int, end: Int, fail: String => Nothing):
    private var pos = start
    private val out = scala.collection.mutable.ArrayBuilder.ofLong()

    private def need(n: Int): Unit =
      if pos + n > end then
        fail("truncated bitmap")

    private def u16(): Int =
      need(2)
      val v = (b(pos) & 0xff) | ((b(pos + 1) & 0xff) << 8)
      pos += 2
      v

    private def i32(): Int =
      need(4)
      val v =
        (b(pos) & 0xff) |
          ((b(pos + 1) & 0xff) << 8) |
          ((b(pos + 2) & 0xff) << 16) |
          ((b(pos + 3) & 0xff) << 24)
      pos += 4
      v

    private def i64(): Long = (i32().toLong & 0xffffffffL) | (i32().toLong << 32)

    def read(): Array[Long] =
      val buckets = i64()
      if buckets < 0 || buckets > (end - pos) then
        fail(s"bad bucket count ${buckets}")
      var k = 0L
      while k < buckets do
        val high = i32().toLong & 0xffffffffL
        bitmap32(high << 32)
        k += 1
      out.result()

    /** A 32-bit roaring bitmap in the portable format, adding `base` to each value */
    private def bitmap32(base: Long): Unit =
      val SerialCookieNoRun = 12346
      val SerialCookie      = 12347
      val cookie            = i32()
      val (size, runs)      =
        if (cookie & 0xffff) == SerialCookie then
          val n    = (cookie >>> 16) + 1
          val bits = (n + 7) / 8
          need(bits)
          val r = java.util.Arrays.copyOfRange(b, pos, pos + bits)
          pos += bits
          (n, r)
        else if cookie == SerialCookieNoRun then
          (i32(), null)
        else
          fail(s"bad roaring cookie ${cookie}")
      if size < 0 || size > 65536 then
        fail(s"bad container count ${size}")
      val keys  = new Array[Int](size)
      val cards = new Array[Int](size)
      var i     = 0
      while i < size do
        keys(i) = u16()
        cards(i) = u16() + 1
        i += 1
      // Offsets to each container; the containers follow in order, so they are skipped
      if runs == null || size >= 4 then
        need(4 * size)
        pos += 4 * size
      i = 0
      while i < size do
        val hi = base | (keys(i).toLong << 16)
        if runs != null && (runs(i / 8) & (1 << (i % 8))) != 0 then
          val n = u16()
          var r = 0
          while r < n do
            val s   = u16()
            val len = u16()
            var v   = 0
            while v <= len do
              out += hi | (s + v).toLong
              v += 1
            r += 1
        else if cards(i) <= 4096 then
          var c = 0
          while c < cards(i) do
            out += hi | u16().toLong
            c += 1
        else
          need(8192)
          var w = 0
          while w < 1024 do
            val word = i64()
            var bit  = 0
            while bit < 64 do
              if (word & (1L << bit)) != 0 then
                out += hi | (w * 64 + bit).toLong
              bit += 1
            w += 1
        i += 1
      end while

    end bitmap32

  end Roaring64

end IcebergDeletes
