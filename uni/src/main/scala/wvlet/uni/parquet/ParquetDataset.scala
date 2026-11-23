package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.io.ListOptions
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.column.Assembly
import wvlet.uni.parquet.column.ValueBuffer
import wvlet.uni.parquet.format.*
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxParallel
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.collection.immutable.VectorMap

/**
  * A file of a [[ParquetDataset]]
  *
  * @param path
  *   a local path, or an object URI such as `s3://bucket/events/dt=2026-11-20/part-0.parquet`
  * @param size
  *   the size in bytes, or -1 for an object given in a list of files, which is not listed
  * @param partitions
  *   the values of the `key=value` folders above the file, typed as the dataset's partition
  *   columns; null for `__HIVE_DEFAULT_PARTITION__`
  * @param stats
  *   whole-file statistics a table format records for the file (Iceberg manifests), used to skip
  *   the file before it is opened; None when there are none
  */
case class DatasetFile(
    path: String,
    size: Long,
    partitions: VectorMap[String, Any],
    stats: Option[DatasetFile.Stats] = None
)

object DatasetFile:
  /**
    * Statistics of a whole data file
    *
    * @param numRows
    *   the number of rows in the file
    * @param columns
    *   the statistics of top-level primitive columns by name, with min and max in the PLAIN
    *   encoding of the dataset's column type. A column that isn't listed has unknown statistics
    */
  case class Stats(numRows: Long, columns: Map[String, ColumnStats])

  /**
    * A column's bounds over a whole file. `min` and `max` may be looser than the actual values (as
    * Iceberg truncates string bounds), but never inside them
    */
  case class ColumnStats(
      min: Option[Array[Byte]],
      max: Option[Array[Byte]],
      nullCount: Option[Long]
  )

/**
  * Row groups of a dataset read on demand. Each call reads the next row group that has rows to
  * read; an empty Seq means its rows were all filtered out. None means the read is done.
  */
private[parquet] trait DatasetBatchReader:
  def nextRowGroup(): Rx[Option[Seq[ColumnBatch]]]

  /** Closes the open file; later calls return None */
  def close(): Unit

/**
  * The Parquet files under a local directory or an object store prefix, read as one table. Open it
  * with [[Parquet.dataset]].
  *
  * Folders named `key=value` become partition columns, appended after the files' columns. A
  * [[ParquetFilter]] on a partition column skips whole files before they are opened; on other
  * columns it skips row groups by statistics, as it does for one file. With
  * [[ParquetReadOptions.withFilter]], the remaining rows are then filtered exactly, after missing
  * columns become nulls and narrow columns are widened. Columns are matched by name across files: a
  * file without a selected column reads it as nulls, and a column whose type differs between files
  * is an error.
  *
  * Synchronous reads open one file at a time and close it before the next. Footers are fetched
  * several at a time (see [[ParquetDatasetOptions.withPrefetch]]): on the JVM for every read, and
  * on Scala.js and Scala Native for the `...Async` reads. Rows come in file order (files are sorted
  * by path), then row group order.
  *
  * Close the dataset when done: it closes the object store if the dataset created it, and any file
  * an unfinished read left open. A store passed to [[Parquet.dataset]] is left open.
  */
