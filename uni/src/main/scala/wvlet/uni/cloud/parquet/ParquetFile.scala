package wvlet.uni.cloud.parquet

import wvlet.uni.io.codec.Bytes
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.cloud.parquet.column.Assembly
import wvlet.uni.cloud.parquet.column.ChunkInput
import wvlet.uni.cloud.parquet.column.ColumnChunkCursor
import wvlet.uni.cloud.parquet.column.FetchBuffers
import wvlet.uni.cloud.parquet.column.FilterStep
import wvlet.uni.cloud.parquet.column.FilteredBatches
import wvlet.uni.cloud.parquet.column.RowGroupBatches
import wvlet.uni.cloud.parquet.column.SelectiveCursor
import wvlet.uni.cloud.parquet.column.ValueBuffer
import wvlet.uni.cloud.parquet.format.*
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol
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

  /**
    * Reads one row group into column vectors, as one batch. A row group with more than 2 GiB of
    * values in a column does not fit in one batch and fails; [[readBatches]] reads it in several.
    * The batch size option does not apply here.
    */
  def readRowGroup(
      index: Int,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): ColumnBatch =
    val fl   = filtering(options)
    val opts = readOptions(options, fl)
    val plan = planRowGroup(index, opts)
    wholeRowGroup(index, fetchedBatches(plan, opts, whole = true, fl))

  /** Indices of the row groups to read: all of them, minus those the row group filter skips */
  def selectRowGroups(options: ParquetReadOptions = ParquetReadOptions.default): IndexedSeq[Int] =
    options.pruneFilter match
      case None =>
        0 until numRowGroups
      case Some(f) =>
        (0 until numRowGroups).filter(i =>
          ParquetFilter.mayMatch(f, schema, rowGroups(i), options.binaryAsString)
        )

  /**
    * Reads the selected row groups in order, one batch per row group. A row group is split into
    * several batches when [[ParquetReadOptions.withBatchSize]] asks for fewer rows, or when a
    * column's values in it pass 1 GiB.
    */
  def readBatches(options: ParquetReadOptions = ParquetReadOptions.default): Iterator[ColumnBatch] =
    selectRowGroups(options).iterator.flatMap(i => rowGroupBatches(i, options))

  /** Reads every row into `A` through Weaver. Columns are matched to fields by name. */
  def readAs[A](options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Iterator[A] = readBatches(options).flatMap(b => ParquetFile.rows(b, weaver, options))

  /**
    * Reads every row as a map from column name to value, in schema column order. Values are those
    * of [[ColumnVector.getValue]]: logical types applied (for example Instant, LocalDate,
    * BigDecimal), lists as Seq, maps as Map, and groups as Map[String, Any].
    */
  def readRows(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Iterator[Map[String, Any]] = readBatches(options).flatMap(_.rows)

  /** Reads every row as a JSON object. See [[ParquetJson]] for how values map to JSON. */
  def readJson(options: ParquetReadOptions = ParquetReadOptions.default): Iterator[JSONObject] =
    readRows(options).map(ParquetJson.fromRow)

  /**
    * Statistics of each leaf column, keyed by its dotted path, summed over every row group. They
    * come from the footer, so no column data is read.
    */
  lazy val columnStatistics: Map[String, ColumnStatistics] = ColumnStatistics.of(schema, rowGroups)

  // ---- Asynchronous reading ----

  /** Reads one row group as one batch, as [[readRowGroup]] does */
  def readRowGroupAsync(
      index: Int,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[ColumnBatch] = Rx
    .single {
      val fl = filtering(options)
      (fl, readOptions(options, fl))
    }
    .flatMap { (fl, opts) =>
      val plan = planRowGroup(index, opts)
      fetchAsync(plan).map(in => wholeRowGroup(index, batches(plan, in, opts, whole = true, fl)))
    }

  /** The batches of one row group, as [[readBatches]] splits them */
  private[parquet] def rowGroupBatches(
      index: Int,
      options: ParquetReadOptions
  ): Iterator[ColumnBatch] =
    val fl   = filtering(options)
    val opts = readOptions(options, fl)
    val plan = planRowGroup(index, opts)
    filtered(fetchedBatches(plan, opts, whole = false, fl), fl)

  /** The batches of one row group, as [[readBatches]] splits them */
  private[parquet] def rowGroupBatchesAsync(
      index: Int,
      options: ParquetReadOptions
  ): Rx[Iterator[ColumnBatch]] = Rx
    .single {
      val fl = filtering(options)
      (fl, readOptions(options, fl))
    }
    .flatMap { (fl, opts) =>
      val plan = planRowGroup(index, opts)
      fetchAsync(plan).map(in => filtered(batches(plan, in, opts, whole = false, fl), fl))
    }

  /**
    * Emits the batches of the selected row groups in order, as [[readBatches]] does. Unlike
    * [[readBatches]], a row group's batches are all decoded before the first is emitted.
    */
  def readBatchesAsync(options: ParquetReadOptions = ParquetReadOptions.default): Rx[ColumnBatch] =
    checked(options)(
      Rx.concatRx(
        selectRowGroups(options).map(i =>
          rowGroupBatchesAsync(i, options).flatMap(it => Rx.fromSeq(it.toSeq))
        )
      )
    )

  /** Emits every row decoded into `A` */
  def readAsAsync[A](
      options: ParquetReadOptions = ParquetReadOptions.default
  )(using weaver: Weaver[A]): Rx[A] = readBatchesAsync(options).flatMap(b =>
    Rx.fromSeq(ParquetFile.rows(b, weaver, options).toSeq)
  )

  /** Emits every row as a map from column name to value, as [[readRows]] does */
  def readRowsAsync(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[Map[String, Any]] = readBatchesAsync(options).flatMap(b => Rx.fromSeq(b.rows.toSeq))

  /** All rows as maps from column name to value, collected into one Seq */
  def readAllRowsAsync(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[Seq[Map[String, Any]]] =
    checked(options)(
      ParquetFile
        .sequence(selectRowGroups(options).map(rowGroupBatchesAsync(_, options)))
        .map(_.flatMap(_.flatMap(_.rows)))
    )

  /** Emits every row as a JSON object, as [[readJson]] does */
  def readJsonAsync(options: ParquetReadOptions = ParquetReadOptions.default): Rx[JSONObject] =
    readRowsAsync(options).map(ParquetJson.fromRow)

  /** All rows decoded into `A`, collected into one Seq */
  def readAllAsync[A](
      options: ParquetReadOptions = ParquetReadOptions.default
  )(using weaver: Weaver[A]): Rx[Seq[A]] =
    checked(options)(
      ParquetFile
        .sequence(selectRowGroups(options).map(rowGroupBatchesAsync(_, options)))
        .map(_.flatMap(_.flatMap(b => ParquetFile.rows(b, weaver, options))))
    )

  // ---- Row filtering ----

  /**
    * A row filter and the columns to return. Columns the filter needs but the read doesn't select
    * are read too, then dropped.
    *
    * `steps` split the filter for filter-first decoding: each step names the top-level columns it
    * adds to those of the steps before it, and tests the conjuncts of the filter that need no other
    * columns.
    */
  private case class Filtering(
      rowFilter: ParquetFilter.RowFilter,
      extra: Seq[String],
      output: Option[Seq[String]],
      steps: IndexedSeq[(Seq[String], ParquetFilter.RowFilter)]
  ):
    def apply(b: ColumnBatch): ColumnBatch =
      val kept = rowFilter(b)
      output match
        case Some(names) if extra.nonEmpty =>
          kept.select(names*)
        case _ =>
          kept

  /** The row filter of a read, which fails here when the filter can't be evaluated exactly */
  private def filtering(options: ParquetReadOptions): Option[Filtering] = options
    .filter
    .map { f =>
      val rf    = ParquetFilter.RowFilter(f, schema, options.binaryAsString)
      val extra =
        options.columns.fold(Seq.empty[String])(cols => rf.topColumns.filterNot(cols.contains))
      Filtering(rf, extra, options.columns, filterSteps(f, options.binaryAsString))
    }

  /**
    * The steps of a filter: its top-level AND conjuncts in order, a conjunct joining the step
    * before it when that step's columns already cover it
    */
  private def filterSteps(
      f: ParquetFilter,
      binaryAsString: Boolean
  ): IndexedSeq[(Seq[String], ParquetFilter.RowFilter)] =
    def conjuncts(f: ParquetFilter): Seq[ParquetFilter] =
      f match
        case ParquetFilter.And(a, b) =>
          conjuncts(a) ++ conjuncts(b)
        case other =>
          Seq(other)
    val steps   = IndexedSeq.newBuilder[(Seq[String], Seq[ParquetFilter])]
    var current = Option.empty[(Seq[String], Seq[ParquetFilter])]
    var decoded = Set.empty[String]
    conjuncts(f).foreach { c =>
      val cols  = ParquetFilter.RowFilter(c, schema, binaryAsString).topColumns
      val added = cols.filterNot(decoded.contains)
      current match
        case Some((names, cs)) if added.isEmpty =>
          current = Some((names, cs :+ c))
        case _ =>
          current.foreach(steps += _)
          current = Some((added, Seq(c)))
      decoded ++= added
    }
    current.foreach(steps += _)
    steps
      .result()
      .map { (names, cs) =>
        names ->
          ParquetFilter.RowFilter(cs.reduceLeft(ParquetFilter.And(_, _)), schema, binaryAsString)
      }

  end filterSteps

  private def readOptions(options: ParquetReadOptions, fl: Option[Filtering]): ParquetReadOptions =
    fl match
      case Some(f) if f.extra.nonEmpty =>
        options.copy(columns = options.columns.map(_ ++ f.extra))
      case _ =>
        options

  /** Drops the batches a filter left without rows */
  private def filtered(it: Iterator[ColumnBatch], fl: Option[Filtering]): Iterator[ColumnBatch] =
    if fl.isEmpty then
      it
    else
      it.filter(_.numRows > 0)

  /** Checks the filter when the stream starts, even when no row group is selected */
  private def checked[A](options: ParquetReadOptions)(body: => Rx[A]): Rx[A] =
    if options.filter.isEmpty then
      body
    else
      Rx.single(filtering(options)).flatMap(_ => body)

  // ---- Planning and decoding ----

  private[parquet] case class FetchRange(offset: Long, length: Int)

  /** A column chunk to read; a windowed chunk is read in windows instead of one fetched range */
  private[parquet] case class ChunkPlan(
      field: ParquetField.Primitive,
      meta: ColumnChunkMeta,
      start: Long,
      length: Long,
      windowed: Boolean
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
        if start < 0 || len < 0 then
          throw ParquetException.corrupt(
            s"column ${p.pathString} has an invalid range (offset ${start}, ${len} bytes)"
          )
        ChunkPlan(p, meta, start, len, windowed = len > chunkWindowSize)
      }
    Plan(rg, fields, chunks, mergeRanges(chunks.filterNot(_.windowed), options.maxMergeGap))

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

  // Limits for cutting batches and reading large chunks. Variables so tests can make them small.

  /** A batch ends at the next complete row once a column's values pass this many bytes */
  private[parquet] var batchSoftLimit: Long = 1L << 30

  /** A column never decodes a page that would push its values past this many bytes */
  private[parquet] var batchHardLimit: Long = ValueBuffer.MaxArrayLength.toLong

  /** Chunks larger than this are read in windows of this size, not in one range */
  private[parquet] var chunkWindowSize: Int = 256 * 1024 * 1024

  /**
    * Whether filtered reads decode filter columns first and the others only for matching rows.
    * False decodes every column for every row, then filters: kept for tests and benchmarks.
    */
  private[parquet] var lateMaterialization: Boolean = true

  private def chunkInput(c: ChunkPlan, data: Seq[(FetchRange, Array[Byte])]): ChunkInput =
    val (range, bytes) = data
      .find { case (r, _) =>
        r.offset <= c.start && c.start + c.length <= r.offset + r.length
      }
      .getOrElse(throw IllegalStateException(s"No fetched range covers ${c.field.pathString}"))
    if bytes.length < range.length then
      throw ParquetException.corrupt(
        s"${source.name} ended before offset ${range.offset + range.length}"
      )
    ChunkInput.OfArray(bytes, (c.start - range.offset).toInt, c.length)

  /**
    * The batches of a row group, read synchronously. When the source reads into a caller's buffer,
    * the merged ranges go into [[FetchBuffers]], which get them back once the batches end; a caller
    * that stops early leaves them to the GC.
    */
  private def fetchedBatches(
      plan: Plan,
      opts: ParquetReadOptions,
      whole: Boolean,
      fl: Option[Filtering]
  ): Iterator[ColumnBatch] =
    val borrowed = IndexedSeq.newBuilder[Array[Byte]]
    val it       = batches(plan, fetch(plan, borrowed), opts, whole, fl)
    val bufs     = borrowed.result()
    if bufs.isEmpty then
      it
    else
      new Iterator[ColumnBatch]:
        private var released = false
        def hasNext: Boolean =
          val more = it.hasNext
          if !more && !released then
            released = true
            bufs.foreach(FetchBuffers.release)
          more

        def next(): ColumnBatch = it.next()

  /**
    * Reads the merged ranges now; windowed chunks read their windows while they are decoded. Ranges
    * read into pooled buffers are added to `borrowed`.
    */
  private def fetch(
      plan: Plan,
      borrowed: scala.collection.mutable.Builder[Array[Byte], ?]
  ): Map[Int, ChunkInput] =
    val src  = syncSource
    val data = plan
      .ranges
      .map { r =>
        if src.readsInto && r.length <= FetchBuffers.MaxBufferBytes then
          val buf = FetchBuffers.borrow(r.length)
          src.readInto(r.offset, r.length, buf)
          borrowed += buf
          r -> buf
        else
          r -> src.read(r.offset, r.length)
      }
    plan
      .chunks
      .map { c =>
        val in =
          if c.windowed then
            ChunkInput.Windowed(src.read, c.start, c.length, chunkWindowSize)
          else
            chunkInput(c, data)
        c.field.columnIndex -> in
      }
      .toMap

  private def windowsOf(c: ChunkPlan): IndexedSeq[FetchRange] =
    (0L until c.length by chunkWindowSize.toLong).map(o =>
      FetchRange(c.start + o, math.min(chunkWindowSize.toLong, c.length - o).toInt)
    )

  /** Reads the merged ranges and every window of the windowed chunks */
  private def fetchAsync(plan: Plan): Rx[Map[Int, ChunkInput]] =
    val ranges = plan.ranges ++ plan.chunks.filter(_.windowed).flatMap(windowsOf)
    ParquetFile
      .sequence(ranges.map(r => source.readAsync(r.offset, r.length).map(b => r -> b)))
      .map { data =>
        data.foreach { case (r, b) =>
          if b.length != r.length then
            throw ParquetException.corrupt(
              s"${source.name} ended before offset ${r.offset + r.length}"
            )
        }
        val byOffset =
          data
            .map { case (r, b) =>
              r.offset -> b
            }
            .toMap
        plan
          .chunks
          .map { c =>
            val in =
              if c.windowed then
                ChunkInput.OfSegments(
                  windowsOf(c).map(r => byOffset(r.offset)),
                  chunkWindowSize,
                  c.length
                )
              else
                chunkInput(c, data)
            c.field.columnIndex -> in
          }
          .toMap
      }

  end fetchAsync

  /**
    * The batches of a row group, each filtered by `fl` when it is given. A filtered batch may have
    * no rows.
    */
  private def batches(
      plan: Plan,
      inputs: Map[Int, ChunkInput],
      options: ParquetReadOptions,
      whole: Boolean,
      fl: Option[Filtering]
  ): Iterator[ColumnBatch] =
    // A whole row group may use all the space an array has
    val soft =
      if whole then
        batchHardLimit
      else
        batchSoftLimit
    // Top-level INT32 decimals that Assembly widens to Long are decoded as Long from the start
    val asLong: Set[Int] =
      if !options.int32DecimalAsLong then
        Set.empty
      else
        plan
          .fields
          .collect {
            case p: ParquetField.Primitive if !p.isRepeated && Assembly.isInt32Decimal(p) =>
              p.columnIndex
          }
          .toSet
    def cursor(c: ChunkPlan) = ColumnChunkCursor(
      c.field,
      c.meta,
      inputs(c.field.columnIndex),
      soft,
      batchHardLimit,
      int32AsLong = asLong.contains(c.field.columnIndex)
    )
    val batchSize =
      if whole then
        ValueBuffer.MaxArrayLength
      else
        options.batchSize.getOrElse(ValueBuffer.MaxArrayLength)
    fl match
      case Some(f) if lateMaterialization =>
        filterFirst(plan, inputs, options, f, asLong, batchSize, cursor)
      case _ =>
        val it = RowGroupBatches(
          plan.fields,
          plan.chunks.map(cursor),
          plan.rowGroup.numRows,
          batchSize,
          options.binaryAsString,
          options.int32DecimalAsLong
        )
        fl.fold(it)(f => it.map(f(_)))

  end batches

  /**
    * Filter-first batches: the first step's columns, and those a [[SelectiveCursor]] can't read
    * (lists, maps, and chunks too large to be sure a batch of them fits), are decoded for every
    * row; the other columns only for the rows still selected
    */
  private def filterFirst(
      plan: Plan,
      inputs: Map[Int, ChunkInput],
      options: ParquetReadOptions,
      fl: Filtering,
      asLong: Set[Int],
      batchSize: Int,
      cursor: ChunkPlan => ColumnChunkCursor
  ): Iterator[ColumnBatch] =
    val byName                               = plan.fields.map(f => f.name -> f).toMap
    def selectable(f: ParquetField): Boolean = leaves(f).forall(p =>
      p.maxRepetitionLevel == 0 &&
        plan.rowGroup.columns(p.columnIndex).totalUncompressedSize <= batchSoftLimit
    )
    val firstNames = fl.steps.head._1.toSet
    val eager      = plan.fields.filter(f => firstNames.contains(f.name) || !selectable(f))
    val eagerNames = eager.map(_.name).toSet
    val steps      = fl
      .steps
      .map { (names, rf) =>
        FilterStep(
          names.map(byName).filterNot(f => eagerNames.contains(f.name)).toIndexedSeq,
          rf.matches
        )
      }
    val stepNames = fl.steps.flatMap(_._1).toSet
    val payload   = plan
      .fields
      .filterNot(f => eagerNames.contains(f.name) || stepNames.contains(f.name))
    val eagerLeaves = eager.flatMap(leaves).map(_.columnIndex).toSet
    val selective   =
      plan
        .chunks
        .filterNot(c => eagerLeaves.contains(c.field.columnIndex))
        .map { c =>
          c.field.columnIndex ->
            SelectiveCursor(
              c.field,
              c.meta,
              inputs(c.field.columnIndex),
              int32AsLong = asLong.contains(c.field.columnIndex)
            )
        }
        .toMap
    val output =
      fl.output match
        case Some(names) =>
          names.map(byName).toIndexedSeq
        case None =>
          plan.fields
    FilteredBatches(
      output,
      eager,
      plan.chunks.filter(c => eagerLeaves.contains(c.field.columnIndex)).map(cursor),
      steps,
      payload,
      selective,
      plan.rowGroup.numRows,
      batchSize,
      options.binaryAsString,
      options.int32DecimalAsLong
    )

  end filterFirst

  private def wholeRowGroup(index: Int, it: Iterator[ColumnBatch]): ColumnBatch =
    val numRows = rowGroups(index).numRows
    if numRows > ValueBuffer.MaxArrayLength then
      throw ParquetException.unsupported(
        s"reading row group ${index} (${numRows} rows) as one batch. Use readBatches, which splits it"
      )
    val b = it.next()
    if it.hasNext then
      throw ParquetException.unsupported(
        s"reading row group ${index} as one batch: a column has more than 2 GiB of values. Use readBatches, which splits it"
      )
    b

end ParquetFile

object ParquetFile:
  private[parquet] final val Magic          = "PAR1".getBytes("US-ASCII")
  private[parquet] final val EncryptedMagic = "PARE".getBytes("US-ASCII")
  // Most footers fit in this, so opening a file takes one read
  private[parquet] final val TailReadSize = 64 * 1024

  private[cloud] def rows[A](
      batch: ColumnBatch,
      weaver: Weaver[A],
      options: ParquetReadOptions
  ): Iterator[A] =
    if batch.numRows == 0 then
      Iterator.empty
    else if batch.columns.exists(hasVariant) then
      // A variant's type varies by row, so its rows go through MessagePack
      val packer = RowPacker(options.binaryAsString)
      Iterator
        .range(0, batch.numRows)
        .map { i =>
          val p = MessagePack.newPacker()
          packer.packRow(p, batch, i)
          val ctx = WeaverContext()
          weaver.unpack(MessagePack.newUnpacker(p.toByteArray), ctx)
          if ctx.hasError then
            throw ctx.getError.get
          ctx.getLastValue.asInstanceOf[A]
        }
    else
      // Weaver reads the vectors directly, with no MessagePack in between
      val unpacker = VectorUnpacker(batch, options.binaryAsString)
      Iterator
        .range(0, batch.numRows)
        .map { i =>
          unpacker.startRow(i)
          val ctx = WeaverContext()
          weaver.unpack(unpacker, ctx)
          if ctx.hasError then
            throw ctx.getError.get
          ctx.getLastValue.asInstanceOf[A]
        }

  private def hasVariant(v: ColumnVector): Boolean =
    v match
      case s: ColumnVector.StructColumn =>
        s.isVariant || s.children.exists(hasVariant)
      case l: ColumnVector.ListColumn =>
        hasVariant(l.elements)
      case m: ColumnVector.MapColumn =>
        hasVariant(m.keys) || hasVariant(m.values)
      case _ =>
        false

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
    val len = Bytes.intLE(tail, tail.length - 8)
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

  /**
    * Opens a file with a footer parsed earlier, such as `file.metadata` of a previous open. This
    * reads nothing and skips parsing the footer, so a caller that opens the same file many times
    * can keep the footer in a cache.
    *
    * The footer must come from the same file: one rewritten since then reads wrong data or fails.
    * Callers that cache footers decide when an entry is stale, for example by keeping the `size`
    * and `lastModified` of [[wvlet.uni.io.FileSystem.info]] with it. The returned file owns the
    * source and closes it on `close()`. Synchronous reads need a [[ByteSource]].
    */
  def open(source: AsyncByteSource, metadata: FileMetaData): ParquetFile =
    try
      ParquetFile(source, metadata)
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
