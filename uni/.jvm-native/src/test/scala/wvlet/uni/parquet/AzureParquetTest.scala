/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.storage.AzureConfig
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
import wvlet.uni.test.UniTest

/** Runs when `UNI_AZURE_TEST_CONNECTION_STRING` is set (CI starts Azurite for the JVM job) */
class AzureParquetTest extends UniTest:
  FileSystemInit.init()

  private val connectionString = Option(System.getenv("UNI_AZURE_TEST_CONNECTION_STRING")).filter(
    _.nonEmpty
  )

  private lazy val config = AzureConfig
    .fromConnectionString(connectionString.get)
    .withBlockSizeBytes(AzureConfig.MinBlockSize)

  private lazy val container = Option(System.getenv("UNI_AZURE_TEST_CONTAINER")).getOrElse(
    "uni-test"
  )

  private lazy val prefix = ObjectUri(container, s"parquet-${System.currentTimeMillis()}/", "az")
  private lazy val store  = ObjectStore.azure(config)

  private def requireServer(): Unit =
    if connectionString.isEmpty then
      skip("Set UNI_AZURE_TEST_CONNECTION_STRING to run Azure Blob integration tests")

  override protected def beforeAll: Unit =
    if connectionString.isDefined then
      store.createContainer(container)

  override protected def afterAll: Unit =
    if connectionString.isDefined then
      store.list(prefix).foreach(o => store.delete(o.uri))
      store.close()

  private val rows = (0 until 5000).map(AllTypes.expected)

  test("write Parquet to Azure Blob and read it back") {
    requireServer()
    val uri = prefix.resolve("all_types.parquet")
    val w   = Parquet.newWriter[AllTypes](store.newOutputStream(uri))
    w.writeAll(rows)
    w.close()

    val f = Parquet.openAzure(uri.toString, config)
    try
      f.numRows shouldBe rows.size.toLong
      f.readAs[AllTypes]().toIndexedSeq shouldBe rows
    finally
      f.close()

    val asyncRows = Parquet
      .openAzureAsync(uri.toString, config)
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

  test("an aborted writer leaves no blob") {
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

end AzureParquetTest
