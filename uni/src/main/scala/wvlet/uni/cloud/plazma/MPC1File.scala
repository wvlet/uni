package wvlet.uni.cloud.plazma

import wvlet.uni.cloud.parquet.ByteSource
import wvlet.uni.cloud.parquet.ColumnVector.BinaryColumn
import wvlet.uni.cloud.parquet.ColumnVector.BooleanColumn
import wvlet.uni.cloud.parquet.ColumnVector.DoubleColumn
import wvlet.uni.cloud.parquet.ColumnVector.LongColumn
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.MapValue
import wvlet.uni.msgpack.spi.Value.NilValue
import wvlet.uni.msgpack.spi.Value.StringValue

import scala.collection.immutable.ListMap

/**
  * An opened MPC1 file. Opening reads the header and the column-name block; each column block is
  * read with its own range read when a column is first scanned, so projected reads of a remote file
  * fetch only the columns they need.
  *
  * {{{
  * val file = MPC1.open("part.mpc1")
  * file.columnNames            // Seq("time", "user", ...)
  * file.readRows(Seq("time", "user")).foreach(println)
  * }}}
  */
class MPC1File private (
    source: ByteSource,
    val header: MPC1Header,
    val columns: IndexedSeq[MPC1Column]
) extends AutoCloseable:

  private lazy val columnsByName: Map[String, MPC1Column] = columns.map(c => c.name -> c).toMap

  def name: String = source.name

  /** The size of the file in bytes */
  def size: Long = source.size

  def compression: MPC1Compression = header.compression

  /** The application metadata of the header; an empty array when the writer set none */
  def metadata: Value = header.metadata

  def columnNames: IndexedSeq[String] = columns.map(_.name)

  def column(name: String): Option[MPC1Column] = columnsByName.get(name)

  /** Reads and decompresses the raw block of a column: one MessagePack value per row */
  def readColumnBlock(column: MPC1Column): Array[Byte] =
    if column.size > Int.MaxValue then
      throw MPC1Exception(s"Column ${column.name} has ${column.size} bytes, over the 2 GiB limit")
    MPC1Format.decompress(compression, source.read(column.offset, column.size.toInt))

  /** The values of a column, one per row, with NilValue for rows without a value */
  def readColumn(name: String): Iterator[Value] = readColumn(columnOf(name))

  private def columnOf(name: String): MPC1Column =
    column(name) match
      case Some(c) =>
        c
      case None =>
        throw IllegalArgumentException(
          s"${source.name} has no column ${name}; it has ${columnNames.mkString(", ")}"
        )

  /**
    * The type of a column's values, found by reading only the format byte of each value. It names
    * the typed reader to use: [[readLongs]], [[readDoubles]], [[readBooleans]], [[readStrings]],
    * [[readBinaries]], any of them for NIL, or [[readColumn]] for ANY. To probe and decode with one
    * block read, call [[MPC1Decoder]] on [[readColumnBlock]].
    */
  def columnType(name: String): MPC1ColumnType = columnType(columnOf(name))

  def columnType(column: MPC1Column): MPC1ColumnType = MPC1Decoder.columnType(
    readColumnBlock(column)
  )

  /**
    * Reads an integer column as unboxed longs, with nil rows as nulls. Fails with [[MPC1Exception]]
    * at a row of another type; see [[MPC1Decoder]].
    */
  def readLongs(name: String): LongColumn = readLongs(columnOf(name))

  def readLongs(column: MPC1Column): LongColumn = MPC1Decoder.decodeLongs(
    column.name,
    readColumnBlock(column),
    columnIndex(column)
  )

  /** Reads a float or integer column as unboxed doubles, with nil rows as nulls */
  def readDoubles(name: String): DoubleColumn = readDoubles(columnOf(name))

  def readDoubles(column: MPC1Column): DoubleColumn = MPC1Decoder.decodeDoubles(
    column.name,
    readColumnBlock(column),
    columnIndex(column)
  )

  /** Reads a boolean column, with nil rows as nulls */
  def readBooleans(name: String): BooleanColumn = readBooleans(columnOf(name))

  def readBooleans(column: MPC1Column): BooleanColumn = MPC1Decoder.decodeBooleans(
    column.name,
    readColumnBlock(column),
    columnIndex(column)
  )

  /** Reads a string column as UTF-8 bytes (`getString(i)` returns a row), with nil rows as nulls */
  def readStrings(name: String): BinaryColumn = readStrings(columnOf(name))

  def readStrings(column: MPC1Column): BinaryColumn = MPC1Decoder.decodeStrings(
    column.name,
    readColumnBlock(column),
    columnIndex(column)
  )

  /** Reads a binary or string column as bytes, with nil rows as nulls */
  def readBinaries(name: String): BinaryColumn = readBinaries(columnOf(name))

  def readBinaries(column: MPC1Column): BinaryColumn = MPC1Decoder.decodeBinaries(
    column.name,
    readColumnBlock(column),
    columnIndex(column)
  )

  private def columnIndex(column: MPC1Column): Int = math.max(columns.indexOf(column), 0)

  def readColumn(column: MPC1Column): Iterator[Value] =
    val unpacker = MessagePack.newUnpacker(readColumnBlock(column))
    new Iterator[Value]:
      def hasNext: Boolean = unpacker.hasNext
      def next(): Value    =
        if !unpacker.hasNext then
          throw java.util.NoSuchElementException(s"no more values in column ${column.name}")
        unpacker.unpackValue

  /**
    * The number of rows, counted by scanning the smallest column block. A file without columns has
    * no rows.
    */
  def countRows(): Long =
    columns.minByOption(_.size) match
      case None =>
        0L
      case Some(c) =>
        val unpacker = MessagePack.newUnpacker(readColumnBlock(c))
        var n        = 0L
        while unpacker.hasNext do
          unpacker.skipValue
          n += 1
        n

  /**
    * Reads rows as maps from column name to value, in file column order. Columns without a value in
    * a row (nil) are left out of that row's map.
    *
    * @param columnNames
    *   the columns to read; all columns when empty. Names the file does not have are ignored, since
    *   such columns have no values.
    */
  def readRows(columnNames: Seq[String] = Seq.empty): Iterator[MapValue] =
    val selected =
      if columnNames.isEmpty then
        columns
      else
        val wanted = columnNames.toSet
        columns.filter(c => wanted.contains(c.name))
    if selected.isEmpty then
      Iterator.empty
    else
      val keys    = selected.map(c => StringValue(c.name))
      val readers = selected.map(readColumn)
      new Iterator[MapValue]:
        def hasNext: Boolean = readers.forall(_.hasNext)
        def next(): MapValue =
          if !hasNext then
            throw java.util.NoSuchElementException("no more rows")
          val b = ListMap.newBuilder[Value, Value]
          var i = 0
          while i < readers.length do
            val v = readers(i).next()
            if v != NilValue then
              b += keys(i) -> v
            i += 1
          MapValue(b.result())

  end readRows

  override def close(): Unit = source.close()

  override def toString: String =
    s"MPC1File(${source.name}, ${columns.size} columns, ${compression.code})"

