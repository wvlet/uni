package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*
import wvlet.uni.cloud.parquet.Parquet.dataset
import wvlet.uni.cloud.parquet.Parquet.datasetTarget
import wvlet.uni.cloud.parquet.Parquet.open
import wvlet.uni.cloud.parquet.Parquet.readAs
import wvlet.uni.cloud.parquet.Parquet.readRows
import wvlet.uni.cloud.parquet.Parquet.write
import wvlet.uni.cloud.parquet.Parquet.writeDataset
import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.cloud.parquet.writer.FileWriter
import wvlet.uni.surface.Surface
import wvlet.uni.rx.Rx
import wvlet.uni.storage.AzureConfig
import wvlet.uni.storage.GcsConfig
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.storage.S3Config
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig

import scala.collection.immutable.VectorMap
import scala.util.control.NonFatal

/**
  * Operations on Apache Iceberg tables (Hadoop tables, as Uni writes them): schema changes, row
  * deletes and updates, compaction, snapshot expiry, rename, and drop. Read a table with
  * [[wvlet.uni.cloud.parquet.Parquet.dataset]] and append with
  * [[wvlet.uni.cloud.parquet.Parquet.writeDataset]].
  *
  * {{{
  * import wvlet.uni.cloud.iceberg.Iceberg
  * import wvlet.uni.cloud.iceberg.IcebergTableChange.*
  *
  * Iceberg.alterTable("warehouse/sales", RenameColumn("item", "product"))
  * Iceberg.expireSnapshots("warehouse/sales", retainLast = 5)
  * }}}
  */
