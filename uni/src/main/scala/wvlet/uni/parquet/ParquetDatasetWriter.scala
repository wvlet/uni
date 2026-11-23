package wvlet.uni.parquet

import wvlet.uni.io.FileAlreadyExistsException
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.io.WriteMode
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.writer.BatchShredder
import wvlet.uni.parquet.writer.DatasetSort
import wvlet.uni.parquet.writer.FileWriter
import wvlet.uni.parquet.writer.MsgReader
import wvlet.uni.parquet.writer.ValueConverter
import wvlet.uni.parquet.writer.VectorGather
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Writes case classes of type `A` to a partitioned dataset: one folder per `key=value` partition,
  * as [[Parquet.dataset]] reads it. Create one with [[Parquet.newDatasetWriter]], call `write` for
  * each row, and `close()` to finish every file.
  *
  * Each file is published only when it is finished: an object store file is a multipart upload, and
  * a local file is renamed from a hidden `.inprogress` name. A row that does not fit the schema
  * throws [[ParquetException]] and is not written; the writer stays usable. After an I/O error, or
  * when `abort()` is called, every file of this write is discarded, including finished ones, and
  * existing data is left as it was.
  */
class ParquetDatasetWriter[A] private[parquet] (core: DatasetWriterCore, weaver: Weaver[A])
    extends AutoCloseable:
  private val packer = PureScalaBufferPacker()
  private val config = WeaverConfig()

  /** The full schema, including the partition columns */
  def schema: ParquetSchema = core.schema

  /** The root folder or prefix, ending with `/` */
  def root: String = core.rootName

  /** The number of rows written so far */
  def numRows: Long = core.numRows

  /** The files finished so far; after `close()`, every file of the write */
  def writtenFiles: Seq[WrittenFile] = core.writtenFiles

  def write(row: A): Unit =
    packer.clear
    weaver.pack(packer, row, config)
    core.writeRow(packer.writtenArray, packer.writtenSize)

  def writeAll(rows: IterableOnce[A]): Unit = rows.iterator.foreach(write)

  /** Discards every file of this write, including finished ones */
  def abort(): Unit = core.abort()

  /** Finishes every open file, then, in an overwrite mode, deletes the replaced files */
  override def close(): Unit = core.close()

end ParquetDatasetWriter

object ParquetDatasetWriter:
  private[parquet] def create[A](
      target: DatasetTarget,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions,
      weaver: Weaver[A]
  ): ParquetDatasetWriter[A] = ParquetDatasetWriter(
    DatasetWriterCore.create(target, schema, options),
    weaver
  )

/**
  * Writes records (maps, JSON, or value sequences) to a partitioned dataset, as
  * [[ParquetRecordWriter]] does for one file. The schema includes the partition columns. Create one
  * with [[Parquet.newDatasetRecordWriter]]; see [[ParquetDatasetWriter]] for how files are
  * published and discarded.
  */
class ParquetDatasetRecordWriter private[parquet] (core: DatasetWriterCore) extends AutoCloseable:
  private val packer = PureScalaBufferPacker()

  def schema: ParquetSchema          = core.schema
  def root: String                   = core.rootName
  def numRows: Long                  = core.numRows
  def writtenFiles: Seq[WrittenFile] = core.writtenFiles

  def write(record: Any): Unit =
    record match
      case json: String =>
        val bytes = RecordBytes.fromJson(json)
        core.writeRow(bytes, bytes.length)
      case other =>
        packer.clear
        RecordBytes.pack(packer, other)
        core.writeRow(packer.writtenArray, packer.writtenSize)

  def writeAll(records: IterableOnce[Any]): Unit = records.iterator.foreach(write)

  def abort(): Unit          = core.abort()
  override def close(): Unit = core.close()

/**
  * Writes [[ColumnBatch]]es to a partitioned dataset, as [[ParquetBatchWriter]] does for one file.
  * A batch has a vector for every column of the schema, including the partition columns, whose
  * values pick each row's folder: an `IntColumn` for INT32 and DATE, a `LongColumn` for INT64, and
  * a `BinaryColumn` for STRING. A batch whose rows all share one partition is written without a
  * copy.
  */
class ParquetDatasetBatchWriter private[parquet] (core: DatasetWriterCore) extends AutoCloseable:
  def schema: ParquetSchema          = core.schema
  def root: String                   = core.rootName
  def numRows: Long                  = core.numRows
  def writtenFiles: Seq[WrittenFile] = core.writtenFiles

  def write(batch: ColumnBatch): Unit                    = core.writeBatch(batch)
  def writeAll(batches: IterableOnce[ColumnBatch]): Unit = batches.iterator.foreach(write)

  def abort(): Unit          = core.abort()
  override def close(): Unit = core.close()

/** Where a dataset is written */
private[parquet] enum DatasetTarget:
  case Local(root: IOPath)
  case Objects(store: ObjectStore, root: ObjectUri, ownsStore: Boolean)

  def rootName: String =
    this match
      case Local(r) =>
        val p = r.path
        if p.endsWith("/") || p.endsWith("\\") then
          p
        else
          s"${p}${IOPath.separator}"
      case Objects(_, r, _) =>
        r.toString

  def closeStore(): Unit =
    this match
      case Objects(store, _, true) =>
        store.close()
      case _ =>

/**
  * The partition routing, file lifecycle, and write modes shared by the three dataset writers. Rows
  * arrive as MessagePack bytes (case classes and records) or as column batches.
  */
