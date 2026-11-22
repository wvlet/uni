package wvlet.uni.parquet

import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.parquet.DatasetWriterCore.Committed
import wvlet.uni.parquet.DatasetWriterCore.RootAction
import wvlet.uni.parquet.DatasetWriterCore.Upload
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxParallel
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig

import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/**
  * Runs a dataset writer core with async I/O: finished files, which the core builds in memory, are
  * uploaded with `putAsync`, and deletes and root files run with async calls. A core for a local
  * folder does its own sync I/O, and this only wraps its results in `Rx`.
  */
private[parquet] final class AsyncDatasetDriver(
    val core: DatasetWriterCore,
    store: Option[ObjectStore]
):

  def schema: ParquetSchema          = core.schema
  def root: String                   = core.rootName
  def numRows: Long                  = core.numRows
  def writtenFiles: Seq[WrittenFile] = core.writtenFiles

  /** Uploads the files finished so far */
  def flush(): Rx[Unit] =
    if core.hasUploads then
      guard(uploadAll(core.takeUploads()))
    else
      Rx.single(())

  /**
    * Calls `write` for each element, and uploads a batch of finished files whenever one is ready
    * (or the queue holds half the memory budget). A failure that ruins the write deletes every file
    * it uploaded.
    */
  def writeAll[A](it: Iterator[A], write: A => Unit): Rx[Unit] =
    var failed: Throwable = null
    try
      while it.hasNext && !core.uploadsReady(AsyncDatasetDriver.UploadParallelism) do
        write(it.next())
    catch
      case NonFatal(e) =>
        failed = e
    if failed != null then
      afterFailure(failed)
    else if core.hasUploads then
      flush().flatMap(_ => writeAll(it, write))
    else
      Rx.single(())

  def close(): Rx[Seq[WrittenFile]] =
    if !core.isAsync then
      Rx.single {
        core.close()
        core.writtenFiles
      }
    else
      guard(
        Rx.single(core.finishAll())
          .flatMap(_ => uploadAll(core.takeUploads()))
          .flatMap(_ => commit())
      ).flatMap { _ =>
        // The data files are published: a failure from here on keeps them
        runAll(core.closed()).transform { result =>
          core.closeStore()
          result.get
          core.writtenFiles
        }
      }

  /** Commits an Iceberg snapshot; a failed commit deletes its manifests before `guard` aborts */
  private def commit(): Rx[Unit] =
    core.commitState() match
      case None =>
        Rx.single(())
      case Some(c) =>
        runSteps(c.run())
          .map(core.committedTo)
          .recoverWith { case NonFatal(e) =>
            core
              .failedCommit(c, e)
              .foldLeft(Rx.single(()))((acc, a) =>
                acc.flatMap(_ =>
                  run(a).recover { case NonFatal(d) =>
                    e.addSuppressed(d)
                  }
                )
              )
              .flatMap(_ => Rx.exception[Unit](e))
          }

  /** Runs commit steps with async calls */
  private def runSteps[A](step: IcebergWrite.Step[A]): Rx[A] =
    import IcebergWrite.Step
    val s = store.getOrElse(throw IllegalStateException("The async writer has no object store"))
    step match
      case Step.Done(v) =>
        Rx.single(v)
      case Step.ListDir(dir, next) =>
        val uri = ObjectUri.parse(dir)
        s.listAsync(uri, recursive = false)
          .flatMap(listed => runSteps(next(IcebergWrite.namesUnder(uri, listed))))
      case Step.Fetch(path, next) =>
        s.getAsync(ObjectUri.parse(path)).flatMap(b => runSteps(next(b)))
      case Step.Put(path, bytes, next) =>
        s.putAsync(ObjectUri.parse(path), bytes).flatMap(_ => runSteps(next()))
      case Step.Create(path, bytes, next) =>
        s.putIfAbsentAsync(ObjectUri.parse(path), bytes).flatMap(ok => runSteps(next(ok)))
      case Step.Delete(path, next) =>
        s.deleteAsync(ObjectUri.parse(path)).flatMap(_ => runSteps(next()))

  /** Deletes every file of this write, including uploaded ones */
  def abort(): Rx[Unit] =
    if !core.isAsync then
      Rx.single(core.abort())
    else
      cleanUp(null)

  /** After a failed write call: a failure that aborted the core also deletes uploaded files */
  private def afterFailure(e: Throwable): Rx[Unit] =
    if core.isAborted then
      cleanUp(e).flatMap(_ => Rx.exception[Unit](e))
    else
      Rx.exception(e)

  private def guard[A](rx: Rx[A]): Rx[A] = rx.recoverWith { case NonFatal(e) =>
    cleanUp(e).flatMap(_ => Rx.exception[A](e))
  }

  /** Runs the core's abort deletes; their errors are added to `cause` */
  private def cleanUp(cause: Throwable): Rx[Unit] =
    val deletes                                = core.abortActions()
    def loop(rest: List[RootAction]): Rx[Unit] =
      rest match
        case Nil =>
          Rx.single(())
        case a :: tail =>
          run(a)
            .recover { case NonFatal(e) =>
              if cause != null && (e ne cause) then
                cause.addSuppressed(e)
            }
            .flatMap(_ => loop(tail))
    loop(deletes.toList).transform { result =>
      core.closeStore()
      result.get
    }

  /**
    * Uploads the files a few at a time. Each batch starts after a timer hop, on a fresh stack, so a
    * store whose puts complete at once doesn't grow the stack with every batch: Rx runs a
    * continuation that is ready at once inline. A put that completes after an abort is deleted.
    */
  private def uploadAll(uploads: Seq[Upload]): Rx[Unit] =
    def loop(batches: List[Seq[Upload]]): Rx[Unit] =
      batches match
        case Nil =>
          Rx.single(())
        case batch :: rest =>
          Rx.delay(0, TimeUnit.MILLISECONDS)
            .flatMap(_ => uploadBatch(batch))
            .flatMap(_ => loop(rest))
    loop(uploads.grouped(AsyncDatasetDriver.UploadParallelism).toList)

  private def uploadBatch(uploads: Seq[Upload]): Rx[Unit] = RxParallel
    .parSequenceN(AsyncDatasetDriver.UploadParallelism)(
      uploads.map { u =>
        u.target match
          case Committed.Remote(s, uri) =>
            s.putAsync(uri, u.bytes)
              .flatMap { _ =>
                if core.uploaded(u) then
                  Rx.single(())
                else
                  s.deleteAsync(uri)
                    .recover { case NonFatal(_) =>
                      ()
                    }
              }
          case Committed.LocalFile(p) =>
            Rx.exception[Unit](IllegalStateException(s"A local file is not uploaded: ${p}"))
      }
    )
    .map(_ => ())

  private def runAll(actions: Seq[RootAction]): Rx[Unit] =
    actions.foldLeft(Rx.single(()))((acc, a) => acc.flatMap(_ => run(a)))

  private def run(a: RootAction): Rx[Unit] =
    val s  = store.getOrElse(throw IllegalStateException("The async writer has no object store"))
    val rx =
      a match
        case RootAction.Put(path, bytes, _) =>
          s.putAsync(ObjectUri.parse(path), bytes)
        case RootAction.Delete(path, _) =>
          s.deleteAsync(ObjectUri.parse(path))
    rx.transformFailure { case NonFatal(e) =>
      a.failure(e)
    }

