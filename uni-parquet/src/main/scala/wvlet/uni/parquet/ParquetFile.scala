package wvlet.uni.parquet

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.parquet.column.Assembly
import wvlet.uni.parquet.column.ColumnChunkReader
import wvlet.uni.parquet.column.ColumnData
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteReader
import wvlet.uni.parquet.thrift.CompactProtocol
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverContext

/**
  * An open Parquet file. The footer (schema and row group metadata) is read when the file is
  * opened; column data is read one row group at a time.
  *
  * Synchronous methods need a [[ByteSource]] (JVM, Scala Native, and Node.js). The `...Async`
  * methods work with any [[AsyncByteSource]], including HTTP in browsers.
  */
class ParquetFile private[parquet] (val source: AsyncByteSource, val metadata: FileMetaData)
    extends AutoCloseable:

  val schema: ParquetSchema = ParquetSchema.fromElements(metadata.schema)

  def numRows: Long                         = metadata.numRows
  def rowGroups: IndexedSeq[RowGroupMeta]   = metadata.rowGroups
  def numRowGroups: Int                     = metadata.rowGroups.size
  def keyValueMetadata: Map[String, String] = metadata.keyValueMetadata
  def createdBy: Option[String]             = metadata.createdBy

  override def close(): Unit = source.close()

  private def syncSource: ByteSource =
    source match
      case s: ByteSource =>
        s
      case _ =>
        throw UnsupportedOperationException(
          s"${source.name} can only be read asynchronously. Use the ...Async methods."
        )

  // ---- Synchronous reading ----

  /** Reads one row group into column vectors */
  def readRowGroup(
      index: Int,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): ColumnBatch =
    val plan = planRowGroup(index, options)
    val src  = syncSource
    val data = plan.ranges.map(r => r -> src.read(r.offset, r.length))
    decode(plan, data)

  /** Indices of the row groups to read: all of them, minus those the row group filter skips */
  def selectRowGroups(options: ParquetReadOptions = ParquetReadOptions.default): IndexedSeq[Int] =
    options.rowGroupFilter match
      case None =>
        0 until numRowGroups
      case Some(f) =>
        (0 until numRowGroups).filter(i => RowGroupFilter.mayMatch(f, schema, rowGroups(i)))

  /** Reads the selected row groups in order */
  def readBatches(options: ParquetReadOptions = ParquetReadOptions.default): Iterator[ColumnBatch] =
    selectRowGroups(options).iterator.map(readRowGroup(_, options))

  /** Reads every row into `A` through Weaver. Columns are matched to fields by name. */
  def readAs[A](options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Iterator[A] = readBatches(options).flatMap(b => ParquetFile.rows(b, weaver, options))

  /** Reads every row as a map from column name to value */
  def readRows(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Iterator[Map[String, Any]] = readBatches(options).flatMap(b =>
    (0 until b.numRows).iterator.map(b.row)
  )

  // ---- Asynchronous reading ----

  def readRowGroupAsync(
      index: Int,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[ColumnBatch] = Rx
    .single(planRowGroup(index, options))
    .flatMap { plan =>
      val reads: Seq[Rx[(FetchRange, Array[Byte])]] = plan
        .ranges
        .map(r => source.readAsync(r.offset, r.length).map(b => r -> b))
      ParquetFile.sequence(reads).map(data => decode(plan, data))
    }

  /** Emits each row group's batch in order */
  def readBatchesAsync(options: ParquetReadOptions = ParquetReadOptions.default): Rx[ColumnBatch] =
    Rx.concatRx(selectRowGroups(options).map(readRowGroupAsync(_, options)))

  /** Emits every row decoded into `A` */
  def readAsAsync[A](
      options: ParquetReadOptions = ParquetReadOptions.default
  )(using weaver: Weaver[A]): Rx[A] = Rx.concatRx(
    selectRowGroups(options).map(i =>
      readRowGroupAsync(i, options).flatMap(b =>
        Rx.fromSeq(ParquetFile.rows(b, weaver, options).toSeq)
      )
    )
  )

  /** All rows decoded into `A`, collected into one Seq */
  def readAllAsync[A](
      options: ParquetReadOptions = ParquetReadOptions.default
  )(using weaver: Weaver[A]): Rx[Seq[A]] = ParquetFile
    .sequence(selectRowGroups(options).map(readRowGroupAsync(_, options)))
    .map(_.flatMap(b => ParquetFile.rows(b, weaver, options)))

  // ---- Planning and decoding ----

  private[parquet] case class FetchRange(offset: Long, length: Int)

  private[parquet] case class ChunkPlan(
      field: ParquetField.Primitive,
      meta: ColumnChunkMeta,
      start: Long,
      length: Int
  )

  private[parquet] case class Plan(
      rowGroup: RowGroupMeta,
      fields: IndexedSeq[ParquetField],
      chunks: IndexedSeq[ChunkPlan],
      ranges: IndexedSeq[FetchRange]
  )

  private def selectedFields(options: ParquetReadOptions): IndexedSeq[ParquetField] =
    options.columns match
      case None =>
        schema.fields
      case Some(names) =>
        names
          .map { n =>
            schema
              .field(n)
              .getOrElse(
                throw IllegalArgumentException(
                  s"No column named ${n} in ${source.name}. Columns: ${schema
                      .fields
                      .map(_.name)
                      .mkString(", ")}"
                )
              )
          }
          .toIndexedSeq

  private def leaves(f: ParquetField): Seq[ParquetField.Primitive] =
    f match
      case p: ParquetField.Primitive =>
        Seq(p)
      case g: ParquetField.Group =>
        g.children.flatMap(leaves)

  private[parquet] def planRowGroup(index: Int, options: ParquetReadOptions): Plan =
    if index < 0 || index >= numRowGroups then
      throw IndexOutOfBoundsException(
        s"Row group ${index} is out of range (0 until ${numRowGroups})"
      )
    val rg     = rowGroups(index)
    val fields = selectedFields(options)
    if rg.columns.size != schema.columns.size then
      throw ParquetException.corrupt(
        s"row group ${index} has ${rg.columns.size} columns, but the schema has ${schema
            .columns
            .size}"
      )
    val chunks = fields
      .flatMap(leaves)
      .map { p =>
        val meta = rg.columns(p.columnIndex)
        if meta.filePath.isDefined then
          throw ParquetException.unsupported("column chunks stored in other files")
        if meta.isEncrypted then
          throw ParquetException.unsupported("encrypted Parquet columns")
        val start = meta.startOffset
        val len   = meta.totalCompressedSize
        if start < 0 || len < 0 || len > Int.MaxValue then
          throw ParquetException.corrupt(
            s"column ${p.pathString} has an invalid range (offset ${start}, ${len} bytes)"
          )
        ChunkPlan(p, meta, start, len.toInt)
      }
    Plan(rg, fields, chunks, mergeRanges(chunks, options.maxMergeGap))

  end planRowGroup

  private def mergeRanges(chunks: IndexedSeq[ChunkPlan], maxGap: Int): IndexedSeq[FetchRange] =
    val sorted                    = chunks.sortBy(_.start)
    val out                       = IndexedSeq.newBuilder[FetchRange]
    var cur: Option[(Long, Long)] = None
    sorted.foreach { c =>
      val end = c.start + c.length
      cur match
        case Some((s, e)) if c.start <= e + maxGap && math.max(e, end) - s <= Int.MaxValue =>
          cur = Some((s, math.max(e, end)))
        case Some((s, e)) =>
          out += FetchRange(s, (e - s).toInt)
          cur = Some((c.start, end))
        case None =>
          cur = Some((c.start, end))
    }
    cur.foreach { case (s, e) =>
      out += FetchRange(s, (e - s).toInt)
    }
    out.result()

  private def decode(plan: Plan, data: Seq[(FetchRange, Array[Byte])]): ColumnBatch =
    val numRows = plan.rowGroup.numRows
    if numRows < 0 || numRows > Int.MaxValue then
      throw ParquetException.corrupt(s"row group has ${numRows} rows")
    val columns: Map[Int, ColumnData] =
      plan
        .chunks
        .map { c =>
          val (range, bytes) = data
            .find { case (r, _) =>
              r.offset <= c.start && c.start + c.length <= r.offset + r.length
            }
            .getOrElse(
              throw IllegalStateException(s"No fetched range covers ${c.field.pathString}")
            )
          if bytes.length != range.length then
            throw ParquetException.corrupt(
              s"${source.name} ended before offset ${range.offset + range.length}"
            )
          val off = (c.start - range.offset).toInt
          c.field.columnIndex -> ColumnChunkReader.read(c.field, c.meta, bytes, off, c.length)
        }
        .toMap
    ColumnBatch(numRows.toInt, Assembly.build(plan.fields, columns, numRows.toInt))

end ParquetFile

object ParquetFile:
  private[parquet] final val Magic          = "PAR1".getBytes("US-ASCII")
  private[parquet] final val EncryptedMagic = "PARE".getBytes("US-ASCII")
  // Most footers fit in this, so opening a file takes one read
  private[parquet] final val TailReadSize = 64 * 1024

  private[parquet] def rows[A](
      batch: ColumnBatch,
      weaver: Weaver[A],
      options: ParquetReadOptions
  ): Iterator[A] =
    if batch.numRows == 0 then
      Iterator.empty
    else
      // Pack the whole batch once, then decode rows from one unpacker
      val packer    = MessagePack.newPacker()
      val rowPacker = RowPacker(options.binaryAsString)
      var i         = 0
      while i < batch.numRows do
        rowPacker.packRow(packer, batch, i)
        i += 1
      val unpacker = MessagePack.newUnpacker(packer.toByteArray)
      Iterator
        .range(0, batch.numRows)
        .map { _ =>
          val ctx = WeaverContext()
          weaver.unpack(unpacker, ctx)
          if ctx.hasError then
            throw ctx.getError.get
          ctx.getLastValue.asInstanceOf[A]
        }

  /** Runs the streams one after another and collects their values */
  private[parquet] def sequence[A](rxs: Seq[Rx[A]]): Rx[Seq[A]] =
    rxs.foldLeft(Rx.single(Vector.empty[A])) { (acc, rx) =>
      acc.flatMap(prev => rx.map(prev :+ _))
    }

  private def checkMagic(b: Array[Byte], off: Int, name: String): Unit =
    if java.util.Arrays.equals(java.util.Arrays.copyOfRange(b, off, off + 4), EncryptedMagic) then
      throw ParquetException.unsupported("encrypted Parquet files (encrypted footer)")
    if !java.util.Arrays.equals(java.util.Arrays.copyOfRange(b, off, off + 4), Magic) then
      throw ParquetException(s"${name} is not a Parquet file (no PAR1 magic at the end)")

  /** Where the footer is, given the file's last bytes */
  private[parquet] def footerRange(tail: Array[Byte], size: Long, name: String): (Long, Int) =
    if size < 12 || tail.length < 8 then
      throw ParquetException(s"${name} is too small to be a Parquet file (${size} bytes)")
    checkMagic(tail, tail.length - 4, name)
    val len = ByteReader.intLE(tail, tail.length - 8)
    if len <= 0 || len.toLong > size - 12 then
      throw ParquetException.corrupt(s"footer length ${len} does not fit in ${name}")
    (size - 8 - len, len)

  private[parquet] def parseFooter(bytes: Array[Byte], off: Int, len: Int): FileMetaData =
    val r = ByteReader(bytes, off, off + len)
    MetadataDecoder.fileMetaData(CompactProtocol.readStruct(r))

  private def footerFromTail(tail: Array[Byte], size: Long, name: String)(
      readMore: (Long, Int) => Array[Byte]
  ): FileMetaData =
    val (start, len) = footerRange(tail, size, name)
    val tailStart    = size - tail.length
    if start >= tailStart then
      parseFooter(tail, (start - tailStart).toInt, len)
    else
      parseFooter(readMore(start, len), 0, len)

  def open(source: ByteSource): ParquetFile =
    try
      val (tail, size) = source.readTail(TailReadSize)
      val meta         = footerFromTail(tail, size, source.name)(source.read)
      ParquetFile(source, meta)
    catch
      case e: Throwable =>
        source.close()
        throw e

  def openAsync(source: AsyncByteSource): Rx[ParquetFile] = source
    .readTailAsync(TailReadSize)
    .flatMap { case (tail, size) =>
      val (start, len) = footerRange(tail, size, source.name)
      val tailStart    = size - tail.length
      if start >= tailStart then
        Rx.single(ParquetFile(source, parseFooter(tail, (start - tailStart).toInt, len)))
      else
        source.readAsync(start, len).map(b => ParquetFile(source, parseFooter(b, 0, len)))
    }
    .tapOn { case scala.util.Failure(_) =>
      // No ParquetFile owns the source yet, so close it here, as open does
      source.close()
    }

end ParquetFile