class ParquetDataset private[parquet] (
    /** The directory or prefix the files were listed from, ending with `/` */
    val root: String,
    val files: IndexedSeq[DatasetFile],
    /** Partition columns, in folder order */
    val partitionColumns: IndexedSeq[ParquetField.Primitive],
    store: Option[ObjectStore],
    ownsStore: Boolean,
    prefetch: Int,
    /** The columns `_common_metadata` records, the schema of a dataset without files */
    metadataFields: IndexedSeq[ParquetField] = IndexedSeq.empty,
    /**
      * A table's columns, for a table format that records its schema (Iceberg): the schema lists
      * these in this order, with types from the files, so columns that the table dropped are hidden
      */
    tableFields: Option[IndexedSeq[ParquetField]] = None,
    /** Columns each file must hold, since no partition value fills them */
    requiredInFiles: Set[String] = Set.empty,
    /** For an Iceberg table: how files map to the table's columns by field ID */
    fieldIds: IcebergTable.FieldIds = IcebergTable.FieldIds.empty,
    /** An Iceberg table's properties; empty for other datasets */
    val tableProperties: Map[String, String] = Map.empty,
    /**
      * Values for top-level columns a file lacks, in place of null: an Iceberg column's
      * `initial-default` (see [[IcebergTable.Resolved.defaults]])
      */
    columnDefaults: Map[String, Any] = Map.empty,
    /** Deleted row positions of each file (sorted), from an Iceberg table's delete files */
    rowDeletes: Map[String, Array[Long]] = Map.empty
) extends AutoCloseable:
  import ParquetDataset.*

  private val partitionKeys: Set[String] = partitionColumns.map(_.name).toSet

  private val footers   = scala.collection.mutable.HashMap.empty[String, FileMetaData]
  private val openFiles = scala.collection.mutable.LinkedHashSet.empty[ParquetFile]

  /** The union of every file's columns, matched by name, followed by the partition columns */
  lazy val schema: ParquetSchema = prepare(ParquetReadOptions.default).schema

  /**
    * The files a read with these options opens: all of them, minus those whose partition values
    * can't match the row group filter. No file is opened to answer this.
    */
  def selectFiles(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): IndexedSeq[DatasetFile] = selectByPartition(options).map(_.file)

  /**
    * A dataset of only these files of [[files]], with the same columns: for reading a table file by
    * file, as a copy-on-write update does before [[ParquetDatasetWriteOptions.withReplacedFiles]].
    * It shares this dataset's object store and leaves it open.
    */
  def withFiles(selected: Seq[DatasetFile]): ParquetDataset =
    val known = files.map(_.path).toSet
    selected.foreach { f =>
      require(known.contains(f.path), s"${f.path} is not a file of ${root}")
    }
    ParquetDataset(
      root,
      selected.toIndexedSeq,
      partitionColumns,
      store,
      ownsStore = false,
      prefetch,
      metadataFields,
      tableFields,
      requiredInFiles,
      fieldIds,
      tableProperties,
      columnDefaults,
      rowDeletes
    )

  override def close(): Unit =
    val toClose = synchronized {
      val fs = openFiles.toSeq
      openFiles.clear()
      fs
    }
    try toClose.foreach(_.close())
    finally
      if ownsStore then
        store.foreach(_.close())

  // ---- Synchronous reading ----

  /**
    * Reads the selected files' row groups in order. Each batch has the dataset's selected columns:
    * a file's own vectors, nulls for columns the file lacks, and constant partition columns.
    */
  def readBatches(options: ParquetReadOptions = ParquetReadOptions.default): Iterator[ColumnBatch] =
    val selected = selectByPartition(options)
    val metas    = selected.map(s => footer(s.file))
    val plan     = buildPlan(options, selected, metas)
    selected
      .iterator
      .zip(metas.iterator)
      .flatMap { case (s, meta) =>
        fileBatches(s, meta, plan)
      }

  /**
    * Reads every row into `A` through Weaver. Columns, including partitions, match fields by name.
    */
  def readAs[A](options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Iterator[A] = readBatches(options).flatMap(b => ParquetFile.rows(b, weaver, options))

  /** Reads every row as a map from column name to value, as [[ParquetFile.readRows]] does */
  def readRows(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Iterator[Map[String, Any]] = readBatches(options).flatMap(_.rows)

  /** Reads every row as a JSON object. See [[ParquetJson]] for how values map to JSON. */
  def readJson(options: ParquetReadOptions = ParquetReadOptions.default): Iterator[JSONObject] =
    readRows(options).map(ParquetJson.fromRow)

  // ---- Asynchronous reading ----

  /** The dataset schema, as [[schema]], reading footers asynchronously */
  def schemaAsync: Rx[ParquetSchema] = prepareAsync(ParquetReadOptions.default).map(_._1.schema)

  /** Emits the batches of the selected files in order, as [[readBatches]] does */
  def readBatchesAsync(options: ParquetReadOptions = ParquetReadOptions.default): Rx[ColumnBatch] =
    prepareAsync(options).flatMap { case (plan, files) =>
      Rx.concatRx(files.map((s, meta) => fileBatchesAsync(s, meta, plan)))
    }

  /** Emits every row decoded into `A` */
  def readAsAsync[A](
      options: ParquetReadOptions = ParquetReadOptions.default
  )(using weaver: Weaver[A]): Rx[A] = readBatchesAsync(options).flatMap(b =>
    Rx.fromSeq(ParquetFile.rows(b, weaver, options).toSeq)
  )

  /** Emits every row as a map from column name to value */
  def readRowsAsync(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[Map[String, Any]] = readBatchesAsync(options).flatMap(b => Rx.fromSeq(b.rows.toSeq))

  /** Emits every row as a JSON object */
  def readJsonAsync(options: ParquetReadOptions = ParquetReadOptions.default): Rx[JSONObject] =
    readRowsAsync(options).map(ParquetJson.fromRow)

  /** All rows decoded into `A`, collected into one Seq */
  def readAllAsync[A](options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Rx[Seq[A]] = collect(readBatchesAsync(options))(b => ParquetFile.rows(b, weaver, options))

  /** All rows as maps from column name to value, collected into one Seq */
  def readAllRowsAsync(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[Seq[Map[String, Any]]] = collect(readBatchesAsync(options))(_.rows)

  /**
    * A reader the caller pulls one row group at a time, for consumers that need backpressure (the
    * npm facade's AsyncIterable). `readBatchesAsync` pushes every batch as soon as it is read.
    */
  private[parquet] def batchReaderAsync(
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Rx[DatasetBatchReader] = prepareAsync(options).map { (plan, files) =>
    new DatasetBatchReader:
      private var nextFile                 = 0
      private var current: ParquetFile     = null
      private var currentSel: Selected     = null
      private var opts: ParquetReadOptions = null
      private var rowGroups: List[Int]     = Nil
      private var closed                   = false
      private def finishFile(): Unit       =
        if current != null then
          release(current)
          current = null

      /** Opens files (no I/O; footers are cached) until one has a row group left to read */
      private def advance(): Boolean =
        while rowGroups.isEmpty && !closed && (current != null || nextFile < files.size) do
          finishFile()
          if nextFile < files.size then
            val (s, meta) = files(nextFile)
            nextFile += 1
            current = track(ParquetFile(openAsyncSource(s.file), meta))
            currentSel = s
            opts = fileOptions(s, plan, current.schema)
            rowGroups = current.selectRowGroups(opts).toList
        !closed && rowGroups.nonEmpty

      def nextRowGroup(): Rx[Option[Seq[ColumnBatch]]] =
        if !advance() then
          finishFile()
          Rx.single(None)
        else
          val i    = rowGroups.head
          val file = current
          val s    = currentSel
          rowGroups = rowGroups.tail
          val read: Rx[Seq[ColumnBatch]] =
            if opts.columns.exists(_.isEmpty) then
              Rx.single(emptyBatches(file.rowGroups(i).numRows, opts).toSeq)
            else
              file.rowGroupBatchesAsync(i, opts).map(_.toSeq)
          read
            .map(bs => withoutDeleted(s.file, file, i, bs.iterator).toSeq)
            .map(bs => Some(bs.map(b => finish(b, s, plan)).filter(_.numRows > 0)))
            .recoverWith { case e: Throwable =>
              close()
              Rx.exception(fileError(s.file, e))
            }

      def close(): Unit =
        closed = true
        rowGroups = Nil
        finishFile()
  }

  private def collect[A](batches: Rx[ColumnBatch])(f: ColumnBatch => Iterator[A]): Rx[Seq[A]] =
    batches
      .scan(Vector.empty[A])((acc, b) => acc ++ f(b))
      .lastOption
      .toRx
      .map(_.getOrElse(Vector.empty))

  // ---- Planning ----

  /**
    * A file that passed partition pruning, with what remains of each filter for its row groups.
    * `rowResidual` is None when the partition values alone decide that every row matches.
    */
  private case class Selected(
      file: DatasetFile,
      residual: Option[ParquetFilter],
      rowResidual: Option[ParquetFilter]
  )

  /**
    * What a read produces
    *
    * @param schema
    *   the dataset schema over the selected files
    * @param output
    *   the columns of each batch, in order
    * @param fileColumns
    *   the columns read from files (not partitions)
    * @param eval
    *   the output columns, then the columns only the row filter needs
    * @param rowFilter
    *   the exact filter, checked against batches of the `eval` columns
    */
  private case class Plan(
      schema: ParquetSchema,
      output: IndexedSeq[ParquetField],
      fileColumns: IndexedSeq[String],
      options: ParquetReadOptions,
      eval: IndexedSeq[ParquetField],
      rowFilter: Option[ParquetFilter.RowFilter]
  )

  private def selectByPartition(options: ParquetReadOptions): IndexedSeq[Selected] =
    if options.rowGroupFilter.isEmpty && options.filter.isEmpty then
      files.map(Selected(_, None, None))
    else
      val types = partitionColumns.map(p => p.name -> p).toMap
      // None: no file matches; Some(None): every row matches; Some(Some(r)): check r
      def residual(f: Option[ParquetFilter], file: DatasetFile): Option[Option[ParquetFilter]] =
        f match
          case None =>
            Some(None)
          case Some(f) =>
            simplify(f, file.partitions, types) match
              case Left(false) =>
                None
              case Left(true) =>
                Some(None)
              case Right(r) =>
                Some(Some(r))
      val pruner = StatsPruner.of(
        tableFields,
        options.rowGroupFilter.toSeq ++ options.filter,
        options.binaryAsString
      )
      files.flatMap { file =>
        for
          r   <- residual(options.rowGroupFilter, file)
          row <- residual(options.filter, file)
          if pruner.forall(_.mayMatch(ParquetFilter.both(r, row), file))
        yield Selected(file, r, row)
      }

  /**
    * Skips files by the statistics a table format records for them ([[DatasetFile.stats]]): each
    * file is checked as one row group whose column chunks carry the file's statistics
    */
  private final class StatsPruner(schema: ParquetSchema, binaryAsString: Boolean):
    // Column chunks in columnIndex order, which follows the schema's leaf order
    private val chunks = schema
      .columns
      .sortBy(_.columnIndex)
      .map { c =>
        ColumnChunkMeta(
          c.path,
          c.physicalType,
          Seq.empty,
          CompressionCodec.UNCOMPRESSED,
          0L,
          0L,
          0L,
          0L,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          false
        )
      }

    /** False only when the file's statistics prove that no row matches `f` */
    def mayMatch(f: Option[ParquetFilter], file: DatasetFile): Boolean =
      (f, file.stats) match
        case (Some(f), Some(stats)) =>
          val columns = chunks.map { c =>
            if c.path.size == 1 then
              stats
                .columns
                .get(c.path.head)
                .map(cs =>
                  c.copy(
                    numValues = stats.numRows,
                    statistics = Some(Statistics(cs.min, cs.max, cs.nullCount, None))
                  )
                )
                .getOrElse(c)
            else
              c
          }
          ParquetFilter.mayMatch(
            f,
            schema,
            RowGroupMeta(columns, 0L, stats.numRows, None),
            binaryAsString
          )
        case _ =>
          true

  end StatsPruner

  private object StatsPruner:
    /**
      * A pruner for a table dataset (only table formats record file statistics). None when a filter
      * names a column the table doesn't have, so the read reports it instead of skipping files
      */
    def of(
        tableFields: Option[IndexedSeq[ParquetField]],
        filters: Seq[ParquetFilter],
        binaryAsString: Boolean
    ): Option[StatsPruner] = tableFields.flatMap { fs =>
      val schema = ParquetSchema(
        ParquetField.Group("schema", Repetition.REQUIRED, None, Seq.empty, 0, 0, fs)
      )
      Option.when(
        filters.flatMap(_.columnNames).forall(n => ParquetFilter.leaf(schema, n).isRight)
      )(StatsPruner(schema, binaryAsString))
    }

  private def prepare(options: ParquetReadOptions): Plan =
    val selected = selectByPartition(options)
    // Footers are fetched several at a time where threads exist, as the async path does
    ParallelMap(selected.filter(s => cachedFooter(s.file).isEmpty), prefetch)(s => footer(s.file))
    buildPlan(options, selected, selected.map(s => footer(s.file)))

  private def prepareAsync(
      options: ParquetReadOptions
  ): Rx[(Plan, IndexedSeq[(Selected, FileMetaData)])] = Rx
    .single(selectByPartition(options))
    .flatMap { selected =>
      RxParallel
        .parSequenceN(prefetch)(selected.map(s => footerAsync(s.file)))
        .map { metas =>
          (buildPlan(options, selected, metas.toIndexedSeq), selected.zip(metas))
        }
    }

  private def buildPlan(
      options: ParquetReadOptions,
      selected: IndexedSeq[Selected],
      metas: IndexedSeq[FileMetaData]
  ): Plan =
    // A dotted filter name may refer to a top-level column with that name or to a struct field
    val filterNames = options.filter.toSeq.flatMap(_.columnNames)
    val filterTops  = filterNames.flatMap(n => Seq(n, n.takeWhile(_ != '.'))).distinct
    val wanted: Option[Set[String]] = options
      .columns
      .map(c => (c ++ filterTops).toSet -- partitionKeys)
    // Union of the files' top-level fields by name, in first-seen order
    val union   = scala.collection.mutable.LinkedHashMap.empty[String, ParquetField]
    val present = scala.collection.mutable.HashMap.empty[String, Int]
    // The file that set the type at each nested path, for naming files in type errors
    val origins = scala.collection.mutable.HashMap.empty[String, String]
    // Every top-level name in the selected files, for checking filter and column names
    val allNames                        = scala.collection.mutable.LinkedHashSet.empty[String]
    val tableNames: Option[Set[String]] = tableFields.map(_.map(_.name).toSet)
    selected
      .zip(metas)
      .foreach { (s, meta) =>
        val fileFields = ParquetSchema.fromElements(meta.schema).fields
        requiredInFiles.foreach { name =>
          if !fileFields.exists(_.name == name) then
            throw ParquetException(
              s"${s
                  .file
                  .path} has no column ${name}, and the table's manifests don't give its value"
            )
        }
        fileFields
          .filter(f => tableNames.forall(_.contains(f.name)))
          .foreach { f =>
            allNames += f.name
            if !partitionKeys.contains(f.name) && wanted.forall(_.contains(f.name)) then
              present(f.name) = present.getOrElse(f.name, 0) + 1
              union.get(f.name) match
                case None =>
                  union(f.name) = f
                  origins(f.name) = s.file.path
                case Some(prev) =>
                  union(f.name) = mergeField(prev, f, f.name, s.file.path, origins)
          }
      }
    // A column some files lack reads as null there
    def nullable(f: ParquetField): ParquetField =
      if !f.isRepeated && !f.isOptional && present.getOrElse(f.name, 0) < selected.size then
        withRepetition(f, Repetition.OPTIONAL)
      else
        f
    // A table column widened after the files were written (int to long, float to double) reads
    // with the table's type, so a later write binds to it
    def promoted(fileField: ParquetField, tableField: ParquetField): ParquetField =
      (fileField, tableField) match
        case (f: ParquetField.Primitive, t: ParquetField.Primitive)
            if f.physicalType != t.physicalType =>
          try
            // A scratch origins map: this is only a check, so the files' origins stay as they are
            mergeField(
              f,
              t,
              t.name,
              s"the table schema of ${root}",
              scala.collection.mutable.HashMap.empty
            )
          catch
            case _: ParquetException =>
              f
        case _ =>
          fileField
    val columns =
      tableFields match
        case Some(table) =>
          // The table's columns in table order, partition columns included; one that no selected
          // file has reads as null
          table.flatMap { t =>
            partitionColumns.find(_.name == t.name) match
              case Some(p) =>
                Some(p)
              case None if wanted.forall(_.contains(t.name)) =>
                Some(union.get(t.name).map(u => nullable(promoted(u, t))).getOrElse(nullable(t)))
              case None =>
                None
          }
        case None if files.isEmpty =>
          metadataFields.filterNot(f => partitionKeys.contains(f.name)) ++ partitionColumns
        case None =>
          union.values.map(nullable).toIndexedSeq ++ partitionColumns
    val schema = ParquetSchema.fromElements(
      SchemaElements.of(
        ParquetSchema(
          ParquetField.Group("schema", Repetition.REQUIRED, None, Seq.empty, 0, 0, columns)
        )
      )
    )
    // Unknown names fail here, before any rows are read. A filter may name columns the read
    // doesn't select. With no file selected there are no footers to check names against.
    if selected.nonEmpty then
      val known   = tableNames.getOrElse(allNames.toSet) ++ partitionKeys
      val missing =
        // A dotted filter name may name a struct field, checked later against the schema
        (
          options.columns.getOrElse(Seq.empty).filterNot(known.contains) ++
            options
              .pruneFilter
              .toSeq
              .flatMap(_.columnNames)
              .filterNot(n => known.contains(n) || known.contains(n.takeWhile(_ != '.')))
        ).distinct
      if missing.nonEmpty then
        throw IllegalArgumentException(
          s"No column named ${missing.mkString(", ")} in ${root}. Columns: ${(
              tableFields.map(_.map(_.name)).getOrElse(allNames.toSeq) ++ partitionKeys
            ).mkString(", ")}"
        )
    val output =
      options.columns match
        case None =>
          schema.fields
        case Some(names) =>
          names.flatMap(n => schema.field(n)).toIndexedSeq
    // The exact filter is checked against the dataset schema, so a file without a column reads it
    // as nulls and a narrow column compares after widening
    val rowFilter = options
      .filter
      .filter(_ => selected.nonEmpty)
      .map(f => ParquetFilter.RowFilter(f, schema, options.binaryAsString))
    val outputNames = output.map(_.name).toSet
    val eval        =
      output ++
        rowFilter.toSeq.flatMap(_.topColumns).filterNot(outputNames.contains).flatMap(schema.field)
    Plan(
      schema,
      output,
      eval.map(_.name).filterNot(partitionKeys.contains),
      options,
      eval,
      rowFilter
    )

  end buildPlan

  // ---- Reading files ----

  private def location(file: DatasetFile): Either[IOPath, ObjectUri] =
    store match
      case Some(_) =>
        Right(ObjectUri.parse(file.path))
      case None =>
        Left(IOPath.parse(file.path))

  private def openSource(file: DatasetFile): ByteSource =
    location(file) match
      case Left(p) =>
        ByteSource.ofFile(p)
      case Right(uri) =>
        ByteSource.ofObject(store.get, uri)

  private def openAsyncSource(file: DatasetFile): AsyncByteSource =
    location(file) match
      case Left(p) =>
        ByteSource.ofFile(p)
      case Right(uri) =>
        ByteSource.ofObjectAsync(store.get, uri)

  private def cachedFooter(file: DatasetFile): Option[FileMetaData] = synchronized(
    footers.get(file.path)
  )

  private def cache(file: DatasetFile, meta: FileMetaData): FileMetaData =
    val m = byFieldId(file, meta)
    synchronized {
      footers(file.path) = m
      m
    }

  /**
    * For an Iceberg table, the footer with its top-level columns named as the table names their
    * field IDs: a renamed column takes its current name, and a dropped one a name no column has.
    * Column chunks follow the schema by position, so the names are all that changes.
    */
  private def byFieldId(file: DatasetFile, meta: FileMetaData): FileMetaData =
    if fieldIds.known.isEmpty || meta.schema.isEmpty then
      meta
    else
      // The top-level elements: each is followed by its subtree
      val top = IndexedSeq.newBuilder[Int]
      var i   = 1
      while i < meta.schema.size do
        top += i
        var pending = meta.schema(i).numChildren.getOrElse(0)
        i += 1
        while pending > 0 do
          pending += meta.schema(i).numChildren.getOrElse(0) - 1
          i += 1
      val tops = top.result()
      if tops.forall(j => meta.schema(j).fieldId.exists(fieldIds.known.contains)) then
        val renamed =
          tops.foldLeft(meta.schema) { (els, j) =>
            val id = els(j).fieldId.get
            els.updated(
              j,
              els(j).copy(name =
                fieldIds.current.getOrElse(id, IcebergTable.FieldIds.droppedName(id))
              )
            )
          }
        meta.copy(schema = renamed)
      else
        fieldIds.byNameError.foreach(e => throw ParquetException(s"${file.path}: ${e}"))
        meta

  end byFieldId

  private def footer(file: DatasetFile): FileMetaData = cachedFooter(file).getOrElse {
    val f = wrapErrors(file)(ParquetFile.open(openSource(file)))
    try cache(file, f.metadata)
    finally f.close()
  }

  private def footerAsync(file: DatasetFile): Rx[FileMetaData] =
    cachedFooter(file) match
      case Some(m) =>
        Rx.single(m)
      case None =>
        Rx.single(openAsyncSource(file))
          .flatMap(ParquetFile.openAsync)
          .map { f =>
            try cache(file, f.metadata)
            finally f.close()
          }
          .recoverWith { case e: Throwable =>
            Rx.exception(fileError(file, e))
          }

  private def track(f: ParquetFile): ParquetFile = synchronized {
    openFiles += f
    f
  }

  private def release(f: ParquetFile): Unit =
    synchronized(openFiles -= f)
    f.close()

  private def fileOptions(s: Selected, plan: Plan, fileSchema: ParquetSchema): ParquetReadOptions =
    plan
      .options
      .copy(
        columns = Some(plan.fileColumns.filter(n => fileSchema.field(n).isDefined)),
        rowGroupFilter = ParquetFilter.both(s.residual, s.rowResidual),
        filter = None
      )

  private def fileBatches(s: Selected, meta: FileMetaData, plan: Plan): Iterator[ColumnBatch] =
    new Iterator[ColumnBatch]:
      private var file: ParquetFile         = null
      private var it: Iterator[ColumnBatch] = null
      private var done                      = false

      private def start(): Unit =
        if it == null && !done then
          file = track(ParquetFile(wrapErrors(s.file)(openSource(s.file)), meta))
          val opts = fileOptions(s, plan, file.schema)
          it = wrapIterator(
            s.file,
            batchesOf(s.file, file, opts).map(b => finish(b, s, plan)).filter(_.numRows > 0)
          )

      override def hasNext: Boolean =
        start()
        if done then
          false
        else if it.hasNext then
          true
        else
          done = true
          release(file)
          false

      override def next(): ColumnBatch =
        if !hasNext then
          throw java.util.NoSuchElementException()
        it.next()

  private def fileBatchesAsync(s: Selected, meta: FileMetaData, plan: Plan): Rx[ColumnBatch] = Rx
    .single(track(ParquetFile(openAsyncSource(s.file), meta)))
    .flatMap { file =>
      val opts                                 = fileOptions(s, plan, file.schema)
      val rowGroups: Seq[Rx[Seq[ColumnBatch]]] = file
        .selectRowGroups(opts)
        .map { i =>
          val read =
            if opts.columns.exists(_.isEmpty) then
              Rx.single(emptyBatches(file.rowGroups(i).numRows, opts).toSeq)
            else
              file.rowGroupBatchesAsync(i, opts).map(_.toSeq)
          read.map(bs => withoutDeleted(s.file, file, i, bs.iterator).toSeq)
        }
      val closing: Rx[Seq[ColumnBatch]] = Rx.single {
        release(file)
        Seq.empty
      }
      Rx.concatRx(rowGroups :+ closing)
        .flatMap(bs => Rx.fromSeq(bs.map(b => finish(b, s, plan)).filter(_.numRows > 0)))
        .recoverWith { case e: Throwable =>
          release(file)
          Rx.exception(fileError(s.file, e))
        }
    }

  /**
    * The file's batches, without its deleted rows; when only partition columns are selected,
    * batches with no columns
    */
  private def batchesOf(
      df: DatasetFile,
      file: ParquetFile,
      opts: ParquetReadOptions
  ): Iterator[ColumnBatch] =
    if opts.columns.exists(_.isEmpty) then
      file
        .selectRowGroups(opts)
        .iterator
        .flatMap(i => withoutDeleted(df, file, i, emptyBatches(file.rowGroups(i).numRows, opts)))
    else if rowDeletes.contains(df.path) then
      file
        .selectRowGroups(opts)
        .iterator
        .flatMap(i => withoutDeleted(df, file, i, file.rowGroupBatches(i, opts)))
    else
      file.readBatches(opts)

  /**
    * Row group `i`'s batches (all its rows, in order) without the rows the table deleted. Deletes
    * name rows by their position in the file, so this runs before any row filter.
    */
  private def withoutDeleted(
      df: DatasetFile,
      file: ParquetFile,
      i: Int,
      batches: Iterator[ColumnBatch]
  ): Iterator[ColumnBatch] =
    rowDeletes.get(df.path) match
      case None =>
        batches
      case Some(deleted) =>
        var first = 0L
        var g     = 0
        while g < i do
          first += file.rowGroups(g).numRows
          g += 1
        batches.map { b =>
          val start = first
          first += b.numRows
          dropRows(b, deleted, start)
        }

  end withoutDeleted

  private def emptyBatches(numRows: Long, opts: ParquetReadOptions): Iterator[ColumnBatch] =
    val size = opts.batchSize.getOrElse(ValueBuffer.MaxArrayLength).toLong
    Iterator
      .iterate(0L)(_ + size)
      .takeWhile(_ < numRows)
      .map(start => ColumnBatch(math.min(size, numRows - start).toInt, IndexedSeq.empty))

  /** A file's batch as the read returns it: in the plan's columns, with rows filtered */
  private def finish(b: ColumnBatch, s: Selected, plan: Plan): ColumnBatch =
    val all  = toOutput(b, s.file, plan)
    val kept =
      if s.rowResidual.isDefined then
        plan.rowFilter.fold(all)(_(all))
      else
        all
    if plan.eval.size == plan.output.size then
      kept
    else
      ColumnBatch(kept.numRows, kept.columns.take(plan.output.size))

  /** The batch with the plan's columns: the file's vectors, nulls, and partition values */
  private def toOutput(b: ColumnBatch, file: DatasetFile, plan: Plan): ColumnBatch =
    val n      = b.numRows
    val byName = b.columns.map(c => c.name -> c).toMap
    ColumnBatch(
      n,
      plan
        .eval
        .map { f =>
          if partitionKeys.contains(f.name) then
            constantVector(f.asInstanceOf[ParquetField.Primitive], file.partitions(f.name), n)
          else
            byName.get(f.name) match
              case Some(v) =>
                conformField(v, f)
              case None =>
                columnDefaults.get(f.name) match
                  case Some(v) =>
                    defaultColumn(f, v, n, plan.options)
                  case None =>
                    missingColumn(f, n, plan.options)
        }
    )

  /**
    * Nulls for a top-level column a file lacks, of the vector type the file's reader returns for
    * it: a LongColumn for an INT32 decimal read with `int32DecimalAsLong`
    */
  private def missingColumn(f: ParquetField, n: Int, opts: ParquetReadOptions): ColumnVector =
    f match
      case p: ParquetField.Primitive
          if opts.int32DecimalAsLong && !p.isRepeated && Assembly.isInt32Decimal(p) =>
        LongColumn(p, new Array[Long](n), nullFlags(n))
      case _ =>
        nullVector(f, n)

  /**
    * A top-level column a file lacks, filled with its default value in the vector type the file's
    * reader returns for the column's physical type
    */
  private def defaultColumn(
      f: ParquetField,
      v: Any,
      n: Int,
      opts: ParquetReadOptions
  ): ColumnVector =
    def fail(): Nothing =
      throw ParquetException(s"Column ${f.name} of ${root} can't hold its default value ${v}")
    f match
      case p: ParquetField.Primitive if !p.isRepeated =>
        def long: Long =
          v match
            case i: Int =>
              i.toLong
            case l: Long =>
              l
            case b: BigInt if b.isValidLong =>
              b.toLong
            case _ =>
              fail()
        p.physicalType match
          case PhysicalType.BOOLEAN =>
            v match
              case b: Boolean =>
                BooleanColumn(p, Array.fill(n)(b), null)
              case _ =>
                fail()
          case PhysicalType.INT32 =>
            val l = long
            if !l.isValidInt then
              fail()
            if opts.int32DecimalAsLong && Assembly.isInt32Decimal(p) then
              LongColumn(p, Array.fill(n)(l), null)
            else
              IntColumn(p, Array.fill(n)(l.toInt), null)
          case PhysicalType.INT64 =>
            LongColumn(p, Array.fill(n)(long), null)
          case PhysicalType.FLOAT =>
            v match
              case x: Float =>
                FloatColumn(p, Array.fill(n)(x), null)
              case _ =>
                fail()
          case PhysicalType.DOUBLE =>
            v match
              case x: Double =>
                DoubleColumn(p, Array.fill(n)(x), null)
              case _ =>
                fail()
          case _ =>
            val bytes =
              v match
                case b: Array[Byte] =>
                  b
                case d: BigInt if p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY =>
                  // A decimal's unscaled value as big-endian two's complement of the fixed length
                  val raw = d.toByteArray
                  if raw.length > p.typeLength then
                    fail()
                  val pad: Byte =
                    if d.signum < 0 then
                      -1
                    else
                      0
                  Array.fill(p.typeLength - raw.length)(pad) ++ raw
                case _ =>
                  fail()
            constantVector(p, bytes, n)
        end match
      case _ =>
        fail()
    end match

  end defaultColumn

  private def wrapErrors[A](file: DatasetFile)(body: => A): A =
    try
      body
    catch
      case e: Throwable =>
        throw fileError(file, e)

  private def wrapIterator[A](file: DatasetFile, it: Iterator[A]): Iterator[A] =
    new Iterator[A]:
      override def hasNext: Boolean = wrapErrors(file)(it.hasNext)
      override def next(): A        = wrapErrors(file)(it.next())

end ParquetDataset

object ParquetDataset extends wvlet.uni.log.LogSupport:
  private[parquet] final val DefaultPartition = "__HIVE_DEFAULT_PARTITION__"

  /** Adds the file to an error's message, once */
  private[parquet] def fileError(file: DatasetFile, e: Throwable): Throwable =
    e match
      case pe: ParquetException if pe.getMessage != null && pe.getMessage.contains(file.path) =>
        pe
      case _: IllegalArgumentException =>
        e
      case _ =>
        ParquetException(s"Failed to read ${file.path}: ${e.getMessage}", e)

  // ---- Discovery ----

  /** A listed file: its path or URI, size, and path segments below the root */
  private[parquet] case class Listed(path: String, size: Long, segments: Seq[String])

  private[parquet] def isObjectUri(root: String): Boolean = root.contains("://")

  /**
    * Whether local paths are Windows paths. Touches FileSystem first, which sets IOPath.isWindows
    */
  private[parquet] def isWindows: Boolean = !FileSystem.isBrowser && IOPath.isWindows

  /**
    * The root of an object prefix, with a trailing `/` so `events` doesn't also match `events_old`
    */
  private[parquet] def objectRoot(root: String): ObjectUri =
    val uri = ObjectUri.parse(root)
    if uri.key.isEmpty || uri.key.endsWith("/") then
      uri
    else
      uri.copy(key = s"${uri.key}/")

  private[parquet] def listObjects(store: ObjectStore, root: ObjectUri): Seq[Listed] = store
    .list(root, recursive = true)
    .flatMap(objectListed(root, _))

  private[parquet] def listObjectsAsync(store: ObjectStore, root: ObjectUri): Rx[Seq[Listed]] =
    store.listAsync(root, recursive = true).map(_.flatMap(objectListed(root, _)))

  private def objectListed(root: ObjectUri, info: wvlet.uni.storage.ObjectInfo): Option[Listed] =
    val key = info.uri.key
    if info.isDirectory || key.endsWith("/") || !key.startsWith(root.key) then
      None
    else
      val uri = ObjectUri(root.bucket, key, root.scheme)
      Some(Listed(uri.toString, info.size, key.substring(root.key.length).split('/').toSeq))

  private[parquet] def listLocal(root: IOPath): Seq[Listed] =
    if FileSystem.isFile(root) then
      Seq(Listed(root.path, FileSystem.info(root).size, Seq(root.fileName)))
    else if !FileSystem.isDirectory(root) then
      Seq.empty
    else
      FileSystem
        .list(root, ListOptions.default.withRecursive(true).withIncludeHidden(true))
        .filter(FileSystem.isFile)
        .map(p => Listed(p.path, FileSystem.info(p).size, p.relativeTo(root).segments))

  /** Builds the dataset from a listing: skip rules, sorting, and partition columns */
  private[parquet] def build(
      root: String,
      listed: Seq[Listed],
      options: ParquetDatasetOptions,
      store: Option[ObjectStore],
      ownsStore: Boolean,
      skipHidden: Boolean = true,
      source: String = "",
      metadata: Option[DatasetMetadata] = None
  ): ParquetDataset =
    val kept = listed
      .filter { l =>
        l.segments.nonEmpty &&
        (!skipHidden || !l.segments.exists(s => s.startsWith("_") || s.startsWith("."))) &&
        options.fileExtension.forall(ext => l.segments.last.endsWith(ext))
      }
      .distinctBy(_.path)
      .sortBy(_.path)
    if kept.isEmpty && metadata.exists(!_.rootHasData) then
      // A write without rows leaves only the root files: an empty dataset with the recorded schema
      val m       = metadata.get
      val unknown = options.partitionTypes.keySet -- m.partitionTypes.keySet
      if unknown.nonEmpty then
        throw IllegalArgumentException(
          s"withPartitionType names ${unknown
              .toSeq
              .sorted
              .mkString(", ")}, but the partition keys under ${root} are: ${m
              .partitionTypes
              .keys
              .mkString(", ")}"
        )
      val columns =
        m.partitionTypes
          .keys
          .zipWithIndex
          .map((k, i) =>
            partitionColumn(k, options.partitionTypes.getOrElse(k, m.partitionTypes(k)), i)
          )
          .toIndexedSeq
      return ParquetDataset(
        rootNameOf(root),
        IndexedSeq.empty,
        columns,
        store,
        ownsStore,
        options.prefetch,
        m.fields
      )
    end if
    if kept.isEmpty then
      val where =
        if source.nonEmpty then
          source
        else
          s"under ${root}"
      throw ParquetException(s"No Parquet files found ${where}")
    val withKeys = kept.map { l =>
      val parts = l
        .segments
        .init
        .flatMap { s =>
          val eq = s.indexOf('=')
          if eq <= 0 then
            None
          else
            Some(unescape(s.substring(0, eq)) -> unescape(s.substring(eq + 1)))
        }
      (l, parts)
    }
    val (first, firstParts) = withKeys.head
    val keys                = firstParts.map(_._1)
    if keys.distinct.size != keys.size then
      throw ParquetException(s"${first.path} repeats a partition key: ${keys.mkString(", ")}")
    withKeys.foreach { (l, parts) =>
      if parts.map(_._1) != keys then
        throw ParquetException(
          s"Partition folders differ: ${first.path} has (${keys.mkString(", ")}), but ${l
              .path} has (${parts.map(_._1).mkString(", ")})"
        )
    }
    val unknown = options.partitionTypes.keySet -- keys
    if unknown.nonEmpty then
      throw IllegalArgumentException(
        s"withPartitionType names ${unknown
            .toSeq
            .sorted
            .mkString(", ")}, but the partition keys under ${root} are: ${keys.mkString(", ")}"
      )
    // Recorded types apply only when the recorded keys are the folder keys
    val recorded: Map[String, ParquetType.Primitive] =
      metadata match
        case Some(m) if m.partitionTypes.keys.toSeq == keys =>
          m.partitionTypes
        case Some(m) =>
          logger.warn(
            s"Ignoring the partition types in ${rootNameOf(root)}${DatasetMetadata
                .CommonMetadata}: it lists (${m
                .partitionTypes
                .keys
                .mkString(", ")}), but the folders have (${keys.mkString(", ")})"
          )
          Map.empty
        case None =>
          Map.empty
    val columns =
      keys
        .zipWithIndex
        .map { (k, i) =>
          partitionColumn(
            k,
            options.partitionTypes.getOrElse(k, recorded.getOrElse(k, ParquetType.STRING)),
            i
          )
        }
        .toIndexedSeq
    val files =
      withKeys
        .map { (l, parts) =>
          DatasetFile(
            l.path,
            l.size,
            VectorMap.from(
              parts
                .zip(columns)
                .map { case ((k, v), c) =>
                  k -> parseValue(v, c, l.path)
                }
            )
          )
        }
        .toIndexedSeq
    ParquetDataset(rootNameOf(root), files, columns, store, ownsStore, options.prefetch)

  end build

  /**
    * The root with a trailing separator, `\` for a local Windows path, so file paths start with it
    */
  private def rootNameOf(root: String): String =
    if root.endsWith("/") || (isWindows && root.endsWith("\\")) then
      root
    else if isWindows && !isObjectUri(root) then
      s"${root}\\"
    else
      s"${root}/"

  /** A local file path in the platform form: Delta logs and globs use `/` even on Windows */
  private[parquet] def localPath(p: String): String =
    if isWindows then
      IOPath.parse(p).path
    else
      p

  private[parquet] def partitionColumn(
      key: String,
      t: ParquetType.Primitive,
      index: Int
  ): ParquetField.Primitive = ParquetField.Primitive(
    key,
    Repetition.OPTIONAL,
    t.physicalType,
    0,
    t.logicalType,
    Seq(key),
    1,
    0,
    index
  )

  // ---- Delta Lake tables ----

  /** Whether to read a plain root as a Delta table, from its listing */
  private[parquet] def isDelta(listed: Seq[Listed], options: ParquetDatasetOptions): Boolean =
    options.tableFormat match
      case ParquetTableFormat.Delta =>
        true
      case ParquetTableFormat.Files | ParquetTableFormat.Iceberg =>
        false
      case ParquetTableFormat.Auto =>
        DeltaLog.isTable(listed)

  /** Fails for Delta-only options on a root read as files */
  private[parquet] def requireFiles(options: ParquetDatasetOptions, root: String): Unit =
    if options.tableFormat == ParquetTableFormat.Delta || options.version.isDefined then
      throw IllegalArgumentException(
        s"withTableFormat(Delta) and withVersion read a Delta table, so they need the table's root folder with a ${DeltaLog
            .LogDir}/ folder: ${root}"
      )
    if options.tableFormat == ParquetTableFormat.Iceberg || options.snapshotId.isDefined then
      throw IllegalArgumentException(
        s"withTableFormat(Iceberg) and withSnapshotId read an Iceberg table, so they need the table's root folder with a ${IcebergTable
            .MetadataDir}/ folder, or a metadata file: ${root}"
      )

  /** The log files to fetch for a Delta table, with the root listing or a `_delta_log/` listing */
  private[parquet] def deltaPlan(
      root: String,
      listed: Seq[Listed],
      options: ParquetDatasetOptions
  ): DeltaLog.LogPlan =
    if !DeltaLog.isTable(listed) then
      throw ParquetException(
        s"${rootNameOf(root)} is not a Delta table: it has no ${DeltaLog.LogDir}/ folder"
      )
    if options.snapshotId.isDefined then
      throw IllegalArgumentException(
        s"withSnapshotId reads an Iceberg table, but ${rootNameOf(
            root
          )} is a Delta table. Use withVersion"
      )
    DeltaLog.plan(rootNameOf(root), listed, options.version)

  /** A dataset of a Delta table's live files at the planned version */
  private[parquet] def deltaDataset(
      root: String,
      plan: DeltaLog.LogPlan,
      fetched: Seq[Array[Byte]],
      options: ParquetDatasetOptions,
      store: Option[ObjectStore],
      ownsStore: Boolean
  ): ParquetDataset =
    val tableRoot = rootNameOf(root)
    val snapshot  = DeltaLog.replay(tableRoot, plan, fetched)
    val fields    = DeltaLog.fields(snapshot.schema)
    val keys      = snapshot.partitionColumns
    val unknown   = options.partitionTypes.keySet -- keys
    if unknown.nonEmpty then
      throw IllegalArgumentException(
        s"withPartitionType names ${unknown
            .toSeq
            .sorted
            .mkString(", ")}, but the partition columns of ${tableRoot} are: ${keys.mkString(", ")}"
      )
    val columns = keys
      .zipWithIndex
      .map { (k, i) =>
        val deltaType = fields.find(_._1 == k).map(_._2)
        val tpe       = options
          .partitionTypes
          .get(k)
          .orElse(deltaType.flatMap(DeltaLog.partitionType))
          .getOrElse(
            throw ParquetException(
              s"Partition column ${k} of ${tableRoot} has the Delta type ${deltaType
                  .map(_.toJSON)
                  .getOrElse(
                    "(missing)"
                  )}, which Uni reads only with withPartitionType(\"${k}\", ParquetType.STRING)"
            )
          )
        partitionColumn(k, tpe, i)
      }
    val files = snapshot
      .files
      .map { a =>
        val path = deltaFilePath(tableRoot, a.path, store.isDefined)
        DatasetFile(
          path,
          a.size,
          VectorMap.from(
            columns.map { c =>
              val v = a.partitionValues.getOrElse(c.name, null)
              c.name -> (
                if v == null then
                  null
                else
                  parseValue(v, c, path, "the type in the table schema")
              )
            }
          )
        )
      }
      .sortBy(_.path)
    val schemaFields =
      if files.isEmpty then
        ParquetSchema
          .fromColumns("schema", DeltaLog.columns(fields.filterNot(f => keys.contains(f._1))))
          .fields
      else
        IndexedSeq.empty
    ParquetDataset(tableRoot, files, columns, store, ownsStore, options.prefetch, schemaFields)

  end deltaDataset

  /** A data file's path or URI, from the relative or absolute URI that the log records */
  private def deltaFilePath(tableRoot: String, p: String, objects: Boolean): String =
    if p.contains("://") || p.startsWith("file:") then
      def outside = ParquetException(
        s"The Delta table ${tableRoot} lists ${p}, which is not in the same ${
            if objects then
              "bucket"
            else
              "file system"
          }"
      )
      if objects then
        val normalized =
          if p.startsWith("s3a://") || p.startsWith("s3n://") then
            s"s3${p.substring(3)}"
          else
            p
        val uri  = ObjectUri.parse(normalized)
        val base = ObjectUri.parse(tableRoot)
        if uri.scheme != base.scheme || uri.bucket != base.bucket then
          throw outside
        uri.copy(key = unescape(uri.key)).toString
      else if p.startsWith("file:") then
        // On Windows, file:///C:/a gives /C:/a, which parses as C:\a
        localPath(unescape(localPathOfFileUri(p).getOrElse(throw outside)))
      else
        throw outside
    else if p.startsWith("/") then
      throw ParquetException(s"The Delta table ${tableRoot} lists ${p}, which is not a URI")
    else if objects then
      s"${tableRoot}${unescape(p)}"
    else
      localPath(s"${tableRoot}${unescape(p)}")

  /** The local path of a `file:/a/b`, `file:///a/b`, or `file://localhost/a/b` URI */
  private[parquet] def localPathOfFileUri(p: String): Option[String] =
    val rest = p.substring("file:".length)
    if rest.startsWith("///") then
      Some(rest.substring(2))
    else if rest.startsWith("//localhost/") then
      Some(rest.substring("//localhost".length))
    else if rest.startsWith("/") && !rest.startsWith("//") then
      Some(rest)
    else
      None

  /** Fetches log files: several at a time where threads exist */
  private[parquet] def fetchLog(
      plan: DeltaLog.LogPlan,
      store: Option[ObjectStore],
      prefetch: Int
  ): Seq[Array[Byte]] =
    ParallelMap(plan.files.toIndexedSeq, prefetch) { l =>
      store match
        case Some(s) =>
          s.get(ObjectUri.parse(l.path))
        case None =>
          FileSystem.readBytes(IOPath.parse(l.path))
    }

  private[parquet] def fetchLogAsync(
      plan: DeltaLog.LogPlan,
      store: ObjectStore,
      prefetch: Int
  ): Rx[Seq[Array[Byte]]] =
    RxParallel.parSequenceN(prefetch)(plan.files.map(l => store.getAsync(ObjectUri.parse(l.path))))

  private[parquet] def underLog(listed: Seq[Listed]): Seq[Listed] = under(DeltaLog.LogDir, listed)

  /** A listing of `dir/` below a root, as root-relative segments */
  private[parquet] def under(dir: String, listed: Seq[Listed]): Seq[Listed] = listed.map(l =>
    l.copy(segments = dir +: l.segments)
  )

  // ---- Apache Iceberg tables ----

  /** Whether to read a plain root as an Iceberg table, from its listing (after Delta detection) */
  private[parquet] def isIceberg(listed: Seq[Listed], options: ParquetDatasetOptions): Boolean =
    options.tableFormat match
      case ParquetTableFormat.Iceberg =>
        true
      case ParquetTableFormat.Files | ParquetTableFormat.Delta =>
        false
      case ParquetTableFormat.Auto =>
        IcebergTable.isTable(listed)

  /** Whether a root names one Iceberg metadata file, read as that table version */
  private[parquet] def isIcebergMetadataFile(
      root: String,
      options: ParquetDatasetOptions
  ): Boolean =
    IcebergTable.isMetadataFile(root) && (
      options.tableFormat == ParquetTableFormat.Auto ||
        options.tableFormat == ParquetTableFormat.Iceberg
    )

  private def checkIcebergOptions(root: String, options: ParquetDatasetOptions): Unit =
    if options.version.isDefined then
      throw IllegalArgumentException(
        s"withVersion reads a Delta table, but ${root} is an Iceberg table. Use withSnapshotId"
      )
    if options.partitionTypes.nonEmpty then
      throw IllegalArgumentException(
        s"withPartitionType doesn't apply to the Iceberg table ${root}: its schema gives the partition types"
      )

  /** The steps that read an Iceberg table from its root listing */
  private[parquet] def icebergFromListing(
      root: String,
      listed: Seq[Listed],
      options: ParquetDatasetOptions,
      objects: Boolean
  ): IcebergTable.Step =
    val tableRoot = rootNameOf(root)
    checkIcebergOptions(tableRoot, options)
    if !IcebergTable.isTable(listed) then
      throw ParquetException(
        s"${tableRoot} is not an Iceberg table: it has no ${IcebergTable
            .MetadataDir}/*.metadata.json"
      )
    IcebergTable.fromListing(tableRoot, listed, options.snapshotId, objects)

  /** The steps that read an Iceberg table from one of its metadata files */
  private[parquet] def icebergFromMetadataFile(
      path: String,
      options: ParquetDatasetOptions,
      objects: Boolean
  ): IcebergTable.Step =
    val tableRoot = IcebergTable
      .rootOfMetadataFile(path)
      .getOrElse(path.substring(0, IcebergTable.lastSeparator(path) + 1))
    checkIcebergOptions(tableRoot, options)
    IcebergTable.fromMetadataFile(tableRoot, path, options.snapshotId, objects)

  /** Runs the steps of an Iceberg read, fetching several files at a time where threads exist */
  private[parquet] def runIceberg(
      step: IcebergTable.Step,
      store: Option[ObjectStore],
      prefetch: Int
  ): IcebergTable.Resolved =
    var s = step
    while true do
      s match
        case IcebergTable.Step.Done(t) =>
          return t
        case IcebergTable.Step.Fetch(paths, next) =>
          s = next(ParallelMap(paths, prefetch)(p => fetchBytes(p, store)))
    throw IllegalStateException("unreachable")

  /**
    * Runs the steps asynchronously. Files are fetched in batches, each after a timer hop, so a
    * snapshot with many manifests on a store that completes at once doesn't grow the stack.
    */
  private[parquet] def runIcebergAsync(
      step: IcebergTable.Step,
      store: ObjectStore,
      prefetch: Int
  ): Rx[IcebergTable.Resolved] =
    step match
      case IcebergTable.Step.Done(t) =>
        Rx.single(t)
      case IcebergTable.Step.Fetch(paths, next) =>
        def loop(
            batches: List[IndexedSeq[String]],
            acc: Vector[Array[Byte]]
        ): Rx[Vector[Array[Byte]]] =
          batches match
            case Nil =>
              Rx.single(acc)
            case batch :: rest =>
              Rx.delay(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .flatMap(_ =>
                  RxParallel.parSequenceN(prefetch)(
                    batch.map(p => store.getAsync(ObjectUri.parse(p)))
                  )
                )
                .flatMap(bytes => loop(rest, acc ++ bytes))
        loop(paths.grouped(IcebergFetchBatch).toList, Vector.empty).flatMap(bytes =>
          runIcebergAsync(next(bytes), store, prefetch)
        )

  private val IcebergFetchBatch = 64

  private def fetchBytes(path: String, store: Option[ObjectStore]): Array[Byte] =
    try
      store match
        case Some(s) =>
          s.get(ObjectUri.parse(path))
        case None =>
          FileSystem.readBytes(IOPath.parse(path))
    catch
      case e: ParquetException =>
        throw e
      case e: Exception =>
        throw ParquetException(s"Failed to read ${path}: ${e.getMessage}", e)

  /** A dataset of an Iceberg table's live files at the chosen snapshot */
  private[parquet] def icebergDataset(
      table: IcebergTable.Resolved,
      options: ParquetDatasetOptions,
      store: Option[ObjectStore],
      ownsStore: Boolean
  ): ParquetDataset =
    val columns = table
      .partitionColumns
      .zipWithIndex
      .map { case ((k, t), i) =>
        partitionColumn(k, t, i)
      }
    val files = table
      .files
      .map(f =>
        DatasetFile(
          f.path,
          f.size,
          VectorMap.from(columns.map(c => c.name -> f.partitions.getOrElse(c.name, null))),
          f.stats
        )
      )
    val fields = ParquetSchema.fromColumns("schema", table.columns).fields
    ParquetDataset(
      table.root,
      files,
      columns,
      store,
      ownsStore,
      options.prefetch,
      tableFields = Some(fields),
      requiredInFiles = table.requiredInFiles,
      fieldIds = table.fieldIds,
      tableProperties = table.properties,
      columnDefaults = table.defaults,
      rowDeletes = table.deletes
    )

  end icebergDataset

  // ---- _common_metadata ----

  /** The `_common_metadata` file directly under the root, from a listing that includes `_` files */
  private[parquet] def metadataListed(
      listed: Seq[Listed],
      options: ParquetDatasetOptions
  ): Option[Listed] =
    if options.readCommonMetadata then
      listed.find(_.segments == Seq(DatasetMetadata.CommonMetadata))
    else
      None

  private[parquet] def readMetadata(
      listed: Seq[Listed],
      options: ParquetDatasetOptions,
      store: Option[ObjectStore]
  ): Option[DatasetMetadata] = metadataListed(listed, options).flatMap { l =>
    val bytes =
      store match
        case Some(s) =>
          s.get(ObjectUri.parse(l.path))
        case None =>
          FileSystem.readBytes(IOPath.parse(l.path))
    DatasetMetadata.parse(bytes, l.path).map(_.copy(rootHasData = hasDataFiles(listed)))
  }

  private def hasDataFiles(listed: Seq[Listed]): Boolean = listed.exists(l =>
    l.segments.nonEmpty && !l.segments.exists(s => s.startsWith("_") || s.startsWith("."))
  )

  private[parquet] def readMetadataAsync(
      listed: Seq[Listed],
      options: ParquetDatasetOptions,
      store: ObjectStore
  ): Rx[Option[DatasetMetadata]] =
    metadataListed(listed, options) match
      case Some(l) =>
        store
          .getAsync(ObjectUri.parse(l.path))
          .map(b =>
            DatasetMetadata.parse(b, l.path).map(_.copy(rootHasData = hasDataFiles(listed)))
          )
      case None =>
        Rx.single(None)

  // ---- Glob patterns and file lists ----

  private val GlobChars   = "*?[{"
  private val RegexSyntax = "\\^$.|?*+()[]{}"

  /**
    * Splits a glob pattern into the root to list (up to the last `/` before the first unescaped
    * glob character) and the pattern for the paths below it. None when there is no glob character.
    */
  private[parquet] def splitGlob(pattern: String): Option[(String, String)] =
    var i     = 0
    var first = -1
    while first < 0 && i < pattern.length do
      val c = pattern.charAt(i)
      if c == '\\' then
        i += 2
      else
        if GlobChars.indexOf(c) >= 0 then
          first = i
        i += 1
    if first < 0 then
      None
    else
      val cut  = pattern.lastIndexOf('/', first)
      val base = pattern.substring(0, cut + 1)
      if isObjectUri(pattern) && !base.substring(base.indexOf("://") + 3).contains('/') then
        throw IllegalArgumentException(
          s"A glob pattern can't be in the bucket name: ${pattern}. List each bucket separately"
        )
      // The base is listed as is, so its escapes are removed
      Some((base.replaceAll("\\\\(.)", "$1"), pattern.substring(cut + 1).stripSuffix("/")))

  /**
    * A matcher for paths below a glob root: `*` and `?` match within one segment, `[...]` is a set
    * (`[!...]` negated), `{a,b}` alternatives, `**` any number of segments, and `\` escapes the
    * next character. A path matches when the pattern matches it or one of its parent folders, so
    * `dt=2026-11-*` keeps every file below the matching folders.
    */
  private[parquet] def globMatcher(pattern: String): Seq[String] => Boolean =
    val regex = java.util.regex.Pattern.compile(globRegex(pattern))
    segments =>
      var n     = segments.length
      var found = false
      while !found && n > 0 do
        found = regex.matcher(segments.take(n).mkString("/")).matches()
        n -= 1
      found

  private def globRegex(p: String): String =
    val sb     = StringBuilder()
    var i      = 0
    var braces = 0
    // Escape only regex syntax characters: other escapes such as `\=` are invalid in the unicode
    // mode of JavaScript regexes, which Scala.js may use
    def quote(c: Char): Unit =
      if RegexSyntax.indexOf(c) >= 0 then
        sb.append('\\')
      sb.append(c)
    while i < p.length do
      val c = p.charAt(i)
      c match
        case '\\' if i + 1 < p.length =>
          quote(p.charAt(i + 1))
          i += 1
        case '*' if i + 1 < p.length && p.charAt(i + 1) == '*' =>
          if i + 2 < p.length && p.charAt(i + 2) == '/' then
            // `**/` matches zero or more whole folders
            sb.append("(?:[^/]+/)*")
            i += 2
          else
            sb.append(".*")
            i += 1
        case '*' =>
          sb.append("[^/]*")
        case '?' =>
          sb.append("[^/]")
        case '[' =>
          val end = p.indexOf(']', i + 2)
          if end < 0 then
            quote(c)
          else
            val body = p.substring(i + 1, end)
            // A set matches one character within a folder name, never `/`
            sb.append('[')
            val set =
              if body.startsWith("!") || body.startsWith("^") then
                sb.append("^/")
                body.substring(1)
              else
                body.filterNot(_ == '/')
            set.foreach { ch =>
              if "\\]^[".indexOf(ch) >= 0 then
                sb.append('\\')
              sb.append(ch)
            }
            sb.append(']')
            i = end
        case '{' =>
          braces += 1
          sb.append("(?:")
        case '}' if braces > 0 =>
          braces -= 1
          sb.append(')')
        case ',' if braces > 0 =>
          sb.append('|')
        case other =>
          quote(other)
      end match
      i += 1
    end while
    if braces > 0 then
      throw IllegalArgumentException(s"Unclosed { in the pattern ${p}")
    sb.result()

  end globRegex

  /** Listed entries for an explicit list of local files, relative to their common parent */
  private[parquet] def listedLocalFiles(paths: Seq[IOPath]): (IOPath, Seq[Listed]) =
    val parent = commonParent(paths.map(_.segments))
    val root   = IOPath(parent, isAbsolute = true)
    val listed = paths.map { p =>
      if !FileSystem.isFile(p) then
        throw ParquetException(s"No such file: ${p.path}")
      Listed(p.path, FileSystem.info(p).size, p.segments.drop(parent.size))
    }
    (root, listed)

  /**
    * Listed entries for an explicit list of objects in one bucket, relative to their common parent.
    * No request is made, so sizes are -1.
    */
  private[parquet] def listedObjects(uris: Seq[ObjectUri]): (ObjectUri, Seq[Listed]) =
    val first = uris.head
    uris
      .find(u => u.scheme != first.scheme || u.bucket != first.bucket)
      .foreach { u =>
        throw IllegalArgumentException(
          s"Every file of a dataset must be in one bucket, but the list has ${first} and ${u}"
        )
      }
    val keys   = uris.map(_.key.split('/').toSeq)
    val parent = commonParent(keys)
    val root   = ObjectUri(first.bucket, parent.map(s => s"${s}/").mkString, first.scheme)
    (root, uris.zip(keys).map((u, k) => Listed(u.toString, -1L, k.drop(parent.size))))

  /** The longest common folder of the paths, given as segments ending with the file name */
  private def commonParent(paths: Seq[Seq[String]]): Seq[String] = paths
    .map(_.init)
    .reduce { (a, b) =>
      a.zip(b).takeWhile((x, y) => x == y).map(_._1)
    }

  /** Decodes the `%XX` escapes Hive and Spark write in partition folder names */
  private[parquet] def unescape(s: String): String =
    if !s.contains('%') then
      s
    else
      val out = java.io.ByteArrayOutputStream()
      var i   = 0
      while i < s.length do
        val c = s.charAt(i)
        if c == '%' && i + 2 < s.length && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2)) then
          out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16))
          i += 3
        else
          val bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8)
          out.write(bytes, 0, bytes.length)
          i += 1
      String(out.toByteArray, StandardCharsets.UTF_8)

  private def isHex(c: Char): Boolean = Character.digit(c, 16) >= 0

  private def parseValue(
      v: String,
      c: ParquetField.Primitive,
      path: String,
      typeSource: String = "the type set with withPartitionType"
  ): Any =
    if v == DefaultPartition then
      null
    else
      def fail(tpe: String) =
        throw ParquetException(s"Partition ${c.name}=${v} in ${path} is not ${tpe}, ${typeSource}")
      partitionKind(c) match
        case Kind.Str =>
          v
        case Kind.I32 =>
          v.toIntOption.getOrElse(fail("an INT32"))
        case Kind.I64 =>
          v.toLongOption.getOrElse(fail("an INT64"))
        case Kind.Date =>
          try
            LocalDate.parse(v)
          catch
            case _: java.time.DateTimeException =>
              fail("a DATE (yyyy-MM-dd)")

  private enum Kind:
    case Str,
      I32,
      I64,
      Date

  private def partitionKind(c: ParquetField.Primitive): Kind =
    (c.physicalType, c.logicalType) match
      case (PhysicalType.INT32, Some(LogicalType.Date)) =>
        Kind.Date
      case (PhysicalType.INT32, _) =>
        Kind.I32
      case (PhysicalType.INT64, _) =>
        Kind.I64
      case _ =>
        Kind.Str

  // ---- Partition pruning ----

  /**
    * Evaluates the partition columns of a filter against one file's values. Left is the filter's
    * exact answer; Right is what is left to check against row group statistics.
    */
  private[parquet] def simplify(
      f: ParquetFilter,
      values: Map[String, Any],
      columns: Map[String, ParquetField.Primitive]
  ): Either[Boolean, ParquetFilter] =
    import ParquetFilter.*
    f match
      case And(a, b) =>
        (simplify(a, values, columns), simplify(b, values, columns)) match
          case (Left(false), _) | (_, Left(false)) =>
            Left(false)
          case (Left(true), x) =>
            x
          case (x, Left(true)) =>
            x
          case (Right(x), Right(y)) =>
            Right(And(x, y))
      case Or(a, b) =>
        (simplify(a, values, columns), simplify(b, values, columns)) match
          case (Left(true), _) | (_, Left(true)) =>
            Left(true)
          case (Left(false), x) =>
            x
          case (x, Left(false)) =>
            x
          case (Right(x), Right(y)) =>
            Right(Or(x, y))
      case IsNull(c) if columns.contains(c) =>
        Left(values(c) == null)
      case IsNotNull(c) if columns.contains(c) =>
        Left(values(c) != null)
      case Compare(c, op, v) if columns.contains(c) =>
        val lit = literal(columns(c), v)
        values(c) match
          case null =>
            // As in SQL, a comparison with null is never true
            Left(false)
          case pv =>
            val cmp =
              (pv, lit) match
                case (a: String, b: String) =>
                  // UTF-8 byte order, as string columns compare
                  compareStrings(a, b)
                case (a: Int, b: Long) =>
                  a.toLong.compareTo(b)
                case (a: Long, b: Long) =>
                  a.compareTo(b)
                case (a: LocalDate, b: LocalDate) =>
                  a.compareTo(b)
                case _ =>
                  throw IllegalStateException(s"Cannot compare ${pv} with ${lit}")
            Left(
              op match
                case Op.Eq =>
                  cmp == 0
                case Op.Lt =>
                  cmp < 0
                case Op.LtEq =>
                  cmp <= 0
                case Op.Gt =>
                  cmp > 0
                case Op.GtEq =>
                  cmp >= 0
            )
        end match
      case other =>
        Right(other)

    end match

  end simplify

  /** A filter literal as the partition column's type, when the conversion is lossless */
  private def literal(c: ParquetField.Primitive, v: Any): Any =
    def fail(tpe: String) =
      throw IllegalArgumentException(
        s"Partition column ${c.name} is ${tpe}, so it can't be compared with ${v} (${
            if v == null then
              "null"
            else
              v.getClass.getSimpleName
          }). ${
            if tpe == "STRING" then
              "Compare it with a String, or set its type with ParquetDatasetOptions.withPartitionType."
            else
              s"Compare it with a ${tpe} value."
          }"
      )
    partitionKind(c) match
      case Kind.Str =>
        v match
          case s: String =>
            s
          case _ =>
            fail("STRING")
      case Kind.I32 | Kind.I64 =>
        val tpe =
          if partitionKind(c) == Kind.I32 then
            "INT32"
          else
            "INT64"
        v match
          case i: Int =>
            i.toLong
          case l: Long =>
            l
          case s: Short =>
            s.toLong
          case b: Byte =>
            b.toLong
          case s: String =>
            s.toLongOption.getOrElse(fail(tpe))
          case _ =>
            fail(tpe)
      case Kind.Date =>
        v match
          case d: LocalDate =>
            d
          case s: String =>
            try
              LocalDate.parse(s)
            catch
              case _: java.time.DateTimeException =>
                fail("DATE")
          case _ =>
            fail("DATE")
    end match

  end literal

  // ---- Type widening ----

  /**
    * Numeric column kinds that widen losslessly: small signed ints to INT64 or DOUBLE, FLOAT to
    * DOUBLE
    */
  private enum Num:
    case SignedInt(bits: Int)
    case Int64
    case Float32
    case Float64

  private def numKind(f: ParquetField): Option[Num] =
    f match
      case p: ParquetField.Primitive =>
        (p.physicalType, p.logicalType) match
          case (PhysicalType.INT32, None) =>
            Some(Num.SignedInt(32))
          case (PhysicalType.INT32, Some(LogicalType.Integer(bits, true))) =>
            Some(Num.SignedInt(bits))
          case (PhysicalType.INT64, None | Some(LogicalType.Integer(64, true))) =>
            Some(Num.Int64)
          case (PhysicalType.FLOAT, None) =>
            Some(Num.Float32)
          case (PhysicalType.DOUBLE, None) =>
            Some(Num.Float64)
          case _ =>
            None
      case _ =>
        None

  /** Whether a column of kind `from` reads losslessly as kind `to` */
  private def widensTo(from: Num, to: Num): Boolean =
    (from, to) match
      case (Num.SignedInt(a), Num.SignedInt(b)) =>
        a <= b
      case (Num.SignedInt(_), Num.Int64 | Num.Float64) =>
        true
      case (Num.Float32, Num.Float64) =>
        true
      case (a, b) =>
        a == b

  /**
    * The dataset type of a column that two files store as `a` (the union so far) and `b` (the file
    * at `bFile`), as Spark's `mergeSchema` and DuckDB's `union_by_name` combine them:
    *
    *   - Primitives must match or widen losslessly (see [[widensTo]]).
    *   - Struct fields union by name in first-seen order. A field some files lack becomes OPTIONAL
    *     and reads as null there (or as an empty list when it is REPEATED).
    *   - Lists merge their elements and maps their keys and values, matched by position, so the
    *     names writers give the wrapper groups (`list.element`, `list.item`, `bag.array`) don't
    *     matter. When files mix the 3-level and legacy 2-level list layouts, the dataset uses the
    *     3-level one.
    *   - REQUIRED and OPTIONAL merge to OPTIONAL; REPEATED must match.
    *
    * Throws when the types don't merge, naming the nested path and the file behind each type.
    */
  private[parquet] def mergeField(
      a: ParquetField,
      b: ParquetField,
      path: String,
      bFile: String,
      origins: scala.collection.mutable.Map[String, String]
  ): ParquetField =
    def fail(): Nothing =
      // The longest recorded prefix of the path names the file behind a's type
      val aFile = Iterator
        .iterate(path)(p => p.substring(0, math.max(p.lastIndexOf('.'), 0)))
        .takeWhile(_.nonEmpty)
        .flatMap(origins.get)
        .nextOption()
        .getOrElse("an earlier file")
      def desc(f: ParquetField): String =
        if f.isRepeated then
          s"repeated ${describe(f)}"
        else
          describe(f)
      throw ParquetException(
        s"Column ${path} has different types in ${aFile} (${desc(a)}) and ${bFile} (${desc(b)})"
      )

    if a.isRepeated != b.isRepeated then
      fail()
    val rep =
      if a.isRepeated then
        Repetition.REPEATED
      else if a.isOptional || b.isOptional then
        Repetition.OPTIONAL
      else
        Repetition.REQUIRED
    def merge(x: ParquetField, y: ParquetField, name: String): ParquetField = mergeField(
      x,
      y,
      s"${path}.${name}",
      bFile,
      origins
    )

    (a, b) match
      case (p: ParquetField.Primitive, q: ParquetField.Primitive) =>
        if p.physicalType == q.physicalType && p.typeLength == q.typeLength &&
          p.logicalType == q.logicalType
        then
          p.copy(repetition = rep)
        else
          (numKind(p), numKind(q)) match
            case (Some(x), Some(y)) if widensTo(x, y) =>
              origins(path) = bFile
              q.copy(name = p.name, repetition = rep)
            case (Some(x), Some(y)) if widensTo(y, x) =>
              p.copy(repetition = rep)
            case _ =>
              fail()
      case (g: ParquetField.Group, h: ParquetField.Group)
          if Assembly.isList(g) && Assembly.isList(h) =>
        val (ga, gb) = (g.children(0), h.children(0))
        val (a3, b3) = (isThreeLevel(g), isThreeLevel(h))
        val ea       = listElement(g)
        val eb       = listElement(h)
        val wide     =
          if a3 == b3 then
            merge(ea, eb, "element")
          else
            // A 2-level element is the repeated group itself: a required element in 3-level terms
            merge(asElement(ea, a3), asElement(eb, b3), "element")
        val (frame, e) =
          if a3 || !b3 then
            (g, ea)
          else
            (h.copy(name = g.name), eb)
        val body =
          if a3 || b3 then
            frame
              .children(0)
              .asInstanceOf[ParquetField.Group]
              .copy(children = IndexedSeq(withName(wide, e.name)))
          else
            withName(wide, ga.name)
        frame.copy(repetition = rep, children = IndexedSeq(body))
      case (g: ParquetField.Group, h: ParquetField.Group)
          if Assembly.isMap(g) && Assembly.isMap(h) =>
        val kv = g.children(0).asInstanceOf[ParquetField.Group]
        val lv = h.children(0).asInstanceOf[ParquetField.Group]
        val k  = merge(kv.children(0), lv.children(0), "key")
        val v  = merge(kv.children(1), lv.children(1), "value")
        g.copy(
          repetition = rep,
          children = IndexedSeq(
            kv.copy(children =
              IndexedSeq(withName(k, kv.children(0).name), withName(v, kv.children(1).name))
            )
          )
        )
      case (g: ParquetField.Group, h: ParquetField.Group)
          if !isCollection(g) && !isCollection(h) && g.logicalType == h.logicalType =>
        val byName = h.children.map(c => c.name -> c).toMap
        val shared = g
          .children
          .map { c =>
            byName.get(c.name) match
              case Some(d) =>
                merge(c, d, c.name)
              case None =>
                missingField(c)
          }
        val existing = g.children.map(_.name).toSet
        val added    = h
          .children
          .filterNot(c => existing.contains(c.name))
          .map { c =>
            origins(s"${path}.${c.name}") = bFile
            missingField(c)
          }
        g.copy(repetition = rep, children = shared ++ added)
      case _ =>
        fail()
    end match

  end mergeField

  private def isCollection(g: ParquetField.Group): Boolean = Assembly.isList(g) || Assembly.isMap(g)

  private def isThreeLevel(g: ParquetField.Group): Boolean = Assembly.isThreeLevelList(
    g,
    g.children(0)
  )

  /** The element of a LIST group: the child of the repeated group, or the repeated group itself */
  private def listElement(g: ParquetField.Group): ParquetField =
    if isThreeLevel(g) then
      g.children(0).asInstanceOf[ParquetField.Group].children(0)
    else
      g.children(0)

  private def asElement(e: ParquetField, threeLevel: Boolean): ParquetField =
    if threeLevel then
      e
    else
      withRepetition(e, Repetition.REQUIRED)

  /** A struct field that some files lack: null there, or an empty list when it is REPEATED */
  private def missingField(f: ParquetField): ParquetField =
    if f.isRepeated then
      f
    else
      withRepetition(f, Repetition.OPTIONAL)

  private def withName(f: ParquetField, name: String): ParquetField =
    f match
      case p: ParquetField.Primitive =>
        p.copy(name = name)
      case g: ParquetField.Group =>
        g.copy(name = name)

  /**
    * A file's vector in the dataset's type `f`, mirroring how the reader assembles a field: a
    * repeated field is a list of its values. Leaves widen, struct children follow the dataset's
    * fields by name, with nulls for fields the file lacks. A vector whose field already has the
    * dataset's shape passes through.
    */
  private def conformField(v: ColumnVector, f: ParquetField): ColumnVector =
    if sameShape(v.field, f) then
      v
    else
      v match
        case l: ListColumn if f.isRepeated =>
          ListColumn(f, l.offsets, conformValue(l.elements, f), l.nulls)
        case _ =>
          conformValue(v, f)

  /** [[conformField]] for a value, ignoring the field's own repetition */
  private def conformValue(v: ColumnVector, f: ParquetField): ColumnVector =
    (v, f) match
      case (l: ListColumn, g: ParquetField.Group) if Assembly.isList(g) =>
        val elements =
          if isThreeLevel(g) then
            conformField(l.elements, listElement(g))
          else
            conformValue(l.elements, listElement(g))
        ListColumn(g, l.offsets, elements, l.nulls)
      case (m: MapColumn, g: ParquetField.Group) if Assembly.isMap(g) =>
        val kv = g.children(0).asInstanceOf[ParquetField.Group]
        MapColumn(
          g,
          m.offsets,
          conformField(m.keys, kv.children(0)),
          conformField(m.values, kv.children(1)),
          m.nulls
        )
      case (s: StructColumn, g: ParquetField.Group) =>
        StructColumn(
          g,
          s.size,
          g.children
            .map { c =>
              s.child(c.name) match
                case Some(cv) =>
                  conformField(cv, c)
                case None =>
                  nullVector(c, s.size)
            },
          s.nulls
        )
      case _ =>
        widenVector(v, f)

  /** A primitive vector in the dataset's (possibly wider) type */
  private def widenVector(v: ColumnVector, f: ParquetField): ColumnVector =
    (v.field, f) match
      case (p: ParquetField.Primitive, q: ParquetField.Primitive)
          if p.physicalType != q.physicalType || p.logicalType != q.logicalType =>
        v match
          case c: IntColumn if q.physicalType == PhysicalType.INT32 =>
            // INT(8) or INT(16) under a wider INT32: same values, the dataset's field
            IntColumn(q, c.values, c.nulls)
          case c: IntColumn if q.physicalType == PhysicalType.INT64 =>
            LongColumn(q, c.values.map(_.toLong), c.nulls)
          case c: IntColumn if q.physicalType == PhysicalType.DOUBLE =>
            DoubleColumn(q, c.values.map(_.toDouble), c.nulls)
          case c: FloatColumn if q.physicalType == PhysicalType.DOUBLE =>
            DoubleColumn(q, c.values.map(_.toDouble), c.nulls)
          case _ =>
            v
      case _ =>
        v

  // ---- Schemas ----

  /** Same type and nested structure, ignoring the field's own optionality and positions */
  private def sameShape(a: ParquetField, b: ParquetField): Boolean =
    (a, b) match
      case (p: ParquetField.Primitive, q: ParquetField.Primitive) =>
        p.physicalType == q.physicalType && p.typeLength == q.typeLength &&
        p.logicalType == q.logicalType
      case (g: ParquetField.Group, h: ParquetField.Group) =>
        g.logicalType == h.logicalType && g.children.size == h.children.size &&
        g.children
          .zip(h.children)
          .forall((x, y) => x.name == y.name && x.repetition == y.repetition && sameShape(x, y))
      case _ =>
        false

  private def describe(f: ParquetField): String =
    f match
      case p: ParquetField.Primitive =>
        s"${p.physicalType}${p.logicalType.map(t => s" ${t}").getOrElse("")}"
      case g: ParquetField.Group =>
        s"group${g.logicalType.map(t => s" ${t}").getOrElse("")} of ${g
            .children
            .map(_.name)
            .mkString(", ")}"

  private def withRepetition(f: ParquetField, r: Repetition): ParquetField =
    f match
      case p: ParquetField.Primitive =>
        p.copy(repetition = r)
      case g: ParquetField.Group =>
        g.copy(repetition = r)

  // ---- Vectors ----

  /** The batch without the rows whose positions, `start` for its first row, are in `deleted` */
  private def dropRows(b: ColumnBatch, deleted: Array[Long], start: Long): ColumnBatch =
    val end = start + b.numRows
    var lo  = java.util.Arrays.binarySearch(deleted, start)
    if lo < 0 then
      lo = -lo - 1
    if lo >= deleted.length || deleted(lo) >= end then
      b
    else
      val keep = scala.collection.mutable.ArrayBuilder.ofInt()
      var d    = lo
      var r    = 0
      while r < b.numRows do
        val pos = start + r
        while d < deleted.length && deleted(d) < pos do
          d += 1
        if d >= deleted.length || deleted(d) != pos then
          keep += r
        r += 1
      val rows = keep.result()
      ColumnBatch(rows.length, b.columns.map(ColumnVector.take(_, rows)))

  /** A vector of n copies of a partition value */
  private[parquet] def constantVector(f: ParquetField.Primitive, v: Any, n: Int): ColumnVector =
    if v == null then
      nullVector(f, n)
    else
      v match
        case s: String =>
          constantVector(f, s.getBytes(StandardCharsets.UTF_8), n)
        case bytes: Array[Byte] =>
          val data    = new Array[Byte](bytes.length * n)
          val offsets = new Array[Int](n + 1)
          var i       = 0
          while i < n do
            System.arraycopy(bytes, 0, data, i * bytes.length, bytes.length)
            offsets(i + 1) = (i + 1) * bytes.length
            i += 1
          BinaryColumn(f, offsets, data, null)
        case i: Int =>
          IntColumn(f, Array.fill(n)(i), null)
        case l: Long =>
          LongColumn(f, Array.fill(n)(l), null)
        case d: LocalDate =>
          IntColumn(f, Array.fill(n)(d.toEpochDay.toInt), null)
        case other =>
          throw IllegalStateException(s"Unexpected partition value ${other}")

  /** A vector of n nulls shaped like `f`, for a column a file lacks */
  private[parquet] def nullVector(f: ParquetField, n: Int): ColumnVector =
    if f.isRepeated then
      // A repeated field holds an empty list in every row
      ListColumn(f, new Array[Int](n + 1), valueVector(f, 0, null), null)
    else
      valueVector(f, n, nullFlags(n))

  private def nullFlags(n: Int): Array[Boolean] =
    if n == 0 then
      null
    else
      Array.fill(n)(true)

  /** Mirrors how the reader assembles a field, ignoring the field's own repetition */
  private def valueVector(f: ParquetField, n: Int, nulls: Array[Boolean]): ColumnVector =
    f match
      case p: ParquetField.Primitive =>
        p.physicalType match
          case PhysicalType.BOOLEAN =>
            BooleanColumn(p, new Array[Boolean](n), nulls)
          case PhysicalType.INT32 =>
            IntColumn(p, new Array[Int](n), nulls)
          case PhysicalType.INT64 =>
            LongColumn(p, new Array[Long](n), nulls)
          case PhysicalType.FLOAT =>
            FloatColumn(p, new Array[Float](n), nulls)
          case PhysicalType.DOUBLE =>
            DoubleColumn(p, new Array[Double](n), nulls)
          case _ =>
            BinaryColumn(p, new Array[Int](n + 1), Array.emptyByteArray, nulls)
      case g: ParquetField.Group if Assembly.isList(g) =>
        val r        = g.children(0)
        val elements =
          if Assembly.isThreeLevelList(g, r) then
            nullVector(r.asInstanceOf[ParquetField.Group].children(0), 0)
          else
            valueVector(r, 0, null)
        ListColumn(g, new Array[Int](n + 1), elements, nulls)
      case g: ParquetField.Group if Assembly.isMap(g) =>
        val kv = g.children(0).asInstanceOf[ParquetField.Group]
        MapColumn(
          g,
          new Array[Int](n + 1),
          nullVector(kv.children(0), 0),
          nullVector(kv.children(1), 0),
          nulls
        )
      case g: ParquetField.Group =>
        StructColumn(g, n, g.children.map(nullVector(_, n)), nulls)

end ParquetDataset
