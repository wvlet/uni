package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.storage.S3Config
import wvlet.uni.test.UniTest

/**
  * Writes and reads Parquet in an S3-compatible store. Runs when `UNI_S3_TEST_ENDPOINT` is set (CI
  * starts one for the JVM job); see S3IntegrationTest in uni.
  */
class S3ParquetTest extends UniTest:
  FileSystemInit.init()

  private val endpoint = Option(System.getenv("UNI_S3_TEST_ENDPOINT")).filter(_.nonEmpty)

  private lazy val config = S3Config
    .minio(endpoint.get)
    .withCredentials(
      Option(System.getenv("UNI_S3_TEST_ACCESS_KEY")).getOrElse("minioadmin"),
      Option(System.getenv("UNI_S3_TEST_SECRET_KEY")).getOrElse("minioadmin")
    )

  private lazy val bucket = Option(System.getenv("UNI_S3_TEST_BUCKET")).getOrElse("uni-test")
  private lazy val prefix = ObjectUri(bucket, s"parquet-${System.currentTimeMillis()}/")
  private lazy val store  = ObjectStore.s3(config)

  private def requireServer(): Unit =
    if endpoint.isEmpty then
      skip("Set UNI_S3_TEST_ENDPOINT to run S3 integration tests")

  override protected def afterAll: Unit =
    if endpoint.isDefined then
      store.list(prefix).foreach(o => store.delete(o.uri))
      store.close()

  private val rows = (0 until 5000).map(AllTypes.expected)

  test("write Parquet to S3 and read it back") {
    requireServer()
    val uri = prefix.resolve("all_types.parquet")
    val w   = Parquet.newWriter[AllTypes](store.newOutputStream(uri))
    w.writeAll(rows)
    w.close()

    val f = Parquet.openS3(uri.toString, config)
    try
      f.numRows shouldBe rows.size.toLong
      f.readAs[AllTypes]().toIndexedSeq shouldBe rows
    finally
      f.close()

    val asyncRows = Parquet
      .openS3Async(uri.toString, config)
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

  test("read a partitioned dataset under a prefix") {
    requireServer()
    val ds = prefix.resolve("visits/")
    store.put(ds.resolve("day=2026-11-19/part-0.parquet"), Parquet.writeBytes(rows.take(3)))
    store.put(ds.resolve("day=2026-11-20/part-0.parquet"), Parquet.writeBytes(rows.slice(3, 5)))
    store.put(ds.resolve("day=2026-11-20/_SUCCESS"), Array.emptyByteArray)
    // A sibling prefix that `visits` without a slash would also match
    store.put(prefix.resolve("visits_old/part-0.parquet"), Parquet.writeBytes(rows.take(1)))

    val root    = ds.toString.stripSuffix("/")
    val dataset = Parquet.dataset(store, root)
    try
      dataset.files.size shouldBe 2
      val opts = ParquetReadOptions
        .default
        .withRowGroupFilter(ParquetFilter.col("day") === "2026-11-20")
      dataset.readAs[AllTypes](opts).toSeq shouldBe rows.slice(3, 5)
      dataset.readAllRowsAsync().await.map(_("day")) shouldBe
        Seq.fill(3)("2026-11-19") ++ Seq.fill(2)("2026-11-20")
    finally
      dataset.close()
    // The dataset leaves a store it was given open
    store.head(ds.resolve("day=2026-11-19/part-0.parquet")).isDefined shouldBe true

    val listedAsync = Parquet.datasetAsync(store, root).await
    try listedAsync.files.size shouldBe 2
    finally listedAsync.close()
  }

  test("an aborted writer leaves no object") {
    requireServer()
    val uri = prefix.resolve("aborted.parquet")
    val w   = Parquet.newWriter[AllTypes](store.newOutputStream(uri))
    w.writeAll(rows.take(10))
    w.abort()
    store.head(uri) shouldBe None
  }

  test("write a partitioned dataset to a prefix and read it back") {
    requireServer()
    val root  = prefix.resolve("written/").toString
    val sales = (0 until 30).map(i =>
      S3Sale(
        i.toLong,
        if i % 2 == 0 then
          "JP"
        else
          "US"
      )
    )
    val options = ParquetDatasetWriteOptions
      .default
      .withPartitionBy("country")
      .withMaxRowsPerFile(8)
    val written = Parquet.writeDataset(store, root, sales, options)
    written.map(_.numRows).sum shouldBe 30L
    written.forall(_.path.startsWith(s"${root}country=")) shouldBe true

    val ds = Parquet.dataset(store, root)
    try
      val jp = ParquetReadOptions.default.withRowGroupFilter(ParquetFilter.col("country") === "JP")
      ds.selectFiles(jp).size shouldBe 2
      ds.readAs[S3Sale](jp).toSeq.sortBy(_.id) shouldBe sales.filter(_.country == "JP")
    finally
      ds.close()

    // Replace the dataset, then abort a write: neither leaves stray objects
    Parquet.writeDataset(store, root, sales.take(3), options.withMode(ParquetWriteMode.Overwrite))
    val w = Parquet.newDatasetWriter[S3Sale](store, root, options.withMode(ParquetWriteMode.Append))
    w.writeAll(sales)
    w.abort()
    val left = store.list(ObjectUri.parse(root)).map(_.uri.toString)
    left.filterNot(_.contains("/_")).size shouldBe 2
    left.filter(_.contains("/_")).map(_.stripPrefix(root)).sorted shouldBe
      Seq("_SUCCESS", "_common_metadata")
    Parquet.dataset(store, root).readAs[S3Sale]().toSeq.sortBy(_.id) shouldBe sales.take(3)
  }

  test("read partition types that the writer recorded") {
    requireServer()
    val root = prefix.resolve("typed/").toString
    val rows = (0 until 6).map(i =>
      Map("id" -> i.toLong, "dt" -> java.time.LocalDate.of(2026, 11, 20 + i % 2), "hour" -> i)
    )
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.DATE)
      .required("hour", ParquetType.INT32)
      .build()
    val w = Parquet.newDatasetRecordWriter(
      store,
      root,
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy("dt", "hour")
    )
    try w.writeAll(rows)
    finally w.close()
    def check(ds: ParquetDataset): Unit =
      try
        ds.partitionColumns.map(_.logicalType) shouldBe IndexedSeq(Some(LogicalType.Date), None)
        val day = ParquetReadOptions
          .default
          .withRowGroupFilter(ParquetFilter.col("dt") === java.time.LocalDate.of(2026, 11, 21))
        ds.readRows(day).map(_("id")).toSeq.sortBy(_.toString) shouldBe Seq(1L, 3L, 5L)
      finally
        ds.close()
    check(Parquet.dataset(store, root))
    check(Parquet.datasetAsync(store, root).await)
  }

  test("write a dataset with the async writer") {
    requireServer()
    val root  = prefix.resolve("async/").toString
    val sales = (0 until 20).map(i =>
      S3Sale(
        i.toLong,
        if i % 2 == 0 then
          "JP"
        else
          "US"
      )
    )
    val written =
      Parquet
        .writeDatasetAsync(
          store,
          root,
          sales,
          ParquetDatasetWriteOptions.default.withPartitionBy("country").withMaxRowsPerFile(4)
        )
        .await
    written.map(_.numRows).sum shouldBe 20L
    val ds = Parquet.datasetAsync(store, root).await
    try ds.readAs[S3Sale]().toSeq.sortBy(_.id) shouldBe sales
    finally ds.close()
  }

  test("read a glob pattern and a list of objects") {
    requireServer()
    val root    = prefix.resolve("globbed/").toString
    val sales   = (0 until 12).map(i => S3Sale(i.toLong, Seq("JP", "US", "FR")(i % 3)))
    val written = Parquet.writeDataset(
      store,
      root,
      sales,
      ParquetDatasetWriteOptions.default.withPartitionBy("country")
    )
    val jpUs = Parquet.dataset(store, s"${root}country={JP,US}")
    try jpUs.readAs[S3Sale]().toSeq.sortBy(_.id) shouldBe sales.filter(_.country != "FR")
    finally jpUs.close()

    // Partitions come from the folders below the listed files' common parent
    val listed = Parquet.dataset(store, written.map(_.path).filterNot(_.contains("country=US")))
    try
      listed.partitionColumns.map(_.name) shouldBe Seq("country")
      listed.readAs[S3Sale]().toSeq.sortBy(_.id) shouldBe sales.filter(_.country != "US")
    finally
      listed.close()
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

end S3ParquetTest

case class S3Sale(id: Long, country: String) derives wvlet.uni.weaver.Weaver
