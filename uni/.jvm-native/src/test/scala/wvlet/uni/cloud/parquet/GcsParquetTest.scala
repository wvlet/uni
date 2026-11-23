package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.iceberg.IcebergSale
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.storage.ObjectStore
import wvlet.uni.cloud.storage.ObjectUri
import wvlet.uni.cloud.storage.GcsConfig
import wvlet.uni.test.UniTest

/**
  * Writes and reads Parquet in Google Cloud Storage. Runs when `UNI_GCS_TEST_ENDPOINT` is set (CI
  * starts fake-gcs-server for the JVM job); see GcsIntegrationTest in uni.
  */
class GcsParquetTest extends UniTest:
  FileSystemInit.init()

  private val endpoint = Option(System.getenv("UNI_GCS_TEST_ENDPOINT")).filter(_.nonEmpty)

  private lazy val config = GcsConfig()
    .withEndpoint(endpoint.get)
    .noCredentials
    .withChunkSizeBytes(GcsConfig.ChunkUnit)

  private lazy val bucket = Option(System.getenv("UNI_GCS_TEST_BUCKET")).getOrElse("uni-test")
  private lazy val prefix = ObjectUri(bucket, s"parquet-${System.currentTimeMillis()}/", "gs")
  private lazy val store  = ObjectStore.gcs(config)

  private def requireServer(): Unit =
    if endpoint.isEmpty then
      skip("Set UNI_GCS_TEST_ENDPOINT to run GCS integration tests")

  override protected def afterAll: Unit =
    if endpoint.isDefined then
      store.list(prefix).foreach(o => store.delete(o.uri))
      store.close()

  private val rows = (0 until 5000).map(AllTypes.expected)

  test("write Parquet to GCS and read it back") {
    requireServer()
    val uri = prefix.resolve("all_types.parquet")
    val w   = Parquet.newWriter[AllTypes](store.newOutputStream(uri))
    w.writeAll(rows)
    w.close()

    val f = Parquet.openGcs(uri.toString, config)
    try
      f.numRows shouldBe rows.size.toLong
      f.readAs[AllTypes]().toIndexedSeq shouldBe rows
    finally
      f.close()

    val asyncRows = Parquet
      .openGcsAsync(uri.toString, config)
      .flatMap(f => f.readAllAsync[AllTypes]())
    asyncRows.await.size shouldBe rows.size
  }

  test("open a stored file through ByteSource.ofObject") {
    requireServer()
    val uri   = prefix.resolve("zstd.parquet")
    val bytes = FileSystem.readBytes(TestFiles.path("duckdb/all_zstd_V2.parquet"))
    store.put(uri, bytes)
    val f = Parquet.open(ByteSource.ofObject(store, uri))
    try f.numRows shouldBe 3000L
    finally f.close()
  }

  test("an aborted writer leaves no object") {
    requireServer()
    val uri = prefix.resolve("aborted.parquet")
    val w   = Parquet.newWriter[AllTypes](store.newOutputStream(uri))
    w.writeAll(rows.take(10))
    w.abort()
    store.head(uri) shouldBe None
  }

  test("write and append to an Iceberg table") {
    requireServer()
    val root = prefix.resolve("iceberg-sales").toString
    val d    = java.time.LocalDate.of(2026, 11, 21)
    val opts = ParquetDatasetWriteOptions
      .default
      .withTableFormat(ParquetTableFormat.Iceberg)
      .withPartitionBy("dt")
    Parquet.writeDataset(store, root, Seq(IcebergSale(1, "a", d, Some("JP"))), opts)
    Parquet.writeDataset(
      store,
      root,
      Seq(IcebergSale(2, "b", d, None)),
      opts.withMode(ParquetWriteMode.Append)
    )
    val ds = Parquet.dataset(store, root)
    try ds.readAs[IcebergSale]().map(_.id).toSeq.sorted shouldBe Seq(1L, 2L)
    finally ds.close()
  }

end GcsParquetTest