end AsyncDatasetDriver

private[parquet] object AsyncDatasetDriver:

  /** How many finished files upload at once */
  private val UploadParallelism = 4

  /** Reads the current metadata of an Iceberg table with async calls */
  private def loadTable(
      store: ObjectStore,
      layout: IcebergWrite.Layout
  ): Rx[Option[IcebergWrite.Table]] =
    import IcebergWrite.Step
    def loop(step: Step[Option[IcebergWrite.Table]]): Rx[Option[IcebergWrite.Table]] =
      step match
        case Step.Done(v) =>
          Rx.single(v)
        case Step.ListDir(dir, next) =>
          val uri = ObjectUri.parse(dir)
          store
            .listAsync(uri, recursive = false)
            .flatMap(l => loop(next(IcebergWrite.namesUnder(uri, l))))
        case Step.Fetch(path, next) =>
          store.getAsync(ObjectUri.parse(path)).flatMap(b => loop(next(b)))
        case other =>
          Rx.exception(IllegalStateException(s"Loading a table doesn't write: ${other}"))
    loop(IcebergWrite.load(layout))

  /** Lists the root and reads an old `_common_metadata` with async calls, then creates the core */
  def create(
      store: ObjectStore,
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions,
      ownsStore: Boolean
  ): Rx[AsyncDatasetDriver] =
    val uri       = ParquetDataset.objectRoot(root)
    val target    = DatasetTarget.Objects(store, uri, ownsStore)
    val overwrite =
      options.mode == ParquetWriteMode.Overwrite ||
        options.mode == ParquetWriteMode.OverwritePartitions
    val keepsMetadata =
      options.mode == ParquetWriteMode.Append ||
        options.mode == ParquetWriteMode.OverwritePartitions
    val created =
      if overwrite && uri.key.stripSuffix("/").isEmpty then
        // Checked before listing, so a bucket root is never listed in full
        Rx.single(DatasetWriterCore.create(target, schema, options, None))
      else
        ParquetDataset
          .listObjectsAsync(store, uri)
          .flatMap { listing =>
            val metadata = listing.find(_.segments == Seq(DatasetMetadata.CommonMetadata))
            val bytes: Rx[Option[Array[Byte]]] =
              metadata match
                case Some(l) if keepsMetadata =>
                  // A failed read fails the write, as in the sync writer
                  store.getAsync(ObjectUri.parse(l.path)).map(Option(_))
                case _ =>
                  Rx.single(None)
            val table: Rx[Option[Option[IcebergWrite.Table]]] =
              if DatasetWriterCore.isIcebergWrite(listing, options, target.rootName) then
                loadTable(store, DatasetWriterCore.icebergLayout(target)).map(Some(_))
              else
                Rx.single(None)
            bytes.flatMap(b =>
              table.map(t =>
                DatasetWriterCore.create(
                  target,
                  schema,
                  options,
                  Some(DatasetWriterCore.Prefetched(listing, b, t))
                )
              )
            )
          }
    created
      .flatMap { core =>
        val driver = AsyncDatasetDriver(core, Some(store))
        driver.runAll(core.startActions).map(_ => driver)
      }
      .tapOnFailure(_ => target.closeStore())

  end create

  /** A driver over a sync core, for a local folder */
  def local(core: DatasetWriterCore): AsyncDatasetDriver = AsyncDatasetDriver(core, None)