private[parquet] final class DatasetWriterCore private (
    target: DatasetTarget,
    val schema: ParquetSchema,
    options: ParquetDatasetWriteOptions,
    /**
      * For the async writer: the root listing and old `_common_metadata` bytes, fetched with async
      * calls. Files are then built in memory and queued, and I/O is returned as actions to run.
      */
    prefetched: Option[DatasetWriterCore.Prefetched],
    /**
      * For a merge-on-read update: the table version to write for, whose commit the caller makes
      * with the files [[closeUncommitted]] returns
      */
    update: Option[DatasetWriterCore.UpdateBase] = None
) extends wvlet.uni.log.LogSupport:
  import DatasetWriterCore.*

  val rootName: String = target.rootName

  private val async: Boolean = prefetched.isDefined

  private val maxFileSize: Option[Long] =
    if async then
      options.maxFileSize.orElse(Some(AsyncMaxFileSize))
    else
      options.maxFileSize

  /** The root listing before this write, including `_` files */
  private val listedRoot: Seq[ParquetDataset.Listed] = listRoot()

  /** For an Iceberg write: the table this write commits to, and the file schema with field IDs */
  private val iceberg: Option[IcebergWrite.Plan] = planIceberg()

  private val keys: IndexedSeq[String] = iceberg
    .map(_.partitionBy)
    .getOrElse(options.partitionBy.toIndexedSeq)

  private val partitionFields: IndexedSeq[ParquetField.Primitive] = keys.map(partitionField)
  private val kinds: IndexedSeq[Kind]                             = partitionFields.map(kindOf)
  private val converters = partitionFields.map(ValueConverter.forField)

  /**
    * The schema of each file: every column except the partition columns. Iceberg files keep every
    * column and carry the table's field IDs.
    */
  private val fileSchema: ParquetSchema = iceberg.map(_.fileSchema).getOrElse(plainFileSchema)

  private def plainFileSchema: ParquetSchema =
    val rest = schema.fields.map(_.name).filterNot(keys.contains)
    if rest.isEmpty then
      throw IllegalArgumentException(
        s"Every column is a partition column (${keys.mkString(
            ", "
          )}). A Parquet file needs at least one other column"
      )
    if keys.isEmpty then
      schema
    else
      schema.select(rest*)

  // Positions of the partition columns among the top-level fields, for rows given as arrays
  private val partitionPositions: Array[Int] =
    keys.map(k => schema.fields.indexWhere(_.name == k)).toArray

  // The partition index of each top-level field, or -1, for rows whose keys are in schema order
  private val partitionOfField: Array[Int] =
    schema.fields.indices.map(j => partitionPositions.indexOf(j)).toArray

  private val lastPartitionPosition: Int = partitionPositions.maxOption.getOrElse(-1)

  // The UTF-8 names of the top-level fields, matched against map keys without decoding them
  private val fieldNames: Array[Array[Byte]] =
    schema.fields.map(_.name.getBytes(StandardCharsets.UTF_8)).toArray

  // The decoded strings of each STRING partition column, so a repeated value isn't decoded again
  private val interners: IndexedSeq[StringInterner] = keys.map(_ => StringInterner())

  private def partitionField(key: String): ParquetField.Primitive =
    schema.field(key) match
      case Some(p: ParquetField.Primitive) if kindOfField(p).isDefined =>
        p
      case Some(f) =>
        throw IllegalArgumentException(
          s"Partition column ${key} must be a top-level STRING, INT32, INT64, or DATE column"
        )
      case None =>
        throw IllegalArgumentException(
          s"Partition column ${key} is not in the schema. The columns are: ${schema
              .fields
              .map(_.name)
              .mkString(", ")}"
        )

  private lazy val batchShredder = BatchShredder(fileSchema, options.writeOptions.parallelism)

  /** The sort order of `withSortBy`, checked against the file schema */
  private val sort: Option[DatasetSort] =
    if options.sortBy.isEmpty then
      None
    else
      options
        .sortBy
        .find(k => keys.contains(k.column))
        .foreach { k =>
          throw IllegalArgumentException(
            s"Sort column ${k.column} is a partition column, which is constant within a partition"
          )
        }
      Some(DatasetSort(fileSchema, options.sortBy))

  // Rows held for sorting, by partition folder
  private val sortBuffers = mutable.LinkedHashMap.empty[String, SortBuffer]
  private var sortBytes   = 0L
  // Partitions written as more than one sorted run, because the held rows exceeded the budget
  private var earlyRuns = 0

  /** The reader-visible data files under the root before this write; none for an Iceberg table */
  private val existing: Seq[ParquetDataset.Listed] =
    if iceberg.isDefined then
      Seq.empty
    else
      checkExisting()

  /** The partition keys and their types, as `_common_metadata` records them */
  private val partitionTypes: Seq[(String, ParquetType.Primitive)] = keys.zip(kinds.map(typeOfKind))

  private def rootFile(name: String): Option[ParquetDataset.Listed] = listedRoot.find(
    _.segments == Seq(name)
  )

  private val priorMetadata: Option[ParquetDataset.Listed] = rootFile(
    DatasetMetadata.CommonMetadata
  )

  /**
    * Whether to write `_common_metadata`. Append and OverwritePartitions write it only when this
    * writer owns the folders (an empty root, or a matching `_common_metadata` from an earlier
    * write), so it never records types for folders another tool wrote.
    */
  private val writesMetadata: Boolean = checkMetadata()

  /**
    * What runs before any file is written: an overwrite removes the old marker first, so `_SUCCESS`
    * never stands beside mixed files. The sync writer runs it here; the async writer runs it before
    * returning the writer.
    */
  val startActions: Seq[RootAction] =
    if iceberg.isEmpty && (
        options.mode == ParquetWriteMode.Overwrite ||
          options.mode == ParquetWriteMode.OverwritePartitions
      )
    then
      rootFile(DatasetMetadata.SuccessMarker).map(l => deleteAction(l)).toSeq
    else
      Seq.empty

  if !async then
    startActions.foreach(runSync)

  // Finished in-memory files waiting for the async writer to upload them
  private val queue       = mutable.ArrayBuffer.empty[Upload]
  private var queuedBytes = 0L
  // Deletes an async abort leaves for the async writer to run
  private val pendingAbort = mutable.ArrayBuffer.empty[RootAction]

  private val writeId   = newWriteId()
  private var fileCount = 0
  private var rowCount  = 0L
  // Open files by partition folder, least recently written first
  private val open      = mutable.LinkedHashMap.empty[String, OpenFile]
  private val committed = mutable.ArrayBuffer.empty[(WrittenFile, Committed)]
  // The row groups of each finished file by path, for the Iceberg manifest's column metrics
  private val rowGroupsOf = mutable.HashMap.empty[String, IndexedSeq[format.RowGroupMeta]]
  // The snapshot an Iceberg write committed
  private var snapshot = Option.empty[IcebergWrite.Committed]
  // The decoded (key, value) pairs of every partition this write touched
  private val touched = mutable.LinkedHashSet.empty[Seq[(String, String)]]
  private var state   = State.Open

  private val reader    = MsgReader()
  private val rowPacker = PureScalaBufferPacker()
  private val values    = new Array[Any](keys.size)
  // The folder of each partition seen, by its values
  private val dirs         = mutable.HashMap.empty[PartitionValues, String]
  private val probe        = PartitionValues(values)
  private var sinceBudget  = 0
  private val partitionSet = keys.toSet
  private val keyIndexes   = mutable.HashMap.empty[String, Int]
  // Start and end positions of the map entries a row keeps
  private val kept = mutable.ArrayBuffer.empty[Int]

  def numRows: Long                  = rowCount
  def writtenFiles: Seq[WrittenFile] = committed.map(_._1).toSeq

  // ---- Rows ----

  /** Writes one row given as MessagePack bytes (a map, or an array in schema order) */
  def writeRow(bytes: Array[Byte], len: Int): Unit =
    ensureOpen()
    ensureRoom()
    extractPartitions(bytes, len)
    val row    = extracted
    val rowLen = extractedLen
    val dir    = currentDir()
    sort match
      case Some(s) =>
        // Check the row now, so a row that doesn't fit fails here rather than in a later run
        val keys = s.keysOfRow(row, rowLen)
        val buf  = sortBuffer(dir)
        buf.rows += java.util.Arrays.copyOf(row, rowLen)
        buf.keys += keys
        // The row's bytes, its keys (copied bytes for strings and binary), and about 32 bytes for
        // the row's own arrays
        held(buf, rowLen + keyBytes(keys) + 32)
        rowCount += 1
        checkSortBudget()
      case None =>
        writeShredded(dir, row, rowLen)
        rowCount += 1

  /** Writes a row (without partition columns) to its partition's current file */
  private def writeShredded(dir: String, row: Array[Byte], rowLen: Int): Unit =
    val f = fileFor(dir)
    try
      f.writer.write(row, rowLen)
    catch
      case NonFatal(e) =>
        if f.writer.hasFailed then
          abortWith(e)
        throw e
    f.rows += 1
    afterWrite(f, 1)

  // The row bytes `extractPartitions` leaves to shred; fields rather than a tuple, which would box
  private var extracted    = Array.emptyByteArray
  private var extractedLen = 0

  private def extractedRow(bytes: Array[Byte], len: Int): Unit =
    extracted = bytes
    extractedLen = len

  /**
    * Reads the partition values of a row into `values`, and sets the bytes to shred against the
    * file schema. Rows are rebuilt without the partition entries, so the remaining fields keep
    * their schema positions and the shredder's positional fast path. Iceberg files keep the
    * partition columns, so their rows are shredded as given.
    */
  private def extractPartitions(bytes: Array[Byte], len: Int): Unit =
    var i = 0
    while i < values.length do
      values(i) = Unset
      i += 1
    if keys.isEmpty then
      extractedRow(bytes, len)
    else
      reader.reset(bytes, len)
      if reader.isMap then
        val n = reader.readMapHeader()
        kept.clear()
        var j = 0
        // Keys in schema order (as Weaver packs case classes) are matched without decoding them
        var positional = true
        var found      = 0
        // An Iceberg row is kept as it is, so the scan stops once every partition value is read
        while j < n && !(iceberg.isDefined && found == keys.length) do
          val start = reader.pos
          val idx   =
            if positional && j < fieldNames.length && reader.keyEquals(fieldNames(j)) then
              partitionOfField(j)
            else
              if positional then
                positional = false
                reader.pos = start
              keyIndex(reader.readKey())
          if idx >= 0 then
            // A repeated partition key counts once, as the shredder does for columns
            if values(idx) == Unset then
              values(idx) = readPartitionValue(idx)
              found += 1
            else
              reader.skip()
          else
            reader.skip()
            if iceberg.isEmpty then
              kept += start
              kept += reader.pos
          j += 1
        finishValues()
        if iceberg.isDefined then
          extractedRow(bytes, len)
        else
          rowPacker.clear
          rowPacker.packMapHeader(kept.length / 2)
          var k = 0
          while k < kept.length do
            rowPacker.writePayload(bytes, kept(k), kept(k + 1) - kept(k))
            k += 2
          extractedRow(rowPacker.writtenArray, rowPacker.writtenSize)
      else if reader.isArray then
        val n = reader.readArrayHeader()
        if n != schema.fields.size then
          throw ParquetException(
            s"A row of ${n} values does not match the ${schema.fields.size} columns of the schema"
          )
        if iceberg.isDefined then
          // Read the partition values, up to the last partition column, and keep the row as it is
          var j = 0
          while j <= lastPartitionPosition do
            val idx = partitionOfField(j)
            if idx >= 0 then
              values(idx) = readPartitionValue(idx)
            else
              reader.skip()
            j += 1
          finishValues()
          extractedRow(bytes, len)
        else
          rowPacker.clear
          rowPacker.packArrayHeader(n - keys.size)
          var j = 0
          while j < n do
            val idx = partitionOfField(j)
            if idx >= 0 then
              values(idx) = readPartitionValue(idx)
            else
              val start = reader.pos
              reader.skip()
              rowPacker.writePayload(bytes, start, reader.pos - start)
            j += 1
          finishValues()
          extractedRow(rowPacker.writtenArray, rowPacker.writtenSize)
        end if
      else
        throw ParquetException(s"A row must be a map or an array, but got ${reader.valueType}")
      end if
    end if

  end extractPartitions

  /** The partition index of a row key, cached because rows repeat the same keys */
  private def keyIndex(key: String): Int =
    if key == null then
      -1
    else
      keyIndexes.getOrElseUpdate(
        key, {
          val exact = keys.indexOf(key)
          if exact >= 0 then
            exact
          else
            val n = normalize(key)
            keys.indexWhere(k => normalize(k) == n)
        }
      )

  private def readPartitionValue(idx: Int): Any =
    if reader.isNil then
      reader.skipNil()
      null
    else if kinds(idx) == Kind.Str && reader.isString then
      val n = reader.readRawHeader()
      val s = interners(idx).intern(reader.bytes, reader.pos, n)
      reader.pos += n
      s
    else if (kinds(idx) == Kind.I64 || kinds(idx) == Kind.I32) && reader.isLong then
      val start = reader.pos
      val v     = reader.readLong()
      if kinds(idx) == Kind.I64 then
        v
      else if v >= Int.MinValue && v <= Int.MaxValue then
        v.toInt
      else
        // Out of range: the converter reports it
        reader.pos = start
        readConverted(idx)
    else
      readConverted(idx)

  private def readConverted(idx: Int): Any =
    val v = reader.readValue()
    try
      val x = converters(idx)(v)
      kinds(idx) match
        case Kind.Str =>
          String(x.asInstanceOf[Array[Byte]], StandardCharsets.UTF_8)
        case Kind.Date =>
          LocalDate.ofEpochDay(x.asInstanceOf[Int].toLong)
        case _ =>
          x
    catch
      case e: ValueConverter.Mismatch =>
        throw ParquetException(s"Partition column ${keys(idx)}: ${e.getMessage}")

  /** Missing values are null; a null in a required partition column is an error */
  private def finishValues(): Unit =
    var i = 0
    while i < values.length do
      if values(i) == Unset then
        values(i) = null
      if values(i) == null && !partitionFields(i).isOptional then
        throw ParquetException(
          s"Partition column ${keys(i)} is required, but the row has no value for it"
        )
      i += 1

  // ---- Batches ----

  def writeBatch(batch: ColumnBatch): Unit =
    ensureOpen()
    ensureRoom()
    val n       = batch.numRows
    val vectors = partitionFields.map { p =>
      val v = batch
        .columns
        .find(_.name == p.name)
        .getOrElse(throw ParquetException(s"The batch has no partition column ${p.name}"))
      checkPartitionVector(p, v, n)
      v
    }
    val asGiven =
      if iceberg.isDefined then
        batch
      else
        ColumnBatch(n, batch.columns.filterNot(c => partitionSet.contains(c.name)))
    // Check the whole batch before any of it is written
    val checked = batchShredder.check(asGiven)
    // Held batches are joined by position, so a sorted write keeps them in schema order
    val data =
      if sort.isDefined then
        ColumnBatch(n, checked.toIndexedSeq)
      else
        asGiven
    try
      if n > 0 then
        writePartitions(data, checked, vectors)
    finally batchShredder.release()

  end writeBatch

  private def writePartitions(
      data: ColumnBatch,
      checked: Array[ColumnVector],
      vectors: IndexedSeq[ColumnVector]
  ): Unit =
    val n = data.numRows
    if keys.isEmpty then
      writeOrHold("", data, checked)
    else
      val groups = mutable.LinkedHashMap.empty[String, (Array[Any], mutable.ArrayBuilder.ofInt)]
      var i      = 0
      while i < n do
        var k = 0
        while k < keys.size do
          values(k) = vectorValue(vectors(k), i, k)
          k += 1
        finishValues()
        val dir = currentDir()
        groups.getOrElseUpdate(dir, (values.clone(), mutable.ArrayBuilder.ofInt()))._2 += i
        i += 1
      if groups.size == 1 then
        // Every row is in one partition: write the batch as it is
        val (dir, (vs, _)) = groups.head
        setValues(vs)
        writeOrHold(dir, data, checked)
      else if writesInParallel(groups.size) then
        writeInParallel(data, groups)
      else
        groups.foreach { case (dir, (vs, rows)) =>
          setValues(vs)
          val r    = rows.result()
          val part = VectorGather.take(data, r, r.length)
          if sort.isDefined then
            hold(dir, part)
          else
            writeGroup(dir, part, batchShredder.check(part))
        }
    end if

  end writePartitions

  /**
    * Whether the partitions of a batch go to their files in parallel: each to its own open file,
    * with no file limits that would roll a file in the middle of the batch
    */
  private def writesInParallel(groups: Int): Boolean =
    options.writeOptions.parallelism > 1 && sort.isEmpty && groups <= options.maxOpenFiles &&
      options.maxRowsPerFile.isEmpty && maxFileSize.isEmpty

  // A shredder for each parallel partition task, since a shredder caches struct vectors
  private val groupShredders = mutable.ArrayBuffer.empty[BatchShredder]

  /**
    * Writes each partition of a batch to its file in parallel. Files are opened first, in order,
    * then each task gathers its rows and writes them column by column, and the bookkeeping runs
    * after every task is done.
    */
  private def writeInParallel(
      data: ColumnBatch,
      groups: mutable.LinkedHashMap[String, (Array[Any], mutable.ArrayBuilder.ofInt)]
  ): Unit =
    val tasks = groups
      .toIndexedSeq
      .map { case (dir, (vs, rows)) =>
        setValues(vs)
        (fileFor(dir), rows.result())
      }
    while groupShredders.size < tasks.size do
      groupShredders += BatchShredder(fileSchema, options.writeOptions.parallelism)
    try
      WriteParallelism.run(tasks.size, options.writeOptions.parallelism) { t =>
        val (f, rows) = tasks(t)
        val shredder  = groupShredders(t)
        val part      = VectorGather.take(data, rows, rows.length)
        val checked   = shredder.check(part)
        try
          f.writer
            .writeColumns(part.numRows)((writers, a, b) => shredder.write(checked, writers, a, b))
        finally shredder.release()
      }
    catch
      case NonFatal(e) =>
        if tasks.exists(_._1.writer.hasFailed) then
          abortWith(e)
        throw e
    tasks.foreach { (f, rows) =>
      f.rows += rows.length
      rowCount += rows.length
      afterWrite(f, rows.length)
    }

  end writeInParallel

  private def setValues(vs: Array[Any]): Unit = System.arraycopy(vs, 0, values, 0, vs.length)

  private def writeOrHold(dir: String, data: ColumnBatch, checked: Array[ColumnVector]): Unit =
    if sort.isDefined then
      // A copy, so the caller can reuse the batch's arrays
      hold(dir, VectorGather.take(data, Array.range(0, data.numRows), data.numRows))
    else
      writeGroup(dir, data, checked)

  /** Holds a checked batch of one partition, its columns in file schema order, for a sorted run */
  private def hold(dir: String, part: ColumnBatch): Unit =
    val buf = sortBuffer(dir)
    buf.batches += part
    held(buf, DatasetSort.estimatedBytes(part))
    rowCount += part.numRows
    checkSortBudget()

  // ---- Sorted runs ----

  private def sortBuffer(dir: String): SortBuffer = sortBuffers.getOrElseUpdate(
    dir,
    SortBuffer(values.clone())
  )

  private def keyBytes(keys: Array[Any]): Long =
    var total = 0L
    var k     = 0
    while k < keys.length do
      total += (
        keys(k) match
          case b: Array[Byte] =>
            16L + b.length
          case _ =>
            16L
      )
      k += 1
    total

  private def held(buf: SortBuffer, bytes: Long): Unit =
    buf.bytes += bytes
    sortBytes += bytes

  /**
    * Writes the largest held partition as a sorted run while the held rows (plus, for the async
    * writer, the queued files) exceed the memory budget
    */
  private def checkSortBudget(): Unit =
    while sortBuffers.nonEmpty && sortBytes + queuedBytes > options.maxBufferedBytes do
      val (dir, buf) = sortBuffers.maxBy(_._2.bytes)
      earlyRuns += 1
      if earlyRuns == 1 then
        logger.warn(
          s"The rows held for sorting exceed maxBufferedBytes (${options
              .maxBufferedBytes}), so partition ${rootName}/${dir} is written as more than one sorted run. Each file stays sorted, but the runs overlap. Raise withMaxBufferedBytes to keep one run per partition."
        )
      writeRun(dir, buf)

  /** Sorts a partition's held rows and writes them as new files, the last one finished */
  private def writeRun(dir: String, buf: SortBuffer): Unit =
    sortBuffers.remove(dir)
    sortBytes -= buf.bytes
    val s = sort.get
    setValues(buf.values)
    fatal {
      if buf.batches.nonEmpty then
        // Every held batch was checked at write, with its columns in file schema order
        val all    = VectorGather.concat(buf.batches.toSeq)
        val n      = all.numRows
        val order  = s.order(i => s.keysOf(all.columns, i), n)
        val sorted = VectorGather.take(all, order, n)
        try writeGroup(dir, sorted, sorted.columns.toArray, countRows = false)
        finally batchShredder.release()
      else
        val order = s.order(buf.keys(_), buf.rows.size)
        var i     = 0
        while i < order.length do
          val row = buf.rows(order(i))
          writeShredded(dir, row, row.length)
          i += 1
      open.get(dir).foreach(finish)
    }

  /** Writes every held partition as a sorted run */
  private def writeRuns(): Unit = sortBuffers.toList.foreach((dir, buf) => writeRun(dir, buf))

  private def checkPartitionVector(p: ParquetField.Primitive, v: ColumnVector, n: Int): Unit =
    val ok =
      v match
        case _: IntColumn =>
          p.physicalType == PhysicalType.INT32
        case _: LongColumn =>
          p.physicalType == PhysicalType.INT64
        case _: BinaryColumn =>
          p.physicalType == PhysicalType.BYTE_ARRAY
        case _ =>
          false
    if !ok then
      throw ParquetException(
        s"Partition column ${p.name} needs ${expectedVector(p)}, but the batch has ${v
            .getClass
            .getSimpleName}"
      )
    if v.size != n then
      throw ParquetException(
        s"Partition column ${p.name} has ${v.size} values, but the batch has ${n} rows"
      )

  private def expectedVector(p: ParquetField.Primitive): String =
    p.physicalType match
      case PhysicalType.INT32 =>
        "an IntColumn"
      case PhysicalType.INT64 =>
        "a LongColumn"
      case _ =>
        "a BinaryColumn"

  private def vectorValue(v: ColumnVector, i: Int, k: Int): Any =
    val kind = kinds(k)
    if v.isNull(i) then
      null
    else
      v match
        case c: IntColumn if kind == Kind.Date =>
          LocalDate.ofEpochDay(c.values(i).toLong)
        case c: IntColumn =>
          c.values(i)
        case c: LongColumn =>
          c.values(i)
        case c: BinaryColumn =>
          interners(k).intern(c.data, c.offsets(i), c.length(i))
        case other =>
          throw ParquetException(s"Unexpected partition vector ${other.getClass.getSimpleName}")

  /**
    * Writes the rows of one partition, starting new files at the file limits. `checked` holds the
    * batch's vectors from `BatchShredder.check`, and each file gets a row range of them.
    */
  private def writeGroup(
      dir: String,
      data: ColumnBatch,
      checked: Array[ColumnVector],
      countRows: Boolean = true
  ): Unit =
    var from = 0
    while from < data.numRows do
      val f  = fileFor(dir)
      var to = data.numRows
      options
        .maxRowsPerFile
        .foreach { m =>
          to = math.min(to.toLong, from + math.max(1L, m - f.rows)).toInt
        }
      if maxFileSize.isDefined then
        to = math.min(to, from + SizeCheckRows)
      val base = from
      try
        f.writer
          .writeColumns(to - from)((writers, a, b) =>
            batchShredder.write(checked, writers, base + a, base + b)
          )
      catch
        case NonFatal(e) =>
          if f.writer.hasFailed then
            abortWith(e)
          throw e
      f.rows += to - from
      if countRows then
        rowCount += to - from
      afterWrite(f, to - from)
      from = to
    end while

  end writeGroup

  // ---- Files ----

  /** The folder of the partition in `values`, such as `dt=2026-11-20/country=JP/` */
  private def currentDir(): String =
    if keys.isEmpty then
      ""
    else
      dirs.get(probe) match
        case Some(dir) =>
          dir
        case None =>
          val sb = StringBuilder()
          var i  = 0
          while i < keys.size do
            sb.append(escape(keys(i)))
            sb.append('=')
            sb.append(escape(folderValue(values(i), iceberg.isDefined)))
            sb.append('/')
            i += 1
          val dir = sb.result()
          // Partitions are few in practice; a write over very many keeps the cache bounded
          if dirs.size >= MaxCachedDirs then
            dirs.clear()
          dirs.put(PartitionValues(values.clone()), dir)
          dir

  private def fileFor(dir: String): OpenFile =
    open.get(dir) match
      case Some(f) =>
        // Keep the most recently written file last, so the head is the least recently used
        if open.last._1 != dir then
          open.remove(dir)
          open.put(dir, f)
        f
      case None =>
        if open.size >= options.maxOpenFiles then
          finish(open.head._2)
        val f = fatal(openFile(dir))
        open.put(dir, f)
        f

  private def openFile(partitionDir: String): OpenFile =
    val name = s"part-${writeId}-${pad(fileCount)}.parquet"
    fileCount += 1
    val partitions = VectorMap.from(
      keys.indices.map(i => keys(i) -> typedValue(values(i), iceberg.isDefined))
    )
    touched += keys.indices.map(i => keys(i) -> folderValue(values(i), iceberg.isDefined))
    // Iceberg data files go under data/; the open-file map still keys by the partition folder
    val dir =
      if iceberg.isDefined then
        s"${IcebergWrite.DataDir}/${partitionDir}"
      else
        partitionDir
    target match
      case DatasetTarget.Local(root) =>
        val folder =
          if dir.isEmpty then
            root
          else
            root.resolve(dir.stripSuffix("/"))
        FileSystem.createDirectoryIfNotExists(folder)
        val tmp = folder.resolve(s".${name}.inprogress")
        val fin = folder.resolve(name)
        val w   =
          try
            FileWriter(
              fileSchema,
              options.writeOptions,
              () => FileSystem.writeStream(tmp),
              sortingColumns
            )
          catch
            case NonFatal(e) =>
              FileSystem.deleteIfExists(tmp)
              throw e
        OpenFile(partitionDir, partitions, w, Committed.LocalFile(fin), Some(tmp))
      case DatasetTarget.Objects(store, root, _) =>
        val uri = root.resolve(s"${dir}${name}")
        if async then
          // The async writer uploads the whole file with one put once it is finished
          val buf = java.io.ByteArrayOutputStream()
          val w   = FileWriter(fileSchema, options.writeOptions, () => buf, sortingColumns)
          OpenFile(partitionDir, partitions, w, Committed.Remote(store, uri), None, Some(buf))
        else
          val w = FileWriter(
            fileSchema,
            options.writeOptions,
            () => store.newOutputStream(uri),
            sortingColumns
          )
          OpenFile(partitionDir, partitions, w, Committed.Remote(store, uri), None)
    end match

  end openFile

  private def sortingColumns: IndexedSeq[format.SortingColumn] = sort
    .map(_.sortingColumns)
    .getOrElse(IndexedSeq.empty)

  private def afterWrite(f: OpenFile, rows: Int): Unit =
    if options.maxRowsPerFile.exists(f.rows >= _) || maxFileSize.exists(f.writer.estimatedSize >= _)
    then
      finish(f)
    else
      sinceBudget += rows
      if sinceBudget >= BudgetCheckRows then
        sinceBudget = 0
        checkBudget()

  /** Writes out the largest buffered row groups early while the buffers exceed the budget */
  private def checkBudget(): Unit =
    if async then
      checkMemory()
    else
      checkBuffers()

  /** Async: whole files stay in memory, so the largest open file is finished and queued */
  private def checkMemory(): Unit =
    var total = queuedBytes + open.valuesIterator.map(_.writer.estimatedSize).sum
    while total > options.maxBufferedBytes && open.nonEmpty do
      val largest = open.valuesIterator.maxBy(_.writer.estimatedSize)
      total -= largest.writer.estimatedSize
      finish(largest)
      total += queue.lastOption.filter(_.target eq largest.target).map(_.bytes.length).getOrElse(0)

  private def checkBuffers(): Unit =
    var total = open.valuesIterator.map(_.writer.bufferedSize).sum
    while total > options.maxBufferedBytes do
      val largest = open.valuesIterator.maxBy(_.writer.bufferedSize)
      val size    = largest.writer.bufferedSize
      if size == 0 then
        total = 0
      else
        fatal(largest.writer.flushBuffered())
        total -= size - largest.writer.bufferedSize

  /** Finishes and publishes a file; a file that got no rows is discarded instead */
  private def finish(f: OpenFile): Unit =
    if f.rows == 0 then
      fatal(discard(f))
      open.remove(f.dir)
    else
      fatal {
        f.writer.close()
        f.tmp
          .foreach { tmp =>
            f.target match
              case Committed.LocalFile(fin) =>
                FileSystem.move(tmp, fin)
              case _ =>
          }
      }
      // Only now, so an abort after a failed close still discards the file
      open.remove(f.dir)
      val written = WrittenFile(f.target.location, f.partitions, f.rows, f.writer.bytesWritten)
      if iceberg.isDefined then
        rowGroupsOf(written.path) = f.writer.rowGroupMetas
      f.memory match
        case Some(buf) =>
          val bytes = buf.toByteArray
          queue += Upload(written, bytes, f.target)
          queuedBytes += bytes.length
        case None =>
          committed += written -> f.target

  private def discard(f: OpenFile): Unit =
    f.writer.abort()
    f.tmp.foreach(FileSystem.deleteIfExists)

  // ---- Lifecycle ----

  private def ensureRoom(): Unit =
    if async && queuedBytes > options.maxBufferedBytes then
      throw ParquetException(
        s"${queue
            .size} finished files (${queuedBytes} bytes) are waiting to upload, over maxBufferedBytes (${options
            .maxBufferedBytes}). Call flush() to upload them, or use writeAll"
      )

  private def ensureOpen(): Unit =
    state match
      case State.Open   =>
      case State.Closed =>
        throw ParquetException("The dataset writer is closed")
      case State.Aborted =>
        throw ParquetException("The dataset writer was aborted")

  /** Runs a step whose failure ruins the write: every file of the write is discarded */
  private def fatal[A](body: => A): A =
    try
      body
    catch
      case NonFatal(e) =>
        abortWith(e)
        throw e

  /**
    * For a merge-on-read update: finishes every file and returns the plan and data files to commit,
    * without committing. On a failed commit the caller deletes [[writtenFiles]].
    */
  def closeUncommitted(): (IcebergWrite.Plan, IndexedSeq[IcebergWrite.DataFile]) =
    ensureOpen()
    writeRuns()
    fatal(open.valuesIterator.toList.foreach(finish))
    state = State.Closed
    val p = iceberg.getOrElse(throw IllegalStateException(s"${rootName} is not an Iceberg write"))
    (p, dataFiles)

  private def dataFiles: IndexedSeq[IcebergWrite.DataFile] =
    committed
      .map(_._1)
      .map(w =>
        IcebergWrite.DataFile(
          w.path,
          w.partitions,
          w.numRows,
          w.size,
          rowGroupsOf.getOrElse(w.path, IndexedSeq.empty)
        )
      )
      .toIndexedSeq

  def close(): Unit =
    if update.isDefined then
      throw IllegalStateException("An update's writer is closed with closeUncommitted")
    if state == State.Open then
      writeRuns()
      fatal(open.valuesIterator.toList.foreach(finish))
      commitState().foreach { c =>
        try
          committedTo(runStepsSync(c.run()))
        catch
          case NonFatal(e) =>
            val outcome =
              if !c.mayHaveCommitted then
                Some(None)
              else
                try
                  Some(runStepsSync(c.verify()))
                catch
                  case NonFatal(v) =>
                    e.addSuppressed(v)
                    None
            outcome match
              case Some(Some(done)) =>
                // The create landed although the call failed
                committedTo(done)
              case Some(None) =>
                failedCommit(c).foreach(a => runCleanup(a, e))
                abortWith(e)
                throw e
              case None =>
                state = State.Closed
                target.closeStore()
                throw uncertainCommit(e)
      }
      state = State.Closed
      try closeActions().foreach(runSync)
      finally target.closeStore()
    end if

  end close

  // ---- Iceberg ----

  /** The commit of an Iceberg write, or none for plain files and for an append without files */
  def commitState(): Option[IcebergWrite.CommitState] = iceberg.flatMap { p =>
    // An overwrite with no rows still commits: it removes the replaced files
    if committed.isEmpty && p.base.isDefined && p.removal == IcebergWrite.Removal.Keep then
      None
    else
      Some(IcebergWrite.CommitState(p, dataFiles))
  }

  /** Records the committed snapshot, so closing writes the version hint after it */
  def committedTo(c: IcebergWrite.Committed): Unit = snapshot = Some(c)

  /** The snapshot an Iceberg write committed */
  def committedSnapshot: Option[IcebergWrite.Committed] = snapshot

  /** Deletes for the manifests of a failed commit; the data files are deleted by the abort */
  def failedCommit(c: IcebergWrite.CommitState): Seq[RootAction] = c
    .writtenFiles
    .map(p => RootAction.Delete(p, s"Failed to delete ${p}"))

  /** A commit that may have landed: its files stay, since a committed snapshot may list them */
  def uncertainCommit(e: Throwable): ParquetException = ParquetException(
    s"The commit to ${rootName} failed, and checking whether it landed failed too, so its files are kept: ${e
        .getMessage}",
    e
  )

  private def runCleanup(a: RootAction, cause: Throwable): Unit =
    try
      runSync(a)
    catch
      case NonFatal(e) =>
        cause.addSuppressed(e)

  /** Runs commit steps with sync calls */
  private def runStepsSync[A](start: IcebergWrite.Step[A]): A = DatasetWriterCore.runStepsSync(
    target,
    start
  )

  /** The Iceberg layout of the root */
  private lazy val layout: IcebergWrite.Layout = DatasetWriterCore.icebergLayout(target)

  /** Decides whether this is an Iceberg write, and binds it to the table (loaded here when sync) */
  private def planIceberg(): Option[IcebergWrite.Plan] =
    if !DatasetWriterCore.isIcebergWrite(listedRoot, options, rootName) then
      if options.replacedFiles.nonEmpty || options.tableProperties.nonEmpty then
        throw ParquetException(
          s"withReplacedFiles and withTableProperties need an Iceberg table, but ${rootName} is not one"
        )
      None
    else
      val base =
        (update, prefetched) match
          case (Some(u), _) =>
            Some(u.table)
          case (None, Some(p)) =>
            p.table.getOrElse(throw IllegalStateException("The async writer didn't load the table"))
          case (None, None) =>
            runStepsSync(IcebergWrite.load(layout))
      if base.isEmpty then
        // An Iceberg table here would hide the plain files from readers
        listedRoot
          .find(l =>
            l.segments.nonEmpty && l.segments.head != IcebergTable.MetadataDir &&
              !l.segments.exists(s => s.startsWith("_") || s.startsWith("."))
          )
          .foreach { l =>
            throw ParquetException(
              s"${rootName} holds plain Parquet files, such as ${l
                  .path}, and no Iceberg table. Write the table to another folder"
            )
          }
      val lineage    = update.exists(_.lineage)
      val lineageIds = Seq(
        RowIdColumn       -> ParquetDataset.RowIdFieldId,
        LastUpdatedColumn -> ParquetDataset.LastUpdatedFieldId
      )
      val rowSchema =
        if lineage then
          schema.select(schema.fields.map(_.name).filterNot(n => lineageIds.exists(_._1 == n))*)
        else
          schema
      val p = IcebergWrite
        .plan(layout, base, rowSchema, options.partitionBy, options.mode, options.replacedFiles)
        .withProperties(options.tableProperties)
      if lineage then
        // The lineage columns follow the table's, with their reserved field IDs
        val extra = lineageIds.map { (name, id) =>
          schema.field(name) match
            case Some(f: ParquetField.Primitive) =>
              f.copy(fieldId = Some(id))
            case _ =>
              throw IllegalStateException(s"An update with row lineage needs a ${name} column")
        }
        Some(
          p.copy(fileSchema =
            ParquetSchema(p.fileSchema.root.copy(children = p.fileSchema.fields ++ extra))
          )
        )
      else
        Some(p)

  // ---- The async writer's steps ----

  /** Async: finished files waiting to upload, removed from the queue */
  def takeUploads(): Seq[Upload] =
    val taken = queue.toSeq
    queue.clear()
    queuedBytes = 0L
    taken

  def hasUploads: Boolean = queue.nonEmpty

  /**
    * Async: whether enough files are queued to upload a batch, or the queue holds half the memory
    * budget, so `writeAll` uploads before a `write` would fail for lack of room
    */
  def uploadsReady(batch: Int): Boolean =
    queue.size >= batch || queuedBytes * 2 >= options.maxBufferedBytes

  /**
    * Async: records an uploaded file, which an abort then deletes. False when the write was aborted
    * while the file was uploading, so the caller deletes it.
    */
  def uploaded(u: Upload): Boolean = synchronized {
    if state == State.Aborted then
      false
    else
      committed += u.file -> u.target
      true
  }

  /** Async: finishes every open file into the queue; the writer stays open until [[closed]] */
  def finishAll(): Unit =
    ensureOpen()
    writeRuns()
    fatal(open.valuesIterator.toList.foreach(finish))

  /** Async: marks the write closed and returns the deletes and root files to run, in order */
  def closed(): Seq[RootAction] =
    state = State.Closed
    closeActions()

  /** Async: aborts the write if it is still open and returns the deletes to run */
  def abortActions(): Seq[RootAction] =
    abortWith(null)
    val actions = pendingAbort.toSeq
    pendingAbort.clear()
    actions

  def closeStore(): Unit = target.closeStore()

  /** Whether files are built in memory for the async writer */
  def isAsync: Boolean = async

  def isAborted: Boolean = state == State.Aborted

  def abort(): Unit =
    if state == State.Open then
      abortWith(null)

  /** Discards open files and deletes finished ones. Cleanup errors are added to `cause`. */
  private def abortWith(cause: Throwable): Unit = synchronized {
    if state == State.Open then
      state = State.Aborted
      def attempt(body: => Unit): Unit =
        try
          body
        catch
          case NonFatal(e) =>
            if cause != null && (e ne cause) then
              cause.addSuppressed(e)
      open.valuesIterator.toList.foreach(f => attempt(discard(f)))
      open.clear()
      sortBuffers.clear()
      sortBytes = 0L
      queue.clear()
      queuedBytes = 0L
      if async then
        // Deletes need async calls, which the async writer runs
        pendingAbort ++=
          committed.map((w, _) => RootAction.Delete(w.path, s"Failed to delete ${w.path}"))
      else
        committed.foreach((_, c) => attempt(c.delete()))
        attempt(target.closeStore())
      committed.clear()
  }

  /**
    * Deletes the files an overwrite replaces. It runs after every new file is published, so a
    * failure here keeps the new files and reports the file it could not delete.
    */
  private def closeActions(): Seq[RootAction] =
    if iceberg.isDefined then
      snapshot
        .toSeq
        .map { c =>
          val (path, bytes) = IcebergWrite.hint(iceberg.get.layout, c)
          RootAction.Put(
            path,
            bytes,
            s"Committed snapshot ${c.snapshotId} to ${rootName} (${c
                .metadataPath}), but writing ${path} failed"
          )
        }
    else
      replacedDeletes() ++ rootFileActions()

  private def replacedDeletes(): Seq[RootAction] =
    val replaced =
      options.mode match
        case ParquetWriteMode.Overwrite =>
          existing
        case ParquetWriteMode.OverwritePartitions =>
          existing.filter(l => touched.contains(partitionsOf(l)))
        case _ =>
          Seq.empty
    replaced.map { l =>
      RootAction.Delete(
        l.path,
        s"The new files under ${rootName} were written, but deleting the replaced file ${l
            .path} failed"
      )
    }

  // ---- Root files ----

  /**
    * Writes `_common_metadata`, then `_SUCCESS`, after every data file is published and the
    * replaced files are deleted. A failure keeps the data files and names the root file.
    */
  private def rootFileActions(): Seq[RootAction] =
    val metadata =
      if writesMetadata then
        Seq(
          putRootAction(
            DatasetMetadata.CommonMetadata,
            DatasetMetadata.toBytes(fileSchema, partitionTypes, options.writeOptions)
          )
        )
      else if options.mode == ParquetWriteMode.Overwrite then
        // A replaced dataset keeps no types recorded for the old one
        priorMetadata.map(l => deleteAction(l)).toSeq
      else
        Seq.empty
    val marker =
      if options.writeSuccessMarker then
        Seq(putRootAction(DatasetMetadata.SuccessMarker, Array.emptyByteArray))
      else
        Seq.empty
    metadata ++ marker

  private def putRootAction(name: String, bytes: Array[Byte]): RootAction =
    val path =
      target match
        case DatasetTarget.Local(root) =>
          root.resolve(name).path
        case DatasetTarget.Objects(_, root, _) =>
          root.resolve(name).toString
    RootAction.Put(
      path,
      bytes,
      s"The data files under ${rootName} were written, but writing ${rootName}${name} failed"
    )

  private def deleteAction(l: ParquetDataset.Listed): RootAction = RootAction.Delete(
    l.path,
    s"Failed to delete ${l.path}"
  )

  /** Runs an action with sync calls, for the sync writers */
  private def runSync(a: RootAction): Unit = DatasetWriterCore.runSync(target, a)

  /** Decides whether to write `_common_metadata`, failing early when it records other types */
  private def checkMetadata(): Boolean =
    // Only Append and OverwritePartitions keep the old file, so only they read it
    lazy val prior = priorMetadata.flatMap { l =>
      val bytes =
        prefetched match
          case Some(p) =>
            p.metadataBytes
          case None =>
            target match
              case DatasetTarget.Local(_) =>
                Some(FileSystem.readBytes(IOPath.parse(l.path)))
              case DatasetTarget.Objects(store, _, _) =>
                Some(store.get(ObjectUri.parse(l.path)))
      bytes.flatMap(b => DatasetMetadata.parse(b, l.path))
    }
    options.mode match
      case ParquetWriteMode.ErrorIfExists | ParquetWriteMode.Overwrite =>
        options.writeCommonMetadata
      case ParquetWriteMode.Append | ParquetWriteMode.OverwritePartitions =>
        prior.foreach { m =>
          if m.partitionTypes.toSeq != partitionTypes then
            throw ParquetException(
              s"${priorMetadata.get.path} records partitions (${describeTypes(
                  m.partitionTypes.toSeq
                )}), but this write has (${describeTypes(partitionTypes)})"
            )
        }
        options.writeCommonMetadata && (
          if priorMetadata.isDefined then
            prior.isDefined
          else
            existing.isEmpty
        )

  end checkMetadata

  private def describeTypes(types: Seq[(String, ParquetType.Primitive)]): String = types
    .map((k, t) => s"${k} ${DatasetMetadata.typeName(t)}")
    .mkString(", ")

  // ---- Modes ----

  private def listRoot(): Seq[ParquetDataset.Listed] =
    val overwrite =
      options.mode == ParquetWriteMode.Overwrite ||
        options.mode == ParquetWriteMode.OverwritePartitions
    if overwrite && isTopLevel then
      throw IllegalArgumentException(
        s"Refusing to overwrite ${rootName}: the root of a bucket or a file system holds more than one dataset. Write to a folder or prefix under it"
      )
    prefetched match
      case Some(p) =>
        p.listing
      case None =>
        target match
          case DatasetTarget.Local(root) =>
            ParquetDataset.listLocal(root)
          case DatasetTarget.Objects(store, root, _) =>
            ParquetDataset.listObjects(store, root)

  private def checkExisting(): Seq[ParquetDataset.Listed] =
    val visible = listedRoot
      .filter(l =>
        l.segments.nonEmpty && !l.segments.exists(s => s.startsWith("_") || s.startsWith("."))
      )
      .sortBy(_.path)
    options.mode match
      case ParquetWriteMode.ErrorIfExists =>
        visible
          .headOption
          .foreach { l =>
            throw ParquetException(
              s"${rootName} already holds data files, such as ${l
                  .path}. Pick a mode with withMode: Append, Overwrite, or OverwritePartitions"
            )
          }
      case ParquetWriteMode.Append | ParquetWriteMode.OverwritePartitions =>
        visible
          .find(l => partitionsOf(l).map(_._1) != keys)
          .foreach { l =>
            throw ParquetException(
              s"${l.path} has partition keys (${partitionsOf(l)
                  .map(_._1)
                  .mkString(", ")}), but this write uses (${keys.mkString(
                  ", "
                )}). Parquet.dataset cannot read mixed partition keys"
            )
          }
      case ParquetWriteMode.Overwrite =>
    visible

  end checkExisting

  private def isTopLevel: Boolean =
    target match
      case DatasetTarget.Local(root) =>
        // A filesystem root: `/`, a drive root `C:\`, or a share root `\\server\share`
        root.isAbsolute && root.parent.isEmpty
      case DatasetTarget.Objects(_, root, _) =>
        root.key.stripSuffix("/").isEmpty