object Iceberg:

  /**
    * Commits schema changes to the Apache Iceberg table at `root` (a Hadoop table, as Uni writes),
    * in order, as one new schema. No data file is rewritten.
    */
  def alterTable(root: String, changes: IcebergTableChange*): Unit =
    withTarget(datasetTarget(root))(alterAt(_, changes))

  /** Commits schema changes to the Iceberg table at `root` in the store */
  def alterTable(store: ObjectStore, root: String, changes: IcebergTableChange*): Unit =
    alterAt(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      changes
    )

  /**
    * Expires every snapshot of the Apache Iceberg table at `root` but the newest `retainLast`, the
    * current one, and those a branch or tag references. The commit is a new metadata version; then
    * the files only expired snapshots reference (manifest lists, manifests, data files) and the
    * metadata files older than the oldest kept snapshot are deleted, so time travel to an expired
    * snapshot stops working. Files outside the table folder are never deleted.
    *
    * @return
    *   the number of expired snapshots (0 when there was nothing to expire)
    */
  def expireSnapshots(root: String, retainLast: Int): Int =
    withTarget(datasetTarget(root))(expireAt(_, retainLast))

  /** Expires snapshots of the Iceberg table at `root` in the store */
  def expireSnapshots(store: ObjectStore, root: String, retainLast: Int): Int =
    expireAt(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      retainLast
    )

  private def expireAt(target: DatasetTarget, retainLast: Int): Int =
    val layout = DatasetWriterCore.icebergLayout(target)
    DatasetWriterCore.runStepsSync(target, IcebergWrite.expire(layout, retainLast)) match
      case None =>
        0
      case Some(e) =>
        finishCommit(target, layout, e, "Expired snapshots of")
        e.snapshots

  /**
    * Deletes the rows that `filter` matches from the Apache Iceberg table at `root` (a Hadoop
    * table, as Uni writes), merge-on-read: one `delete` snapshot adds delete files and keeps the
    * data files. A format version 3 table gets deletion vectors (one per data file, merged with the
    * file's earlier deletes), and a version 2 table position-delete files. Files are skipped by
    * partition values and column statistics, and only the filter's columns are read. When another
    * writer commits first, the rows are found again and the commit retried. Rows of earlier
    * snapshots stay readable by time travel until [[expireSnapshots]] removes them.
    *
    * @return
    *   the number of rows deleted; 0, with nothing committed, when no row matches
    */
  def deleteRows(root: String, filter: ParquetFilter): Long =
    withTarget(datasetTarget(root))(deleteRowsAt(_, filter))

  /** Deletes the rows that `filter` matches from the Iceberg table at `root` in the store */
  def deleteRows(store: ObjectStore, root: String, filter: ParquetFilter): Long = deleteRowsAt(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
    filter
  )

  private def deleteRowsAt(target: DatasetTarget, filter: ParquetFilter): Long =
    commitRowChange(target, "Deleted rows from") { (layout, t, snapshotId) =>
      val found =
        withSnapshot(target, snapshotId) { ds =>
          ds.matchingPositions(filter)
        }
      Option.when(found.nonEmpty) {
        val c = IcebergWrite.RowDeleteCommit(
          layout,
          t,
          found.map((f, added, existing) => IcebergWrite.RowDeletes(f.path, added, existing))
        )
        RowChange(c, Seq.empty, c.deletedRows)
      }
    }

  /**
    * Updates the rows that `filter` matches in the Apache Iceberg table at `root` (a Hadoop table,
    * as Uni writes), merge-on-read: `update` gets each matching row as [[readRows]] gives it and
    * returns its new version, which must fit the table's current schema. One `overwrite` snapshot
    * adds data files with the new rows and delete files for the old ones (deletion vectors in a
    * format version 3 table, position-delete files in version 2), and keeps the other data files.
    * In a version 3 table, an updated row keeps its row ID. When another writer commits first, the
    * rows are read again and `update` is called again, so it must not have side effects.
    *
    * @return
    *   the number of rows updated; 0, with nothing committed, when no row matches
    */
  def updateRows(root: String, filter: ParquetFilter)(
      update: Map[String, Any] => Map[String, Any]
  ): Long = withTarget(datasetTarget(root))(updateRowsAt(_, filter, RowCodec.maps, update))

  /** Updates the rows that `filter` matches in the Iceberg table at `root` in the store */
  def updateRows(store: ObjectStore, root: String, filter: ParquetFilter)(
      update: Map[String, Any] => Map[String, Any]
  ): Long = updateRowsAt(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
    filter,
    RowCodec.maps,
    update
  )

  /**
    * Updates the rows that `filter` matches as `A`, as [[updateRows]] does with maps: each
    * matching row is read into `A` as [[ParquetDataset.readAs]] reads it (columns matched to fields
    * by name), and `update`'s result is written with the table's current schema, as
    * [[writeDataset]] writes `A`. `A`'s fields must cover the table's required columns; columns `A`
    * leaves out are written as null.
    *
    * @return
    *   the number of rows updated; 0, with nothing committed, when no row matches
    */
  def updateRows[A](root: String, filter: ParquetFilter)(update: A => A)(using
      weaver: Weaver[A]
  ): Long = withTarget(datasetTarget(root))(updateRowsAt(_, filter, RowCodec.of(weaver), update))

  /** Updates the rows that `filter` matches as `A` in the Iceberg table at `root` in the store */
  def updateRows[A](store: ObjectStore, root: String, filter: ParquetFilter)(
      update: A => A
  )(using weaver: Weaver[A]): Long = updateRowsAt(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
    filter,
    RowCodec.of(weaver),
    update
  )

  /**
    * How an update reads rows from matching batches and packs new ones for the writer: MessagePack
    * maps, to which the row lineage columns are added
    */
  private trait RowCodec[R]:
    def decode(batch: ColumnBatch): Iterator[R]

    /** Packs a row as a MessagePack map; it must not be null */
    def pack(packer: PureScalaBufferPacker, row: R): Unit

  private object RowCodec:
    val maps: RowCodec[Map[String, Any]] =
      new RowCodec[Map[String, Any]]:
        def decode(batch: ColumnBatch): Iterator[Map[String, Any]] = batch.rows
        // The lineage columns are the update's to write
        def pack(packer: PureScalaBufferPacker, row: Map[String, Any]): Unit = RecordBytes.pack(
          packer,
          row - DatasetWriterCore.RowIdColumn - DatasetWriterCore.LastUpdatedColumn
        )

    def of[A](weaver: Weaver[A]): RowCodec[A] =
      new RowCodec[A]:
        private val config                          = WeaverConfig()
        def decode(batch: ColumnBatch): Iterator[A] = ParquetFile.rows(
          batch,
          weaver,
          ParquetReadOptions.default
        )
        def pack(packer: PureScalaBufferPacker, row: A): Unit = weaver.pack(packer, row, config)

  /**
    * Writes a packed row with the row lineage columns added: the row's old ID, and a null
    * last-updated sequence number so the row takes the commit's
    */
  private def writeWithLineage(
      core: DatasetWriterCore,
      row: PureScalaBufferPacker,
      out: PureScalaBufferPacker,
      reader: writer.MsgReader,
      rowId: Any
  ): Unit =
    val bytes = row.writtenArray
    val size  = row.writtenSize
    reader.reset(bytes, size)
    if !reader.isMap then
      throw IllegalArgumentException("An updated row must pack as a map of column values")
    val entries = reader.readMapHeader()
    val header  = reader.pos
    out.clear
    out.packMapHeader(entries + 2)
    out.writePayload(bytes, header, size - header)
    out.packString(DatasetWriterCore.RowIdColumn)
    rowId match
      case id: Long =>
        out.packLong(id)
      case _ =>
        out.packNil
    out.packString(DatasetWriterCore.LastUpdatedColumn)
    out.packNil
    core.writeRow(out.writtenArray, out.writtenSize)

  end writeWithLineage

  private def updateRowsAt[R](
      target: DatasetTarget,
      filter: ParquetFilter,
      codec: RowCodec[R],
      update: R => R
  ): Long =
    commitRowChange(target, "Updated rows of") { (layout, t, snapshotId) =>
      withSnapshot(target, snapshotId) { ds =>
        val lineage = IcebergWrite.formatVersionOf(t) >= 3
        val schema  =
          if lineage then
            val lineageFields = Seq(
              DatasetWriterCore.RowIdColumn,
              DatasetWriterCore.LastUpdatedColumn
            ).map(n =>
              ParquetField.Primitive(
                n,
                format.Repetition.OPTIONAL,
                format.PhysicalType.INT64,
                0,
                None,
                Seq(n),
                1,
                0,
                0
              )
            )
            // Rebuilt from elements, so the new columns get their paths and levels
            ParquetSchema.fromElements(
              SchemaElements.of(
                ParquetSchema(
                  ds.tableSchema.root.copy(children = ds.tableSchema.fields ++ lineageFields)
                )
              )
            )
          else
            ds.tableSchema
        val core = DatasetWriterCore.create(
          target,
          schema,
          ParquetDatasetWriteOptions
            .default
            .withTableFormat(ParquetTableFormat.Iceberg)
            .withMode(ParquetWriteMode.Append),
          update = Some(DatasetWriterCore.UpdateBase(t, lineage))
        )
        val packer  = PureScalaBufferPacker()
        val withIds = PureScalaBufferPacker()
        val reader  = writer.MsgReader()
        val deletes = IndexedSeq.newBuilder[IcebergWrite.RowDeletes]
        try
          ds.foreachMatchingRows(filter, codec.decode) { m =>
            deletes += IcebergWrite.RowDeletes(m.file.path, m.positions, m.deleted)
            var i = 0
            while i < m.rows.size do
              val updated = update(m.rows(i))
              if updated == null then
                throw IllegalArgumentException("The update returned null for a row")
              packer.clear
              codec.pack(packer, updated)
              if lineage then
                // The row keeps its ID, and takes this commit's sequence number
                writeWithLineage(core, packer, withIds, reader, m.rowIds(i))
              else
                core.writeRow(packer.writtenArray, packer.writtenSize)
              i += 1
          }
        catch
          case e: Throwable =>
            core.abort()
            throw e
        val rows = deletes.result()
        if rows.isEmpty then
          core.abort()
          None
        else
          // A failure while finishing the files discards them
          val (plan, files) = core.closeUncommitted()
          val written       = core.writtenFiles.map(_.path)
          // What the rows depended on, to tell whether a newer version leaves them valid
          val candidates = ds.candidateFiles(filter)
          val states     = rows.map(r => r.path -> ds.readState(r.path)).toMap
          def change(base: IcebergWrite.Table): RowChange =
            val c = IcebergWrite.RowDeleteCommit(
              layout,
              base,
              rows,
              Some(plan.copy(base = Some(base)) -> files)
            )
            RowChange(
              c,
              written,
              c.deletedRows,
              newer => Option.when(reusable(newer))(change(newer))
            )
          def reusable(newer: IcebergWrite.Table): Boolean =
            IcebergWrite.sameWriteShape(t, newer) &&
              IcebergWrite
                .currentSnapshotId(newer)
                .exists { id =>
                  withSnapshot(target, id) { now =>
                    // No file the filter may match was added, and each touched file reads the same
                    val live = now.files.map(_.path).toSet
                    now.candidateFiles(filter).subsetOf(candidates) &&
                    states.forall((path, state) =>
                      live.contains(path) && now.readState(path) == state
                    )
                  }
                }
          Some(change(t))
        end if
      }
    }

  /**
    * A row change to commit: the commit, the data files written for it, the rows it changes, and,
    * given a newer table version that won the race, the same change on that version when its data
    * files still hold, so they are kept rather than written again
    */
  private case class RowChange(
      commit: IcebergWrite.RowDeleteCommit,
      files: Seq[String],
      rows: Long,
      rebase: IcebergWrite.Table => Option[RowChange] = _ => None
  )

  /** Reads the Iceberg table at `target` as of a snapshot */
  private def withSnapshot[A](target: DatasetTarget, snapshotId: Long)(
      body: ParquetDataset => A
  ): A =
    val options = ParquetDatasetOptions.default.withSnapshotId(snapshotId)
    val ds      =
      target match
        case DatasetTarget.Local(root) =>
          dataset(root.path, options)
        case DatasetTarget.Objects(store, root, _) =>
          dataset(store, root.toString, options)
    try body(ds)
    finally ds.close()

  /**
    * Commits a merge-on-read row change, prepared on the table's current version. When another
    * writer commits first, the change moves to the new version if its data files still hold
    * ([[RowChange.rebase]]), with only its delete files planned again; otherwise its files are
    * deleted and it is prepared again. Either way, up to [[IcebergWrite.MaxAttempts]] attempts.
    */
  private def commitRowChange(target: DatasetTarget, done: String)(
      prepare: (IcebergWrite.Layout, IcebergWrite.Table, Long) => Option[RowChange]
  ): Long =
    val layout                     = DatasetWriterCore.icebergLayout(target)
    def load(): IcebergWrite.Table = DatasetWriterCore
      .runStepsSync(target, IcebergWrite.load(layout))
      .getOrElse(throw ParquetException(s"${target.rootName} is not an Iceberg table"))
    def commit(change: RowChange, n: Int): Long =
      val c = change.commit
      // A lost race keeps the data files until the change is known to need them no longer
      runTableCommit(target, layout, n, done)(
        c.run(),
        c.mayHaveCommitted,
        c.verify(),
        c.writtenFiles,
        keptOnRace = change.files
      ) match
        case Some(_) =>
          change.rows
        case None =>
          val (newer, rebased) =
            try
              val t = load()
              (t, change.rebase(t))
            catch
              case e: Throwable =>
                cleanUpFiles(target, change.files)
                throw e
          rebased match
            case Some(next) =>
              commit(next, n + 1)
            case None =>
              cleanUpFiles(target, change.files)
              attempt(newer, n + 1)
    end commit
    // The rows come from the snapshot the commit builds on; if a newer version arrives
    // meanwhile, the commit loses the race
    def attempt(t: IcebergWrite.Table, n: Int): Long =
      IcebergWrite.currentSnapshotId(t).flatMap(id => prepare(layout, t, id)) match
        case None =>
          0L
        case Some(change) =>
          commit(change, n)
    attempt(load(), 1)

  end commitRowChange

  /**
    * Runs a table commit and writes the version hint. A commit that lost a race and must be planned
    * again (`IcebergWrite.LostRace`) deletes its files and returns None, up to the last attempt. An
    * ambiguous failure is checked first, since the commit may have landed.
    */
  private def runTableCommit(
      target: DatasetTarget,
      layout: IcebergWrite.Layout,
      n: Int,
      what: String
  )(
      run: => IcebergWrite.Step[IcebergWrite.Committed],
      mayHaveCommitted: => Boolean,
      verify: => IcebergWrite.Step[Option[IcebergWrite.Committed]],
      written: => Seq[String],
      /** Files that a lost race keeps, for the caller to reuse or delete; deleted on any failure */
      keptOnRace: Seq[String] = Seq.empty
  ): Option[IcebergWrite.Committed] =
    val committed =
      try
        Some(DatasetWriterCore.runStepsSync(target, run))
      catch
        case e: IcebergWrite.LostRace =>
          cleanUpFiles(target, written)
          if n < IcebergWrite.MaxAttempts then
            None
          else
            cleanUpFiles(target, keptOnRace)
            throw ParquetException(
              s"Another writer kept committing to ${target
                  .rootName} first; gave up after ${n} attempts",
              e
            )
        case NonFatal(e) =>
          val verified =
            if mayHaveCommitted then
              try
                DatasetWriterCore.runStepsSync(target, verify)
              catch
                case NonFatal(_) =>
                  None
            else
              None
          if verified.isEmpty then
            cleanUpFiles(target, written ++ keptOnRace)
            throw e
          verified
    committed.foreach { done =>
      val (path, bytes) = IcebergWrite.hint(layout, done)
      DatasetWriterCore.runSync(
        target,
        DatasetWriterCore
          .RootAction
          .Put(
            path,
            bytes,
            s"${what} ${target.rootName} (${done.metadataPath}), but writing ${path} failed"
          )
      )
    }
    committed

  end runTableCommit

  /**
    * Compacts the Apache Iceberg table at `root` (a Hadoop table, as Uni writes): rewrites data
    * files with their deletes applied, merges small files, and removes the delete files that no
    * remaining data file needs (deletion vectors, position deletes, and equality deletes), in one
    * `replace` snapshot. The rows don't change. Files are grouped by partition spec and partition
    * value, and each new file keeps its group's spec and partition, so any partition transform
    * works. A format version 3 table keeps its row lineage: the new files store each row's
    * `_row_id` and `_last_updated_sequence_number`. The new files keep the data sequence number of
    * the snapshot that was read, so equality deletes committed meanwhile still apply to them. When
    * another writer commits first, the commit rebases if that commit touched nothing this one
    * rewrote or removed, and otherwise the compaction plans and rewrites again. Rows of earlier
    * snapshots stay readable by time travel until [[expireSnapshots]] removes the old files.
    *
    * @return
    *   what was committed; all zeros, with nothing committed, when nothing needed a rewrite
    */
  def compactTable(
      root: String,
      options: IcebergCompactionOptions = IcebergCompactionOptions.default
  ): IcebergCompaction = withTarget(datasetTarget(root))(compactAt(_, options))

  /** Compacts the Iceberg table at `root` in the store */
  def compactTable(
      store: ObjectStore,
      root: String,
      options: IcebergCompactionOptions
  ): IcebergCompaction = compactAt(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
    options
  )

  /** Compacts the Iceberg table at `root` in the store with the default options */
  def compactTable(store: ObjectStore, root: String): IcebergCompaction =
    compactTable(store, root, IcebergCompactionOptions.default)

  /** Deletes files a failed commit wrote; one that fails to delete stays unreferenced */
  private def cleanUpFiles(target: DatasetTarget, paths: Seq[String]): Unit = paths.foreach { p =>
    try
      DatasetWriterCore.runSync(
        target,
        DatasetWriterCore.RootAction.Delete(p, s"Failed to delete ${p}")
      )
    catch
      case NonFatal(_) =>
  }

  private def compactAt(
      target: DatasetTarget,
      options: IcebergCompactionOptions
  ): IcebergCompaction =
    val layout                             = DatasetWriterCore.icebergLayout(target)
    def attempt(n: Int): IcebergCompaction =
      val t = DatasetWriterCore
        .runStepsSync(target, IcebergWrite.load(layout))
        .getOrElse(throw ParquetException(s"${target.rootName} is not an Iceberg table"))
      IcebergWrite.currentSnapshotId(t) match
        case None =>
          IcebergCompaction.empty
        case Some(id) =>
          val live   = DatasetWriterCore.runStepsSync(target, IcebergWrite.liveFiles(layout, t))
          val dsOpts = ParquetDatasetOptions.default.withSnapshotId(id)
          val ds     =
            target match
              case DatasetTarget.Local(root) =>
                dataset(root.path, dsOpts)
              case DatasetTarget.Objects(store, root, _) =>
                dataset(store, root.toString, dsOpts)
          val written = scala.collection.mutable.ArrayBuffer.empty[String]
          try
            val selected = options
              .filter
              .map(f => ds.selectFiles(ParquetReadOptions.default.withFilter(f)).map(_.path).toSet)
            val plan = IcebergWrite.planCompaction(live, options, selected)
            if plan.isEmpty then
              IcebergCompaction.empty
            else
              val files =
                try
                  rewriteGroups(target, layout, plan, ds, options, written)
                catch
                  case NonFatal(e) =>
                    cleanUpFiles(target, written.toSeq)
                    throw e
              val c = IcebergWrite.compactionCommit(layout, plan, files)
              runTableCommit(target, layout, n, "Compacted")(
                c.run(),
                c.mayHaveCommitted,
                c.verify(),
                written.toSeq ++ c.writtenFiles
              ) match
                case None =>
                  attempt(n + 1)
                case Some(_) =>
                  IcebergCompaction(
                    plan.rewritten.size,
                    files.size,
                    plan.removedDeletes.size,
                    files.map(f => f.record("record_count").asInstanceOf[Long]).sum
                  )
            end if
          finally
            ds.close()
          end try
      end match
    end attempt
    attempt(1)

  end compactAt

  /**
    * Writes the rows of each group of files to new files of the target size, next to the group's
    * first file, with the row-lineage columns in a format version 3 table. Paths go to `written` as
    * each file opens, so a failure can delete them.
    */
  private[cloud] def rewriteGroups(
      target: DatasetTarget,
      layout: IcebergWrite.Layout,
      plan: IcebergWrite.CompactionPlan,
      ds: ParquetDataset,
      options: IcebergCompactionOptions,
      written: scala.collection.mutable.ArrayBuffer[String]
  ): IndexedSeq[IcebergWrite.CompactedFile] =
    val t        = plan.live.table
    val lineage  = IcebergWrite.formatVersionOf(t) >= 3
    val schema   = IcebergWrite.compactionFileSchema(t, ds.schema)
    val maxSize  = IcebergWrite.targetFileSize(t, options)
    val shredder = writer.BatchShredder(schema, options.writeOptions.parallelism)
    val byPath   = ds.files.map(f => f.path -> f).toMap
    val out      = IndexedSeq.newBuilder[IcebergWrite.CompactedFile]
    val writeId  = IcebergWrite.newWriteId()
    var count    = 0
    plan
      .groups
      .foreach { group =>
        val head   = group.head
        val folder = IcebergWrite
          .folderOf(layout, t, head.uri)
          .getOrElse(IcebergWrite.dataFolder(layout, t))
        var open: Option[(FileWriter, String)] = None
        def finish(): Unit                     =
          open.foreach { (w, path) =>
            w.close()
            val f = IcebergWrite.DataFile(
              path,
              VectorMap.empty,
              w.numRows,
              w.bytesWritten,
              w.rowGroupMetas
            )
            out +=
              IcebergWrite.CompactedFile(
                head,
                IcebergWrite.dataFileRecord(
                  schema,
                  f,
                  IcebergWrite.uriOf(layout, t, path),
                  head.partition
                )
              )
          }
          open = None
        def writer(): FileWriter =
          open match
            case Some((w, _)) =>
              w
            case None =>
              val path = s"${folder}${writeId}-compacted-${count}.parquet"
              count += 1
              written += path
              val w = FileWriter(
                schema,
                options.writeOptions,
                () => DatasetWriterCore.openOutputSync(target, path)
              )
              open = Some((w, path))
              w
        try
          group.foreach { f =>
            val file = byPath.getOrElse(
              f.key,
              throw ParquetException(s"${f.key} is not a data file of ${target.rootName}")
            )
            // Rows keep their lineage: stored values, or the source file's inherited ones
            val inherited =
              Option.when(lineage)(ParquetDataset.Inherited(f.firstRowId, Some(f.sequence)))
            ds.foreachLiveBatch(file, inherited) { (b, rowLineage) =>
              val batch = rowLineage
                .map(l => b.copy(columns = b.columns ++ lineageColumns(schema, l)))
                .getOrElse(b)
              val vectors = shredder.check(batch)
              try
                // In slices, so a file is cut near the target size within a large batch
                var from = 0
                while from < batch.numRows do
                  val to = math.min(batch.numRows, from + SizeCheckRows)
                  val w  = writer()
                  w.writeColumns(to - from)((ws, a, b) =>
                    shredder.write(vectors, ws, from + a, from + b)
                  )
                  if w.estimatedSize >= maxSize then
                    finish()
                  from = to
              finally
                shredder.release()
            }
          }
          finish()
        catch
          case NonFatal(e) =>
            open.foreach((w, _) => w.abort())
            throw e
        end try
      }
    out.result()

  end rewriteGroups

  /** The `_row_id` and `_last_updated_sequence_number` columns of a batch's rows */
  private def lineageColumns(
      schema: ParquetSchema,
      l: ParquetDataset.Lineage
  ): IndexedSeq[ColumnVector] = IndexedSeq(
    ColumnVector.LongColumn(
      schema.field(DatasetWriterCore.RowIdColumn).get,
      l.rowIds,
      l.rowIdNulls
    ),
    ColumnVector.LongColumn(
      schema.field(DatasetWriterCore.LastUpdatedColumn).get,
      l.lastUpdated,
      l.lastUpdatedNulls
    )
  )

  /** The rows a compaction writes between checks of the file size */
  private val SizeCheckRows = 1024

  /**
    * Moves the Apache Iceberg table at local folder `from` to `to`, which must not exist yet, and
    * points its metadata at the new location: the current snapshot's manifests are written again
    * with the new paths, so other engines read the moved table. Only the current snapshot is kept;
    * the files of older snapshots and older metadata versions are deleted. Object stores aren't
    * supported, since they can't move a folder.
    */
  def renameTable(from: String, to: String): Unit =
    if ParquetDataset.isObjectUri(from) || ParquetDataset.isObjectUri(to) then
      throw ParquetException(
        s"Cannot rename ${from} to ${to}: renameTable moves local folders only"
      )
    val source      = IOPath.parse(from)
    val target      = IOPath.parse(to)
    val metadataDir = source.resolve(IcebergTable.MetadataDir)
    // Checked before moving: only a Hadoop table (metadata/vN.metadata.json) can be relocated
    val hadoop =
      FileSystem.isDirectory(metadataDir) &&
        FileSystem.list(metadataDir).exists(p => IcebergTable.HadoopMetadata.matches(p.fileName))
    if !hadoop then
      throw ParquetException(
        s"${from} is not an Iceberg table that Uni writes (metadata/vN.metadata.json)"
      )
    if FileSystem.exists(target) then
      throw ParquetException(s"Cannot rename ${from} to ${to}: ${to} already exists")
    withTarget(datasetTarget(from)) { t =>
      val layout = DatasetWriterCore.icebergLayout(t)
      DatasetWriterCore
        .runStepsSync(t, IcebergWrite.load(layout))
        .flatMap(table =>
          IcebergWrite
            .relocationProblem(table.metadata)
            .orElse(
              DatasetWriterCore.runStepsSync(t, IcebergWrite.relocationFileProblem(layout, table))
            )
        )
        .foreach(p => throw ParquetException(s"Cannot rename ${from} to ${to}: ${p}"))
    }
    FileSystem.move(source, target)
    withTarget(datasetTarget(to)) { t =>
      val layout = DatasetWriterCore.icebergLayout(t)
      val e      =
        try
          DatasetWriterCore.runStepsSync(t, IcebergWrite.relocate(layout))
        catch
          case NonFatal(err) =>
            throw ParquetException(
              s"Moved ${from} to ${to}, but updating its metadata for the new location failed; Uni still reads it there: ${err
                  .getMessage}",
              err
            )
      finishCommit(t, layout, e, "Moved the table to")
    }

  end renameTable

  /** Writes the hint for a committed version, then deletes the files no snapshot references */
  private def finishCommit(
      target: DatasetTarget,
      layout: IcebergWrite.Layout,
      e: IcebergWrite.Expired,
      what: String
  ): Unit =
    val (path, bytes) = IcebergWrite.hint(layout, e.version)
    DatasetWriterCore.runSync(
      target,
      DatasetWriterCore
        .RootAction
        .Put(
          path,
          bytes,
          s"${what} ${target.rootName} (${e.metadataPath}), but writing ${path} failed"
        )
    )
    // Every file is tried: one that fails to delete stays as an unreferenced file
    val failed = e
      .unreferenced
      .flatMap { p =>
        try
          DatasetWriterCore.runSync(
            target,
            DatasetWriterCore.RootAction.Delete(p, s"Failed to delete ${p}")
          )
          None
        catch
          case NonFatal(err) =>
            Some(p -> err)
      }
    failed
      .headOption
      .foreach { (p, err) =>
        throw ParquetException(
          s"${what} ${target.rootName} (${e.metadataPath}), but ${failed
              .size} unreferenced files could not be deleted, such as ${p}: ${err.getMessage}",
          err
        )
      }

  end finishCommit

  /**
    * Deletes the Apache Iceberg table at `root`: its metadata first, so a drop that fails partway
    * leaves no readable table, then its data files and the folder. Fails when `root` is not an
    * Iceberg table.
    */
  def dropTable(root: String): Unit = withTarget(datasetTarget(root))(dropAt)

  /** Deletes the Iceberg table at `root` in the store */
  def dropTable(store: ObjectStore, root: String): Unit = dropAt(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false)
  )

  private def withTarget[A](target: DatasetTarget)(body: DatasetTarget => A): A =
    try body(target)
    finally target.closeStore()

  private def alterAt(target: DatasetTarget, changes: Seq[IcebergTableChange]): Unit =
    require(changes.nonEmpty, "No schema change is given")
    val layout        = DatasetWriterCore.icebergLayout(target)
    val altered       = DatasetWriterCore.runStepsSync(target, IcebergWrite.alter(layout, changes))
    val (path, bytes) = IcebergWrite.hint(layout, altered)
    DatasetWriterCore.runSync(
      target,
      DatasetWriterCore
        .RootAction
        .Put(
          path,
          bytes,
          s"Committed the schema change to ${target.rootName} (${altered
              .metadataPath}), but writing ${path} failed"
        )
    )

  private def dropAt(target: DatasetTarget): Unit =
    val layout   = DatasetWriterCore.icebergLayout(target)
    val metadata = DatasetWriterCore.listDirSync(target, layout.metadataDir)
    if !metadata.exists(IcebergTable.isMetadataFile) then
      throw ParquetException(s"${target.rootName} is not an Iceberg table")
    // The hint and metadata files go first, so readers stop seeing the table
    val first = metadata
      .filter(n => n == IcebergTable.VersionHint || IcebergTable.isMetadataFile(n))
      .sortBy(n =>
        if n == IcebergTable.VersionHint then
          0
        else
          1
      )
    first.foreach { n =>
      DatasetWriterCore.runSync(
        target,
        DatasetWriterCore
          .RootAction
          .Delete(layout.metadata(n), s"Failed to delete ${layout.metadata(n)}")
      )
    }
    target match
      case DatasetTarget.Local(root) =>
        FileSystem.deleteRecursively(root)
      case DatasetTarget.Objects(store, root, _) =>
        store.list(root, recursive = true).foreach(o => store.delete(o.uri))