end AsyncDatasetDriver

/**
  * Writes case classes to a partitioned dataset with async I/O, for browsers and other non-blocking
  * code; see [[Parquet.newDatasetWriterAsync]]. `write` encodes a row and queues the files it
  * finishes; `flush()`, `writeAll`, and `close()` upload them. A `write` fails when the queued
  * files exceed `maxBufferedBytes`, until `flush()` uploads them.
  *
  * Each file is built in memory and uploaded with one put, so files are capped at 128 MiB unless
  * `withMaxFileSize` sets another size. If a write call fails in a way that ruins the write, call
  * `abort()` to delete the files already uploaded; `writeAll` and `close` do this themselves.
  */
class ParquetDatasetWriterAsync[A] private[parquet] (driver: AsyncDatasetDriver, weaver: Weaver[A]):
  private val packer = PureScalaBufferPacker()
  private val config = WeaverConfig()

  def schema: ParquetSchema          = driver.schema
  def root: String                   = driver.root
  def numRows: Long                  = driver.numRows
  def writtenFiles: Seq[WrittenFile] = driver.writtenFiles

  def write(row: A): Unit =
    packer.clear
    weaver.pack(packer, row, config)
    driver.core.writeRow(packer.writtenArray, packer.writtenSize)

  def writeAll(rows: IterableOnce[A]): Rx[Unit] = driver.writeAll(rows.iterator, write)

  def flush(): Rx[Unit] = driver.flush()

  /** Uploads the remaining files, deletes replaced files, and writes the root files */
  def close(): Rx[Seq[WrittenFile]] = driver.close()

  def abort(): Rx[Unit] = driver.abort()

end ParquetDatasetWriterAsync

object ParquetDatasetWriterAsync:
  private[parquet] def create[A](
      driver: AsyncDatasetDriver,
      weaver: Weaver[A]
  ): ParquetDatasetWriterAsync[A] = ParquetDatasetWriterAsync(driver, weaver)

/**
  * Writes records (maps, JSON, or value sequences) with async I/O; see
  * [[ParquetDatasetWriterAsync]]
  */
class ParquetDatasetRecordWriterAsync private[parquet] (driver: AsyncDatasetDriver):
  private val sync = ParquetDatasetRecordWriter(driver.core)

  def schema: ParquetSchema          = driver.schema
  def root: String                   = driver.root
  def numRows: Long                  = driver.numRows
  def writtenFiles: Seq[WrittenFile] = driver.writtenFiles

  def write(record: Any): Unit                       = sync.write(record)
  def writeAll(records: IterableOnce[Any]): Rx[Unit] = driver.writeAll(records.iterator, write)
  def flush(): Rx[Unit]                              = driver.flush()
  def close(): Rx[Seq[WrittenFile]]                  = driver.close()
  def abort(): Rx[Unit]                              = driver.abort()

/** Writes [[ColumnBatch]]es with async I/O; see [[ParquetDatasetWriterAsync]] */
class ParquetDatasetBatchWriterAsync private[parquet] (driver: AsyncDatasetDriver):
  def schema: ParquetSchema          = driver.schema
  def root: String                   = driver.root
  def numRows: Long                  = driver.numRows
  def writtenFiles: Seq[WrittenFile] = driver.writtenFiles

  def write(batch: ColumnBatch): Unit = driver.core.writeBatch(batch)

  def writeAll(batches: IterableOnce[ColumnBatch]): Rx[Unit] = driver.writeAll(
    batches.iterator,
    write
  )

  def flush(): Rx[Unit]             = driver.flush()
  def close(): Rx[Seq[WrittenFile]] = driver.close()
  def abort(): Rx[Unit]             = driver.abort()