end DatasetWriterCore

private[parquet] object DatasetWriterCore:

  /** Creates the writer core, closing a store the target owns if the options are invalid */
  def create(
      target: DatasetTarget,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions,
      prefetched: Option[Prefetched] = None,
      update: Option[UpdateBase] = None
  ): DatasetWriterCore =
    try
      DatasetWriterCore(target, schema, options, prefetched, update)
    catch
      case e: Throwable =>
        target.closeStore()
        throw e

  private object Unset

  /** The partition folders cached before the cache starts over */
  private val MaxCachedDirs = 4096

  /** Partition values as a hash key; the writer probes with its own reused array */
  private final class PartitionValues(val values: Array[Any]):
    override def hashCode: Int = java.util.Arrays.hashCode(values.asInstanceOf[Array[AnyRef]])
    override def equals(o: Any): Boolean =
      o match
        case p: PartitionValues =>
          java
            .util
            .Arrays
            .equals(values.asInstanceOf[Array[AnyRef]], p.values.asInstanceOf[Array[AnyRef]])
        case _ =>
          false

  /**
    * The strings of one partition column by their UTF-8 bytes, so the rows of a partition share one
    * decoded string. Past [[StringInterner.MaxEntries]] distinct values, strings are decoded as
    * they come.
    */
  private[parquet] final class StringInterner:
    private var keys  = new Array[Array[Byte]](64)
    private var strs  = new Array[String](64)
    private var mask  = 63
    private var count = 0

    private def hashOf(buf: Array[Byte], off: Int, len: Int): Int =
      var h = len
      var i = 0
      while i < len do
        h = 31 * h + buf(off + i)
        i += 1
      h ^ (h >>> 16)

    def intern(buf: Array[Byte], off: Int, len: Int): String =
      var s = hashOf(buf, off, len) & mask
      while keys(s) != null && !same(keys(s), buf, off, len) do
        s = (s + 1) & mask
      if keys(s) != null then
        strs(s)
      else
        val str = String(buf, off, len, StandardCharsets.UTF_8)
        if count < StringInterner.MaxEntries then
          keys(s) = java.util.Arrays.copyOfRange(buf, off, off + len)
          strs(s) = str
          count += 1
          if count * 2 > mask then
            grow()
        str

    private def same(k: Array[Byte], buf: Array[Byte], off: Int, len: Int): Boolean =
      if k.length != len then
        false
      else
        var i = 0
        while i < len && k(i) == buf(off + i) do
          i += 1
        i == len

    private def grow(): Unit =
      val oldKeys = keys
      val oldStrs = strs
      keys = new Array[Array[Byte]](oldKeys.length * 2)
      strs = new Array[String](oldKeys.length * 2)
      mask = keys.length - 1
      var i = 0
      while i < oldKeys.length do
        val k = oldKeys(i)
        if k != null then
          var s = hashOf(k, 0, k.length) & mask
          while keys(s) != null do
            s = (s + 1) & mask
          keys(s) = k
          strs(s) = oldStrs(i)
        i += 1

  end StringInterner

  private[parquet] object StringInterner:
    val MaxEntries = 4096

  /** The async writer's file size cap when none is set, since files are built in memory */
  val AsyncMaxFileSize: Long = 128L * 1024 * 1024

  /**
    * What a merge-on-read update writes for: the table version its rows were read from, and whether
    * the schema ends with the row lineage columns `_row_id` and `_last_updated_sequence_number`
    * (format version 3), which the files keep under Iceberg's reserved field IDs
    */
  case class UpdateBase(table: IcebergWrite.Table, lineage: Boolean)

  /** The row lineage columns an update writes, after the table's columns */
  val RowIdColumn: String       = "_row_id"
  val LastUpdatedColumn: String = "_last_updated_sequence_number"

  /** What the async writer fetched before creating the core */
  case class Prefetched(
      listing: Seq[ParquetDataset.Listed],
      metadataBytes: Option[Array[Byte]],
      /** For an Iceberg write: the loaded table, or `Some(None)` when there is none yet */
      table: Option[Option[IcebergWrite.Table]] = None
  )

  /** Whether the write goes to an Iceberg table, from the options and the root listing */
  def isIcebergWrite(
      listing: Seq[ParquetDataset.Listed],
      options: ParquetDatasetWriteOptions,
      rootName: String
  ): Boolean =
    def isDelta = listing.exists(_.segments.headOption.contains(DeltaLog.LogDir))
    options.tableFormat match
      case ParquetTableFormat.Files =>
        false
      case ParquetTableFormat.Iceberg =>
        true
      case ParquetTableFormat.Delta =>
        throw ParquetException(
          s"Uni doesn't write Delta Lake tables yet. Write ${rootName} with ParquetTableFormat.Iceberg or Files"
        )
      case ParquetTableFormat.Auto =>
        if isDelta then
          throw ParquetException(
            s"${rootName} is a Delta Lake table, which Uni doesn't write yet. Use withTableFormat(ParquetTableFormat.Files) to write plain files next to it anyway"
          )
        IcebergTable.isTable(listing)

  /** Runs commit steps with sync calls */
  def runStepsSync[A](target: DatasetTarget, start: IcebergWrite.Step[A]): A =
    import IcebergWrite.Step
    var step = start
    var done = Option.empty[A]
    while done.isEmpty do
      step match
        case Step.Done(v) =>
          done = Some(v)
        case Step.ListDir(dir, next) =>
          step = next(listDirSync(target, dir))
        case Step.Fetch(path, next) =>
          step = next(
            target match
              case DatasetTarget.Local(_) =>
                FileSystem.readBytes(IOPath.parse(path))
              case DatasetTarget.Objects(store, _, _) =>
                store.get(ObjectUri.parse(path))
          )
        case Step.Put(path, bytes, next) =>
          runSync(target, RootAction.Put(path, bytes, s"Failed to write ${path}"))
          step = next()
        case Step.Create(path, bytes, next) =>
          val created =
            target match
              case DatasetTarget.Local(_) =>
                try
                  FileSystem.writeBytes(IOPath.parse(path), bytes, WriteMode.CreateNew)
                  true
                catch
                  case _: FileAlreadyExistsException =>
                    false
              case DatasetTarget.Objects(store, _, _) =>
                store.putIfAbsent(ObjectUri.parse(path), bytes).isDefined
          step = next(created)
        case Step.Delete(path, next) =>
          runSync(target, RootAction.Delete(path, s"Failed to delete ${path}"))
          step = next()
    end while
    done.get

  end runStepsSync

  def listDirSync(target: DatasetTarget, dir: String): Seq[String] =
    target match
      case DatasetTarget.Local(_) =>
        val d = IOPath.parse(dir)
        if FileSystem.isDirectory(d) then
          FileSystem.list(d).filter(FileSystem.isFile).map(_.fileName)
        else
          Seq.empty
      case DatasetTarget.Objects(store, _, _) =>
        IcebergWrite.namesUnder(
          ObjectUri.parse(dir),
          store.list(ObjectUri.parse(dir), recursive = false)
        )

  /** Runs an action with sync calls, for the sync writers */
  def runSync(target: DatasetTarget, a: RootAction): Unit =
    try
      (a, target) match
        case (RootAction.Put(path, bytes, _), DatasetTarget.Local(root)) =>
          FileSystem.createDirectoryIfNotExists(root)
          FileSystem.writeBytes(IOPath.parse(path), bytes)
        case (RootAction.Put(path, bytes, _), DatasetTarget.Objects(store, _, _)) =>
          store.put(ObjectUri.parse(path), bytes)
        case (RootAction.Delete(path, _), DatasetTarget.Local(_)) =>
          FileSystem.deleteIfExists(IOPath.parse(path))
        case (RootAction.Delete(path, _), DatasetTarget.Objects(store, _, _)) =>
          store.delete(ObjectUri.parse(path))
    catch
      case NonFatal(e) =>
        throw a.failure(e)

  def icebergLayout(target: DatasetTarget): IcebergWrite.Layout =
    target match
      case DatasetTarget.Local(root) =>
        val abs =
          if root.isAbsolute then
            root
          else
            FileSystem.currentDirectory.resolve(root.path)
        IcebergWrite.Layout(target.rootName, objects = false, IcebergWrite.fileUri(abs.path))
      case DatasetTarget.Objects(_, root, _) =>
        IcebergWrite.Layout(target.rootName, objects = true, root.toString.stripSuffix("/"))

  /** A finished in-memory file for the async writer to upload */
  final class Upload(val file: WrittenFile, val bytes: Array[Byte], val target: Committed)

  /**
    * I/O the core plans: the sync writers run it with sync calls, the async writer with async ones
    */
  enum RootAction(val error: String):
    case Put(path: String, bytes: Array[Byte], message: String) extends RootAction(message)
    case Delete(path: String, message: String)                  extends RootAction(message)

    def failure(e: Throwable): ParquetException = ParquetException(s"${error}: ${e.getMessage}", e)

  private val SizeCheckRows   = 1024
  private val BudgetCheckRows = 256

  private enum State:
    case Open,
      Closed,
      Aborted

  private enum Kind:
    case Str,
      I32,
      I64,
      Date

  /** Where a finished file lives, so an abort can delete it */
  enum Committed:
    case LocalFile(path: IOPath)
    case Remote(store: ObjectStore, uri: ObjectUri)

    def location: String =
      this match
        case LocalFile(p) =>
          p.path
        case Remote(_, uri) =>
          uri.toString

    def delete(): Unit =
      this match
        case LocalFile(p) =>
          FileSystem.deleteIfExists(p)
        case Remote(store, uri) =>
          store.delete(uri)

  /** The rows of one partition held for a sorted run: MessagePack rows or column batches */
  private final class SortBuffer(val values: Array[Any]):
    val rows    = mutable.ArrayBuffer.empty[Array[Byte]]
    val keys    = mutable.ArrayBuffer.empty[Array[Any]]
    val batches = mutable.ArrayBuffer.empty[ColumnBatch]
    var bytes   = 0L

  private final class OpenFile(
      val dir: String,
      val partitions: VectorMap[String, Any],
      val writer: FileWriter,
      val target: Committed,
      val tmp: Option[IOPath],
      val memory: Option[java.io.ByteArrayOutputStream] = None
  ):
    var rows: Long = 0L

  private def kindOfField(p: ParquetField.Primitive): Option[Kind] =
    if p.isRepeated then
      None
    else
      (p.physicalType, p.logicalType) match
        case (PhysicalType.INT32, Some(LogicalType.Date)) =>
          Some(Kind.Date)
        case (PhysicalType.INT32, None | Some(LogicalType.Integer(32, true))) =>
          Some(Kind.I32)
        case (PhysicalType.INT64, None | Some(LogicalType.Integer(64, true))) =>
          Some(Kind.I64)
        case (PhysicalType.BYTE_ARRAY, Some(LogicalType.String | LogicalType.Enum)) =>
          Some(Kind.Str)
        case _ =>
          None

  private def kindOf(p: ParquetField.Primitive): Kind = kindOfField(p).get

  private def typeOfKind(k: Kind): ParquetType.Primitive =
    k match
      case Kind.Str =>
        ParquetType.STRING
      case Kind.I32 =>
        ParquetType.INT32
      case Kind.I64 =>
        ParquetType.INT64
      case Kind.Date =>
        ParquetType.DATE

  /** The folder text of a value before escaping: null and "" become the Hive default partition */
  private def folderValue(v: Any, keepEmpty: Boolean): String =
    v match
      case null =>
        ParquetDataset.DefaultPartition
      case s: String if s.isEmpty && keepEmpty =>
        // Iceberg keeps an empty string apart from null; the manifest holds the value
        ""
      case s: String if s.isEmpty =>
        ParquetDataset.DefaultPartition
      case other =>
        other.toString

  /** The value as the reader returns it: an empty string reads back as null */
  private def typedValue(v: Any, keepEmpty: Boolean): Any =
    v match
      case s: String if s.isEmpty && !keepEmpty =>
        null
      case other =>
        other

  /** The decoded (key, value) pairs of a listed file's partition folders */
  private def partitionsOf(l: ParquetDataset.Listed): Seq[(String, String)] = l
    .segments
    .init
    .flatMap { s =>
      val eq = s.indexOf('=')
      if eq <= 0 then
        None
      else
        Some(
          ParquetDataset.unescape(s.substring(0, eq)) ->
            ParquetDataset.unescape(s.substring(eq + 1))
        )
    }

  private def normalize(s: String): String = s.toLowerCase.replace("_", "").replace("-", "")

  /**
    * Percent-encodes the characters Hive escapes in partition folder names (control characters,
    * quotes, `#`, `%`, `*`, slashes, `:`, `=`, `?`, brackets, braces, `^`, and DEL), plus `<`, `>`,
    * and `|`, which Windows does not allow in file names. Other characters, including non-ASCII
    * text, are kept.
    */
  private[parquet] def escape(s: String): String =
    var i = 0
    while i < s.length && !needsEscape(s.charAt(i)) do
      i += 1
    if i == s.length then
      s
    else
      val sb = StringBuilder(s.length + 8)
      sb.append(s.substring(0, i))
      while i < s.length do
        val c = s.charAt(i)
        if needsEscape(c) then
          sb.append('%')
          sb.append(Hex(c >> 4))
          sb.append(Hex(c & 0xf))
        else
          sb.append(c)
        i += 1
      sb.result()

  private val Hex = "0123456789ABCDEF".toCharArray

  private def needsEscape(c: Char): Boolean =
    c < 0x20 || c == 0x7f || (
      c match
        case '"' | '#' | '%' | '\'' | '*' | '/' | ':' | '=' | '?' | '\\' | '{' | '[' | ']' | '^' |
            '<' | '>' | '|' =>
          true
        case _ =>
          false
    )

  /** 8 random hex characters, so files of different writes never collide */
  private def newWriteId(): String =
    val sb = StringBuilder()
    var i  = 0
    while i < 8 do
      sb.append(Hex(scala.util.Random.nextInt(16)))
      i += 1
    sb.result().toLowerCase

  private def pad(n: Int): String =
    val s = n.toString
    if s.length >= 5 then
      s
    else
      s"${"0" * (5 - s.length)}${s}"

end DatasetWriterCore
