package wvlet.uni.parquet

import wvlet.uni.io.CRC32
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType

import scala.collection.immutable.ArraySeq

/**
  * Row deletes of an Iceberg table: position-delete files (Parquet rows of a data file path and a
  * row position, format version 2), deletion vectors (a roaring bitmap of row positions in a Puffin
  * file, format version 3), and equality-delete files (Parquet rows of key values).
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
      contentSize: Option[Long],
      /** For an equality delete, the field IDs of its key columns; empty for position deletes */
      equalityIds: IndexedSeq[Int] = IndexedSeq.empty,
      /** The partition spec and the partition values (see [[partitionKey]]) it was written with */
      specId: Int = 0,
      partition: Map[String, Any] = Map.empty
  ):
    def isDeletionVector: Boolean = format.equalsIgnoreCase("puffin")
    def isEquality: Boolean       = equalityIds.nonEmpty

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
    def applies(dataPath: String, del: DeleteFile): Boolean = dataFiles
      .get(dataPath)
      .exists(_ <= del.sequenceNumber)
    def positionsOf(dataPath: String): scala.collection.mutable.ArrayBuilder.ofLong = byFile
      .getOrElseUpdate(dataPath, scala.collection.mutable.ArrayBuilder.ofLong())
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
        if applies(target, d) then
          positionsOf(target) ++= deletionVector(b, offset, size, d.path)
      else if d.format.equalsIgnoreCase("parquet") then
        val f = Parquet.open(b)
        try f
            .readBatches(ParquetReadOptions.default.withColumns("file_path", "pos"))
            .foreach { batch =>
              val paths = batch.column("file_path")
              val pos   = batch.column("pos")
              // Rows are sorted by file_path, so the previous row's target is usually this one's
              var lastPath: Any                                        = null
              var target: scala.collection.mutable.ArrayBuilder.ofLong = null
              var i                                                    = 0
              while i < batch.numRows do
                val p = paths.getValue(i)
                val n = pos.getValue(i)
                if p == null || n == null then
                  throw ParquetException(s"Position delete file ${d.path} has a null row")
                if p != lastPath then
                  lastPath = p
                  val dataPath = resolve(p.toString)
                  target =
                    if d.referencedDataFile.forall(_ == dataPath) && applies(dataPath, d) then
                      positionsOf(dataPath)
                    else
                      null
                if target != null then
                  target += n.asInstanceOf[Long]
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

  // ---- Equality deletes ----

  /**
    * The key values of one equality-delete file: a row whose `columns` (the table's current names)
    * hold one of `keys` is deleted. Keys are built by [[KeyReader]].
    */
  final class EqualityDelete(val columns: IndexedSeq[String], keys: scala.collection.Set[Any]):
    def size: Int = keys.size

    /** Whether a key from a [[KeyReader]] over `columns` is deleted */
    def contains(key: Any): Boolean = keys.contains(key)

  /** Data file path -> the equality deletes that apply to it */
  type Equalities = Map[String, IndexedSeq[EqualityDelete]]

  /** What decides which equality deletes apply to a data file */
  case class DataFileScope(sequenceNumber: Long, specId: Int, partition: Map[String, Any])

  /** A manifest's partition record, with bytes as comparable values */
  def partitionKey(record: Map[String, Any]): Map[String, Any] = record.map {
    case (k, b: Array[Byte]) =>
      k -> ArraySeq.unsafeWrapArray(b)
    case kv =>
      kv
  }

  /**
    * The equality deletes of each data file. A delete applies to a data file whose sequence number
    * is strictly below its own and, unless the delete's spec is unpartitioned, that has the same
    * spec and partition values. `columnName` gives the current top-level column of a field ID.
    */
  def equalities(
      deletes: IndexedSeq[DeleteFile],
      bytes: Map[String, Array[Byte]],
      dataFiles: Map[String, DataFileScope],
      unpartitioned: Int => Boolean,
      columnName: (Int, String) => String
  ): Equalities =
    if deletes.isEmpty then
      Map.empty
    else
      val loaded           = deletes.map(d => d -> equalityDelete(d, bytes(d.path), columnName))
      val (global, scoped) = loaded.partition((d, _) => unpartitioned(d.specId))
      val byPartition      = scoped.groupBy((d, _) => (d.specId, d.partition))
      dataFiles.flatMap { (path, f) =>
        val applied = (global ++ byPartition.getOrElse((f.specId, f.partition), IndexedSeq.empty))
          .collect {
            case (d, e) if f.sequenceNumber < d.sequenceNumber =>
              e
          }
        Option.when(applied.nonEmpty)(path -> applied)
      }

  private def equalityDelete(
      d: DeleteFile,
      bytes: Array[Byte],
      columnName: (Int, String) => String
  ): EqualityDelete =
    val f = Parquet.open(bytes)
    try
      val fields = f.schema.fields
      // The file's column of each key: by field ID, or by the table's name for a file without IDs
      val fileColumns = d
        .equalityIds
        .map { id =>
          val name = columnName(id, d.path)
          fields
            .find(_.fieldId.contains(id))
            .orElse(fields.find(c => c.fieldId.isEmpty && c.name == name))
            .getOrElse(
              throw ParquetException(
                s"Equality delete file ${d.path} has no column with field ID ${id} (${name})"
              )
            )
            .name
        }
      val keys = scala.collection.mutable.HashSet.empty[Any]
      f.readBatches(ParquetReadOptions.default.withColumns(fileColumns*))
        .foreach { batch =>
          val reader = KeyReader(fileColumns.map(n => batch.column(n)))
          var i      = 0
          while i < batch.numRows do
            keys += reader.key(i)
            i += 1
        }
      EqualityDelete(d.equalityIds.map(columnName(_, d.path)), keys)
    finally
      f.close()

  end equalityDelete

  /** A null in a single-column key, which a set can't hold as null */
  private case object NullKey

  /**
    * A floating-point key by its bits, as Iceberg compares them (`Double.compare`): NaN equals NaN,
    * and -0.0 differs from 0.0
    */
  private case class FloatingKey(bits: Long)

  /**
    * Builds the keys of a batch's rows from the key column vectors: their stored values, so that a
    * value matches however the read decodes it and across type widening. Integers compare as longs,
    * floats as doubles, decimals by unscaled value, and strings, binary, and fixed values by bytes.
    * Null equals null.
    */
  final class KeyReader(vectors: IndexedSeq[ColumnVector]):
    private val columns  = vectors.toArray
    private val decimals = columns.map(
      _.field.logicalType.exists(_.isInstanceOf[LogicalType.Decimal])
    )

    columns.foreach {
      case _: BooleanColumn | _: IntColumn | _: LongColumn | _: FloatColumn | _: DoubleColumn |
          _: BinaryColumn =>
      case v =>
        throw ParquetException(
          s"Column ${v.name} is an equality delete key, which must be a primitive column"
        )
    }

    def key(row: Int): Any =
      if columns.length == 1 then
        val v = valueOf(0, row)
        if v == null then
          NullKey
        else
          v
      else
        val a = new Array[Any](columns.length)
        var j = 0
        while j < a.length do
          a(j) = valueOf(j, row)
          j += 1
        ArraySeq.unsafeWrapArray(a)

    private def valueOf(j: Int, i: Int): Any =
      val v = columns(j)
      if v.isNull(i) then
        null
      else
        v match
          case c: BooleanColumn =>
            c.values(i)
          case c: IntColumn =>
            if decimals(j) then
              BigInt(c.values(i))
            else
              c.values(i).toLong
          case c: LongColumn =>
            if decimals(j) then
              BigInt(c.values(i))
            else
              c.values(i)
          case c: FloatColumn =>
            FloatingKey(java.lang.Double.doubleToLongBits(c.values(i).toDouble))
          case c: DoubleColumn =>
            FloatingKey(java.lang.Double.doubleToLongBits(c.values(i)))
          case c: BinaryColumn =>
            if decimals(j) then
              BigInt(c.getBytes(i))
            else
              ArraySeq.unsafeWrapArray(c.getBytes(i))
          case _ =>
            null

  end KeyReader

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

  // ---- Writing deletion vectors ----

  /**
    * The `deletion-vector-v1` blob of sorted, distinct, non-negative positions: the inverse of
    * [[deletionVector]]
    */
  def deletionVectorBlob(positions: Array[Long]): Array[Byte] =
    val bitmap = LittleEndianOut()
    writeRoaring64(positions, bitmap)
    val body = bitmap.toByteArray
    val out  = new Array[Byte](12 + body.length)
    putBeInt(out, 0, 4 + body.length)
    System.arraycopy(Magic, 0, out, 4, 4)
    System.arraycopy(body, 0, out, 8, body.length)
    putBeInt(out, 8 + body.length, CRC32.compute(out, 4, 4 + body.length))
    out

  private def putBeInt(b: Array[Byte], i: Int, v: Int): Unit =
    b(i) = (v >>> 24).toByte
    b(i + 1) = (v >>> 16).toByte
    b(i + 2) = (v >>> 8).toByte
    b(i + 3) = v.toByte

  private final class LittleEndianOut:
    private val out = java.io.ByteArrayOutputStream()

    def u16(v: Int): Unit =
      out.write(v & 0xff)
      out.write((v >>> 8) & 0xff)

    def i32(v: Int): Unit =
      u16(v & 0xffff)
      u16(v >>> 16)

    def i64(v: Long): Unit =
      i32(v.toInt)
      i32((v >>> 32).toInt)

    def toByteArray: Array[Byte] = out.toByteArray

  /**
    * The portable serialization of a 64-bit roaring bitmap: the number of 32-bit bitmaps, then each
    * one's high 32 bits and its positions in the portable 32-bit format without run containers
    */
  private def writeRoaring64(positions: Array[Long], out: LittleEndianOut): Unit =
    // Starts of the runs of positions that share their high 32 bits
    val starts = scala.collection.mutable.ArrayBuilder.ofInt()
    var i      = 0
    while i < positions.length do
      if i == 0 || (positions(i) >>> 32) != (positions(i - 1) >>> 32) then
        starts += i
      i += 1
    val s = starts.result()
    out.i64(s.length.toLong)
    var k = 0
    while k < s.length do
      val from = s(k)
      val to   =
        if k + 1 < s.length then
          s(k + 1)
        else
          positions.length
      out.i32((positions(from) >>> 32).toInt)
      writeBitmap32(positions, from, to, out)
      k += 1

  private def writeBitmap32(
      positions: Array[Long],
      from: Int,
      to: Int,
      out: LittleEndianOut
  ): Unit =
    // Containers hold the values that share the high 16 of the low 32 bits
    val starts = scala.collection.mutable.ArrayBuilder.ofInt()
    var i      = from
    while i < to do
      if i == from || ((positions(i) >>> 16) & 0xffff) != ((positions(i - 1) >>> 16) & 0xffff) then
        starts += i
      i += 1
    val s                = starts.result()
    def end(c: Int): Int =
      if c + 1 < s.length then
        s(c + 1)
      else
        to
    def bytesOf(c: Int): Int =
      val card = end(c) - s(c)
      if card <= 4096 then
        2 * card
      else
        8192
    out.i32(12346) // SERIAL_COOKIE_NO_RUNCONTAINER
    out.i32(s.length)
    var c = 0
    while c < s.length do
      out.u16(((positions(s(c)) >>> 16) & 0xffff).toInt)
      out.u16(end(c) - s(c) - 1)
      c += 1
    var offset = 8 + 8 * s.length
    c = 0
    while c < s.length do
      out.i32(offset)
      offset += bytesOf(c)
      c += 1
    c = 0
    while c < s.length do
      val card = end(c) - s(c)
      if card <= 4096 then
        var j = s(c)
        while j < end(c) do
          out.u16((positions(j) & 0xffff).toInt)
          j += 1
      else
        val words = new Array[Long](1024)
        var j     = s(c)
        while j < end(c) do
          val low = (positions(j) & 0xffff).toInt
          words(low >>> 6) |= 1L << (low & 63)
          j += 1
        words.foreach(out.i64)
      c += 1
    end while

  end writeBitmap32

  /** A deletion vector to write: the data file it applies to and its sorted, distinct positions */
  case class NewVector(referencedDataFile: String, positions: Array[Long])

  /** Where a vector's blob went in a Puffin file */
  case class BlobRange(offset: Long, length: Long)

  private val PuffinMagic = "PFA1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

  /** The field ID of a row's position, which a deletion vector's blob names */
  private val RowPositionFieldId = 2147483645L

  /**
    * A Puffin file of `deletion-vector-v1` blobs, one per vector, and each blob's range: the magic,
    * the blobs, then a footer of the magic, its uncompressed JSON, the JSON's length, flags, and
    * the magic again
    */
  def puffinFile(
      vectors: IndexedSeq[NewVector],
      createdBy: String
  ): (Array[Byte], IndexedSeq[BlobRange]) =
    import wvlet.uni.json.JSON.*
    val out = java.io.ByteArrayOutputStream()
    out.write(PuffinMagic)
    val ranges = vectors.map { v =>
      val blob  = deletionVectorBlob(v.positions)
      val range = BlobRange(out.size().toLong, blob.length.toLong)
      out.write(blob)
      range
    }
    val footer = JSONObject(
      Seq(
        "blobs" ->
          JSONArray(
            vectors
              .zip(ranges)
              .map { (v, r) =>
                JSONObject(
                  Seq(
                    "type"            -> JSONString("deletion-vector-v1"),
                    "fields"          -> JSONArray(IndexedSeq(JSONLong(RowPositionFieldId))),
                    "snapshot-id"     -> JSONLong(-1L),
                    "sequence-number" -> JSONLong(-1L),
                    "offset"          -> JSONLong(r.offset),
                    "length"          -> JSONLong(r.length),
                    "properties"      ->
                      JSONObject(
                        Seq(
                          "referenced-data-file" -> JSONString(v.referencedDataFile),
                          "cardinality"          -> JSONString(v.positions.length.toString)
                        )
                      )
                  )
                )
              }
          ),
        "properties" -> JSONObject(Seq("created-by" -> JSONString(createdBy)))
      )
    ).toJSON.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    out.write(PuffinMagic)
    out.write(footer)
    val tail = LittleEndianOut()
    tail.i32(footer.length)
    tail.i32(0) // flags: the footer is uncompressed
    out.write(tail.toByteArray)
    out.write(PuffinMagic)
    (out.toByteArray, ranges)

  end puffinFile

  /** The sorted union of two sorted, distinct position arrays */
  def union(a: Array[Long], b: Array[Long]): Array[Long] =
    val all = java.util.Arrays.copyOf(a, a.length + b.length)
    System.arraycopy(b, 0, all, a.length, b.length)
    java.util.Arrays.sort(all)
    distinct(all)

end IcebergDeletes
