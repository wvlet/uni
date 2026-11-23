package wvlet.uni.cloud.parquet

import wvlet.uni.http.Http
import wvlet.uni.http.StaticFiles
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest

/** Reads Parquet files over HTTP Range requests from uni's own server */
class HttpRangeTest extends UniTest:
  FileSystemInit.init()

  private val root = TestFiles.path("duckdb/empty.parquet").parent.get.path

  private def withServer[A](body: String => A): A =
    val server = Http.server.withPort(0).withFilter(StaticFiles(root)).start()
    try body(s"http://localhost:${server.localPort}")
    finally server.stop()

  test("read a file over HTTP with a synchronous client") {
    withServer { base =>
      val f = Parquet.openUrl(s"${base}/all_zstd_V2.parquet")
      try
        f.numRows shouldBe 3000L
        val rows = f.readAs[AllTypes]().toIndexedSeq
        rows.size shouldBe 3000
        rows(2999) shouldBe AllTypes.expected(2999)
      finally
        f.close()
    }
  }

  test("read selected columns of one row group over HTTP") {
    withServer { base =>
      val f = Parquet.openUrl(s"${base}/all_snappy_V1.parquet")
      try
        val b = f.readRowGroup(1, ParquetReadOptions.default.withColumns("id"))
        b.row(0) shouldBe Map("id" -> 2048L)
      finally
        f.close()
    }
  }

  test("read a file over HTTP asynchronously") {
    withServer { base =>
      Parquet
        .openUrlAsync(s"${base}/all_gzip_V1.parquet")
        .flatMap(_.readAllAsync[AllTypes]())
        .await
        .size shouldBe 3000
    }
  }

  test("report a missing file") {
    withServer { base =>
      // The HTTP client reports the status itself
      intercept[wvlet.uni.http.HttpException] {
        Parquet.openUrl(s"${base}/missing.parquet")
      }
    }
  }

end HttpRangeTest