end MPC1File

object MPC1File:
  def open(source: ByteSource): MPC1File =
    try
      read(source)
    catch
      case e: Throwable =>
        source.close()
        throw e

  private def read(source: ByteSource): MPC1File =
    val fileSize = source.size
    val first    = source.read(0, math.min(fileSize, MPC1Format.ExpectedMaxHeaderSize).toInt)
    val total    = MPC1Format.headerSize(first)
    if total > fileSize then
      throw MPC1Exception.corrupt(s"header needs ${total} bytes, but the file has ${fileSize}")
    val headerBytes =
      if total <= first.length then
        first
      else
        first ++ source.read(first.length, total - first.length)
    val header = MPC1Format.parseHeader(headerBytes, fileSize)

    val nameBlockSize = header.blockSizes.head
    if total.toLong + nameBlockSize > Int.MaxValue then
      throw MPC1Exception.corrupt(s"column-name block has ${nameBlockSize} bytes")
    val nameEnd   = total + nameBlockSize.toInt
    val nameBlock =
      if nameEnd <= headerBytes.length then
        java.util.Arrays.copyOfRange(headerBytes, total, nameEnd)
      else if total < headerBytes.length then
        java.util.Arrays.copyOfRange(headerBytes, total, headerBytes.length) ++
          source.read(headerBytes.length, nameEnd - headerBytes.length)
      else
        source.read(total, nameBlockSize.toInt)
    val names =
      if nameBlockSize == 0 then
        IndexedSeq.empty
      else
        // Some readers gunzip the name block whatever the codec, so accept a gzip name block in
        // an uncompressed file. A name block of strings never starts with 0x1f.
        val codec =
          if nameBlock.length >= 2 && (nameBlock(0) & 0xff) == 0x1f && (nameBlock(1) & 0xff) == 0x8b
          then
            MPC1Compression.GZIP
          else
            header.compression
        MPC1Format.parseNames(MPC1Format.decompress(codec, nameBlock))

    // Some old files have no column names but several blocks; they are read as files without
    // columns
    val columns =
      if names.isEmpty then
        IndexedSeq.empty
      else
        val sizes = header.blockSizes.tail
        if sizes.length != names.length then
          throw MPC1Exception.corrupt(s"${names.length} column names, but ${sizes.length} blocks")
        var offset = nameEnd.toLong
        names
          .zip(sizes)
          .map { (name, size) =>
            val c = MPC1Column(name, offset, size)
            offset += size
            c
          }
    new MPC1File(source, header, columns)

  end read

end MPC1File
