package wvlet.uni.parquet.npm

import wvlet.uni.parquet.MemoryObjectStore
import wvlet.uni.storage.AwsCredentials
import wvlet.uni.storage.AzureCredentials
import wvlet.uni.test.UniTest

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/**
  * The facade's object-store path, against a store whose sync methods fail as browsers would. The
  * local path is covered by the Node smoke test in uni-npm/npm-test.
  */
class JsDatasetTest extends UniTest:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val root = "s3://bucket/events/"

  private val schema = js.Array[js.Dynamic](
    js.Dynamic.literal(name = "id", `type` = "int64", optional = false),
    js.Dynamic.literal(name = "name", `type` = "string"),
    js.Dynamic.literal(name = "dt", `type` = "date")
  )

  private def events: js.Array[js.Any] = js.Array(
    js.Dynamic.literal(name = "a", dt = "2026-11-19", id = js.BigInt(1)),
    js.Dynamic.literal(name = "b", dt = "2026-11-20", id = js.BigInt(2)),
    js.Dynamic.literal(name = "c", dt = "2026-11-20", id = js.BigInt(3))
  )

  private def write(store: MemoryObjectStore): Future[js.Array[js.Object]] =
    val stores: JsDataset.Stores = (_, _) => store
    ParquetNpm.first(
      JsDataset
        .writerOf(root, schema, js.Dynamic.literal(partitionBy = js.Array("dt")), stores)
        .flatMap(w => w.writeAllRx(events).flatMap(_ => w.closeRx()))
    )

  private def open(
      store: MemoryObjectStore,
      source: js.Any,
      options: js.Dynamic = js.Dynamic.literal()
  ): Future[JsParquetDataset] = JsDataset.openDatasetWith(source, options, (_, _) => store).toFuture

  private def ids(rows: js.Array[js.Any]): Seq[String] = rows
    .toSeq
    .map(_.asInstanceOf[js.Dynamic].id.toString)

  test("write and read a dataset on an object store with async calls only") {
    val store = MemoryObjectStore()
    write(store)
      .flatMap { written =>
        written.length shouldBe 2
        store.keys("").map(_.split('/').last).filter(_.startsWith("_")).sorted shouldBe
          Seq("_SUCCESS", "_common_metadata")
        open(store, root)
      }
      .flatMap { ds =>
        ds.partitionColumns.toSeq shouldBe Seq("dt")
        val dt = ds.schema.toSeq.last.asInstanceOf[js.Dynamic]
        // The type recorded in _common_metadata
        dt.`type`.toString shouldBe "date"
        dt.partition.asInstanceOf[Boolean] shouldBe true
        val filter = js.Dynamic.literal(filter = js.Array[js.Any]("dt", "=", "2026-11-20"))
        for
          files <- ds.files(filter).toFuture
          rows  <- ds.readRows(filter).toFuture
          all   <- ds.readRows().toFuture
        yield
          files.length shouldBe 1
          ids(rows) shouldBe Seq("2", "3")
          ids(all) shouldBe Seq("1", "2", "3")
          all(0).asInstanceOf[js.Dynamic].dt.isInstanceOf[js.Date] shouldBe true
          ds.close()
      }
  }

  test("read an object-store glob and file list") {
    val store = MemoryObjectStore()
    write(store).flatMap { written =>
      val paths = written.toSeq.map(_.asInstanceOf[js.Dynamic].path.toString).sorted
      for
        glob  <- open(store, s"${root}dt=2026-11-2*")
        grows <- glob.readRows().toFuture
        list  <- open(
          store,
          paths.toJSArray,
          js.Dynamic.literal(partitionTypes = js.Dynamic.literal(dt = "date"))
        )
        files <- list.files().toFuture
        lrows <- list.readRows().toFuture
      yield
        ids(grows) shouldBe Seq("2", "3")
        // Objects in a list are not listed, so their sizes are unknown
        files.toSeq.map(_.asInstanceOf[js.Dynamic].size) shouldBe Seq(null, null)
        ids(lrows) shouldBe Seq("1", "2", "3")
        lrows(0).asInstanceOf[js.Dynamic].dt.isInstanceOf[js.Date] shouldBe true
    }
  }

  test("map storage options to store configs") {
    val s3 = JsStorage.s3Config(
      js.Dynamic
        .literal(
          region = "us-west-2",
          endpoint = "http://localhost:9000/",
          pathStyle = true,
          credentials = js
            .Dynamic
            .literal(accessKeyId = "AK", secretAccessKey = "SK", sessionToken = "ST")
        ),
      browser = true
    )
    s3.region shouldBe Some("us-west-2")
    s3.endpoint shouldBe Some("http://localhost:9000")
    s3.pathStyle shouldBe true
    s3.credentials.credentials shouldBe Some(AwsCredentials("AK", "SK", Some("ST")))
    JsStorage
      .s3Config(js.Dynamic.literal(credentials = "anonymous"), browser = true)
      .credentials
      .credentials shouldBe None

    val az = JsStorage.azureConfig(js.Dynamic.literal(sasToken = "?sv=1&sig=x"), browser = true)
    az.credentials.credentials shouldBe Some(AzureCredentials.Sas("?sv=1&sig=x"))
    JsStorage
      .gcsConfig(js.Dynamic.literal(accessToken = "t"), browser = true)
      .credentials
      .isDefined shouldBe true
  }

  test("ask for credentials in browsers") {
    val e1 = intercept[IllegalArgumentException] {
      JsStorage.s3Config(js.Dynamic.literal(region = "us-east-1"), browser = true)
    }
    e1.getMessage shouldContain "storage.s3 credentials"
    val e2 = intercept[IllegalArgumentException] {
      JsStorage.gcsConfig(js.Dynamic.literal(credentialsJson = "{}"), browser = true)
    }
    e2.getMessage shouldContain "Node.js"
    val e3 = intercept[IllegalArgumentException] {
      JsStorage.azureConfig(js.Dynamic.literal(account = "acct"), browser = true)
    }
    e3.getMessage shouldContain "storage.azure credentials"
    // Node.js falls back to the default chain
    JsStorage.s3Config(js.Dynamic.literal(), browser = false).region shouldBe None
  }

end JsDatasetTest
