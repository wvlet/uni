package wvlet.uni.parquet

import wvlet.uni.parquet.ParquetFilter.*
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalDate

case class AsyncSale(id: Long, amount: Double, dt: LocalDate, country: String) derives Weaver

/** The async dataset writer against an in-memory store whose sync methods fail, as in a browser */
class AsyncDatasetWriterTest extends UniTest:
  private val root  = "s3://bucket/sales/"
  private val days  = Seq(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21))
  private val sales =
    for i <- 0 until 40
    yield AsyncSale(
      i.toLong,
      i * 1.5,
      days(i % 2),
      if i % 3 == 0 then
        "JP"
      else
        "US"
    )

  private val byDtCountry = ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country")

  private def dataKeys(store: MemoryObjectStore): Seq[String] = store
    .keys(root)
    .filterNot(_.stripPrefix(root).startsWith("_"))

  private def rootKeys(store: MemoryObjectStore): Seq[String] = store
    .keys(root)
    .map(_.stripPrefix(root))
    .filter(_.startsWith("_"))

  private def readBack(store: MemoryObjectStore): Rx[Seq[AsyncSale]] = Parquet
    .datasetAsync(store, root)
    .flatMap { ds =>
      ds.partitionColumns.map(_.name) shouldBe IndexedSeq("dt", "country")
      ds.readAllAsync[AsyncSale]().map(_.sortBy(_.id))
    }

  test("write a dataset with async calls and read it back typed") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, root, sales, byDtCountry)
      .flatMap { written =>
        written.size shouldBe 4
        written.map(_.numRows).sum shouldBe 40L
        dataKeys(store).size shouldBe 4
        rootKeys(store) shouldBe Seq("_SUCCESS", "_common_metadata")
        readBack(store)
      }
      .map(_ shouldBe sales)
  }

  test("roll files and upload them while writing") {
    val store = MemoryObjectStore()
    Parquet
      .newDatasetWriterAsync[AsyncSale](store, root, byDtCountry.withMaxRowsPerFile(3))
      .flatMap { w =>
        // Uploads happen during writeAll, before close
        w.writeAll(sales)
          .flatMap { _ =>
            (dataKeys(store).size > 0) shouldBe true
            w.close()
          }
      }
      .flatMap { written =>
        written.map(_.numRows).sum shouldBe 40L
        written.forall(_.numRows <= 3) shouldBe true
        readBack(store)
      }
      .map(_ shouldBe sales)
  }

  test("ask for flush when queued files exceed the budget") {
    val store = MemoryObjectStore()
    Parquet
      .newDatasetWriterAsync[AsyncSale](
        store,
        root,
        byDtCountry.withMaxRowsPerFile(1).withMaxBufferedBytes(1)
      )
      .flatMap { w =>
        w.write(sales(0))
        val e = intercept[ParquetException] {
          w.write(sales(1))
        }
        e.getMessage shouldContain "flush()"
        w.flush()
          .flatMap { _ =>
            w.write(sales(1))
            w.close()
          }
      }
      .map(_.map(_.numRows).sum shouldBe 2L)
  }

  test("delete uploaded files when an upload fails") {
    val store = MemoryObjectStore(failPuts = Some(2))
    Parquet
      .writeDatasetAsync(store, root, sales, byDtCountry.withMaxRowsPerFile(3))
      .transform { result =>
        result.isFailure shouldBe true
        store.size shouldBe 0
      }
  }

  test("abort deletes uploaded files") {
    val store = MemoryObjectStore()
    Parquet
      .newDatasetWriterAsync[AsyncSale](store, root, byDtCountry.withMaxRowsPerFile(3))
      .flatMap { w =>
        w.writeAll(sales)
          .flatMap { _ =>
            (store.size > 0) shouldBe true
            w.abort()
          }
      }
      .map(_ => store.size shouldBe 0)
  }

  test("apply write modes and root files with async calls") {
    val store = MemoryObjectStore()
    val jp    = sales.filter(_.country == "JP")
    Parquet
      .writeDatasetAsync(store, root, sales, byDtCountry)
      .flatMap(_ => Parquet.writeDatasetAsync(store, root, sales, byDtCountry))
      .transform { result =>
        result.isFailure shouldBe true
        result.failed.get.getMessage shouldContain "already holds data files"
      }
      .flatMap(_ =>
        Parquet.writeDatasetAsync(
          store,
          root,
          jp,
          byDtCountry.withMode(ParquetWriteMode.Overwrite).withWriteSuccessMarker(false)
        )
      )
      .flatMap { _ =>
        rootKeys(store) shouldBe Seq("_common_metadata")
        Parquet.writeDatasetAsync(
          store,
          root,
          sales.filterNot(_.country == "JP"),
          byDtCountry.withMode(ParquetWriteMode.Append)
        )
      }
      .flatMap { _ =>
        rootKeys(store) shouldBe Seq("_SUCCESS", "_common_metadata")
        readBack(store)
      }
      .flatMap { all =>
        all shouldBe sales
        // A dataset read with a filter on a recorded DATE partition
        Parquet
          .datasetAsync(store, root)
          .map(ds =>
            ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("dt") === days(0)))
              .size shouldBe 2
          )
      }
  }

  test("write records and batches with async calls") {
    val store  = MemoryObjectStore()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.DATE)
      .build()
    val opts = ParquetDatasetWriteOptions.default.withPartitionBy("dt")
    Parquet
      .newDatasetRecordWriterAsync(store, root, schema, opts)
      .flatMap { w =>
        w.writeAll(Seq(Map("id" -> 1L, "dt" -> days(0)), Map("id" -> 2L, "dt" -> days(1))))
          .flatMap(_ => w.close())
      }
      .map { written =>
        written.size shouldBe 2
        dataKeys(store).size shouldBe 2
      }
  }

  test("write many small files without growing the stack") {
    val store = MemoryObjectStore()
    val rows  = (0 until 2000).map(i => AsyncSale(i.toLong, 1.0, days(i % 2), "JP"))
    Parquet
      .writeDatasetAsync(store, root, rows, byDtCountry.withMaxRowsPerFile(1))
      .map { written =>
        written.size shouldBe 2000
        dataKeys(store).size shouldBe 2000
      }
  }

end AsyncDatasetWriterTest
