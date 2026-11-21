package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.writer.BatchShredder
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
    prefetched: Option[DatasetWriterCore.Prefetched]
):
  import DatasetWriterCore.*

  val rootName: String = target.rootName

  private val async: Boolean = prefetched.isDefined

  private val maxFileSize: Option[Long] =
    if async then
      options.maxFileSize.orElse(Some(AsyncMaxFileSize))
    else
      options.maxFileSize

  private val keys: IndexedSeq[String]                            = options.partitionBy.toIndexedSeq
  private val partitionFields: IndexedSeq[ParquetField.Primitive] = keys.map(partitionField)
  private val kinds: IndexedSeq[Kind]                             = partitionFields.map(kindOf)
  private val converters = partitionFields.map(ValueConverter.forField)

  /** The schema of each file: every column except the partition columns */
  private val fileSchema: ParquetSchema =
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

  private lazy val batchShredder = BatchShredder(fileSchema)

  /** The root listing before this write, including `_` files */
  private val listedRoot: Seq[ParquetDataset.Listed] = listRoot()

  /** The reader-visible data files under the root before this write */
  private val existing: Seq[ParquetDataset.Listed] = checkExisting()

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
    if options.mode == ParquetWriteMode.Overwrite ||
      options.mode == ParquetWriteMode.OverwritePartitions
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
  // The decoded (key, value) pairs of every partition this write touched
  private val touched = mutable.LinkedHashSet.empty[Seq[(String, String)]]
  private var state   = State.Open

  private val reader       = MsgReader()
  private val rowPacker    = PureScalaBufferPacker()
  private val values       = new Array[Any](keys.size)
  private var lastValues   = Array.empty[Any]
  private var lastDir      = ""
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
    val (row, rowLen) = extractPartitions(bytes, len)
    val dir           = currentDir()
    val f             = fileFor(dir)
    try
      f.writer.write(row, rowLen)
    catch
      case NonFatal(e) =>
        if f.writer.hasFailed then
          abortWith(e)
        throw e
    f.rows += 1
    rowCount += 1
    afterWrite(f, 1)

  /**
    * Reads the partition values of a row into `values`, and returns the bytes to shred against the
    * file schema. Rows are rebuilt without the partition entries, so the remaining fields keep
    * their schema positions and the shredder's positional fast path.
    */
  private def extractPartitions(bytes: Array[Byte], len: Int): (Array[Byte], Int) =
    var i = 0
    while i < values.length do
      values(i) = Unset
      i += 1
    if keys.isEmpty then
      (bytes, len)
    else
      reader.reset(bytes, len)
      if reader.isMap then
        val n = reader.readMapHeader()
        kept.clear()
        var j = 0
        while j < n do
          val start = reader.pos
          val idx   = keyIndex(reader.readKey())
          if idx >= 0 then
            // A repeated partition key counts once, as the shredder does for columns
            if values(idx) == Unset then
              values(idx) = readPartitionValue(idx)
            else
              reader.skip()
          else
            reader.skip()
            kept += start
            kept += reader.pos
          j += 1
        finishValues()
        rowPacker.clear
        rowPacker.packMapHeader(kept.length / 2)
        var k = 0
        while k < kept.length do
          rowPacker.writePayload(bytes, kept(k), kept(k + 1) - kept(k))
          k += 2
        (rowPacker.writtenArray, rowPacker.writtenSize)
      else if reader.isArray then
        val n = reader.readArrayHeader()
        if n != schema.fields.size then
          throw ParquetException(
            s"A row of ${n} values does not match the ${schema.fields.size} columns of the schema"
          )
        rowPacker.clear
        rowPacker.packArrayHeader(n - keys.size)
        var j = 0
        while j < n do
          val idx = partitionPositions.indexOf(j)
          if idx >= 0 then
            values(idx) = readPartitionValue(idx)
          else
            val start = reader.pos
            reader.skip()
            rowPacker.writePayload(bytes, start, reader.pos - start)
          j += 1
        finishValues()
        (rowPacker.writtenArray, rowPacker.writtenSize)
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
    else
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
    val data = ColumnBatch(n, batch.columns.filterNot(c => partitionSet.contains(c.name)))
    // Check the whole batch before any of it is written
    val checked = batchShredder.check(data)
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
      writeGroup("", data, checked)
    else
      val groups = mutable.LinkedHashMap.empty[String, (Array[Any], mutable.ArrayBuilder.ofInt)]
      var i      = 0
      while i < n do
        var k = 0
        while k < keys.size do
          values(k) = vectorValue(vectors(k), i, kinds(k))
          k += 1
        finishValues()
        val dir = currentDir()
        groups.getOrElseUpdate(dir, (values.clone(), mutable.ArrayBuilder.ofInt()))._2 += i
        i += 1
      if groups.size == 1 then
        // Every row is in one partition: write the batch as it is
        val (dir, (vs, _)) = groups.head
        setValues(vs)
        writeGroup(dir, data, checked)
      else
        groups.foreach { case (dir, (vs, rows)) =>
          setValues(vs)
          val r    = rows.result()
          val part = VectorGather.take(data, r, r.length)
          writeGroup(dir, part, batchShredder.check(part))
        }
    end if

  end writePartitions

  private def setValues(vs: Array[Any]): Unit = System.arraycopy(vs, 0, values, 0, vs.length)

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

  private def vectorValue(v: ColumnVector, i: Int, kind: Kind): Any =
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
          c.getString(i)
        case other =>
          throw ParquetException(s"Unexpected partition vector ${other.getClass.getSimpleName}")

  /**
    * Writes the rows of one partition, starting new files at the file limits. `checked` holds the
    * batch's vectors from `BatchShredder.check`, and each file gets a row range of them.
    */
  private def writeGroup(dir: String, data: ColumnBatch, checked: Array[ColumnVector]): Unit =
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
    else if java
        .util
        .Arrays
        .equals(values.asInstanceOf[Array[AnyRef]], lastValues.asInstanceOf[Array[AnyRef]])
    then
      lastDir
    else
      val sb = StringBuilder()
      var i  = 0
      while i < keys.size do
        sb.append(escape(keys(i)))
        sb.append('=')
        sb.append(escape(folderValue(values(i))))
        sb.append('/')
        i += 1
      lastValues = values.clone()
      lastDir = sb.result()
      lastDir

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

  private def openFile(dir: String): OpenFile =
    val name = s"part-${writeId}-${pad(fileCount)}.parquet"
    fileCount += 1
    val partitions = VectorMap.from(keys.indices.map(i => keys(i) -> typedValue(values(i))))
    touched += keys.indices.map(i => keys(i) -> folderValue(values(i)))
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
            FileWriter(fileSchema, options.writeOptions, () => FileSystem.writeStream(tmp))
          catch
            case NonFatal(e) =>
              FileSystem.deleteIfExists(tmp)
              throw e
        OpenFile(dir, partitions, w, Committed.LocalFile(fin), Some(tmp))
      case DatasetTarget.Objects(store, root, _) =>
        val uri = root.resolve(s"${dir}${name}")
        if async then
          // The async writer uploads the whole file with one put once it is finished
          val buf = java.io.ByteArrayOutputStream()
          val w   = FileWriter(fileSchema, options.writeOptions, () => buf)
          OpenFile(dir, partitions, w, Committed.Remote(store, uri), None, Some(buf))
        else
          val w = FileWriter(fileSchema, options.writeOptions, () => store.newOutputStream(uri))
          OpenFile(dir, partitions, w, Committed.Remote(store, uri), None)

  end openFile

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

  def close(): Unit =
    if state == State.Open then
      fatal(open.valuesIterator.toList.foreach(finish))
      state = State.Closed
      try closeActions().foreach(runSync)
      finally target.closeStore()

  // ---- The async writer's steps ----

  /** Async: finished files waiting to upload, removed from the queue */
  def takeUploads(): Seq[Upload] =
    val taken = queue.toSeq
    queue.clear()
    queuedBytes = 0L
    taken

  def hasUploads: Boolean = queue.nonEmpty

  /** Async: records an uploaded file, which an abort then deletes */
  def uploaded(u: Upload): Unit = committed += u.file -> u.target

  /** Async: finishes every open file into the queue; the writer stays open until [[closed]] */
  def finishAll(): Unit =
    ensureOpen()
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
  private def abortWith(cause: Throwable): Unit =
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

  /**
    * Deletes the files an overwrite replaces. It runs after every new file is published, so a
    * failure here keeps the new files and reports the file it could not delete.
    */
  private def closeActions(): Seq[RootAction] = replacedDeletes() ++ rootFileActions()

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
  private def runSync(a: RootAction): Unit =
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
        root.segments.isEmpty || (root.segments.size == 1 && root.segments.head.endsWith(":"))
      case DatasetTarget.Objects(_, root, _) =>
        root.key.stripSuffix("/").isEmpty

end DatasetWriterCore

private[parquet] object DatasetWriterCore:

  /** Creates the writer core, closing a store the target owns if the options are invalid */
  def create(
      target: DatasetTarget,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions,
      prefetched: Option[Prefetched] = None
  ): DatasetWriterCore =
    try
      DatasetWriterCore(target, schema, options, prefetched)
    catch
      case e: Throwable =>
        target.closeStore()
        throw e

  private object Unset

  /** The async writer's file size cap when none is set, since files are built in memory */
  val AsyncMaxFileSize: Long = 128L * 1024 * 1024

  /** What the async writer fetched before creating the core */
  case class Prefetched(listing: Seq[ParquetDataset.Listed], metadataBytes: Option[Array[Byte]])

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
  private def folderValue(v: Any): String =
    v match
      case null =>
        ParquetDataset.DefaultPartition
      case s: String if s.isEmpty =>
        ParquetDataset.DefaultPartition
      case other =>
        other.toString

  /** The value as the reader returns it: an empty string reads back as null */
  private def typedValue(v: Any): Any =
    v match
      case s: String if s.isEmpty =>
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
