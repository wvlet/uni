package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.ListOptions
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class SelInner(a: Option[Int], b: Option[String]) derives Weaver

case class SelRow(
    id: Long,
    k: Int,
    flag: Option[Boolean],
    f: Option[Float],
    d: Double,
    s: Option[String],
    word: String,
    inner: Option[SelInner],
    tags: Seq[String]
) derives Weaver

/**
  * Filter-first reads (late materialization) must give exactly what decoding every column and then
  * filtering gives: the same rows, values, nulls, and dictionary indices.
  */
class LateMaterializationTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 5000).map { i =>
    SelRow(
      id = i.toLong,
      k = (i * 7919) % 1000,
      flag = Option.when(i % 5 != 0)(i % 3 == 0),
      f = Option.when(i % 7 != 0)(i.toFloat / 3),
      d = (i % 97).toDouble,
      s = Option.when(i % 4 != 1)(s"v${i % 37}"),
      word = s"w${i}" * (i % 3 + 1),
      inner =
        if i % 9 == 0 then
          None
        else
          Some(SelInner(Option.when(i % 2 == 0)(i), Option.when(i % 3 != 0)(s"b${i % 5}")))
      ,
      tags = (0 until i % 3).map(t => s"t${t}")
    )
  }

  /** Several row groups of several pages each, so pages and whole row groups get skipped */
  private def write(dictionary: Boolean): Array[Byte] = Parquet.writeBytes(
    rows,
    ParquetWriteOptions
      .default
      .withRowGroupSize(64 * 1024)
      .withPageSize(2 * 1024)
      .withDictionaryEnabled(dictionary)
  )

  private val files = Seq(true, false).map(d => d -> write(d))

  /** Everything a read returns, values and vector shape, as comparable strings */
  private def describe(batches: Iterator[ColumnBatch]): Seq[String] =
    batches
      .flatMap { b =>
        Iterator
          .range(0, b.numRows)
          .map { i =>
            b.columns
              .map { v =>
                val value  = ParquetJson.fromRow(Map("v" -> v.getValue(i))).toJSON
                val extras =
                  v match
                    case c: BinaryColumn if c.dictionaryIndex(i) >= 0 =>
                      // A batch of nulls only has no rows from a dictionary, so its size may be 0
                      s"#${c.dictionaryIndex(i)}/${c.dictionarySize}"
                    case c: BinaryColumn =>
                      "#-1"
                    case _ =>
                      ""
                s"${v.name}=${value}${extras}"
              }
              .mkString(",")
          }
      }
      .toSeq

  private def both(
      f: ParquetFile,
      options: ParquetReadOptions
  ): (Either[String, Seq[String]], Either[String, Seq[String]]) =
    def run(late: Boolean) =
      f.lateMaterialization = late
      try
        Right(describe(f.readBatches(options)))
      catch
        case e: IllegalArgumentException =>
          Left(e.getMessage)
        case e: ParquetException =>
          Left(e.getClass.getName)
    try (run(true), run(false))
    finally f.lateMaterialization = true

  /** Compares two reads, showing only the first difference */
  private def same(
      late: Either[String, Seq[String]],
      eager: Either[String, Seq[String]],
      context: String = ""
  ): Unit =
    (late, eager) match
      case (Right(a), Right(b)) =>
        val i = a.zip(b).indexWhere(_ != _)
        if i >= 0 then
          s"${context} row ${i}: ${a(i)}" shouldBe s"${context} row ${i}: ${b(i)}"
        a.size shouldBe b.size
      case _ =>
        late shouldBe eager

  private def check(bytes: Array[Byte], filter: ParquetFilter, options: ParquetReadOptions) =
    val f = Parquet.open(bytes)
    try
      val (late, eager) = both(f, options.withFilter(filter))
      same(late, eager)
      eager.getOrElse(Seq.empty)
    finally
      f.close()

  private val filters: Seq[ParquetFilter] = Seq(
    col("id") >= 4990L,
    col("id") === 17L,
    // No row matches, so no page of the other columns is read
    col("id") < 0L,
    col("k") < 10,
    col("d") > 1000.0,
    col("s") === "v3",
    col("s").isNull,
    col("inner.a") > 4000,
    col("inner").isNull,
    col("tags").isNull,
    // Conjuncts decoded step by step, each for the rows the steps before it kept
    col("k") < 500 && col("s") === "v5" && col("d") <= 50.0,
    col("d") <= 3.0 && col("id") > 100L && col("k") >= 0,
    col("s") > "v2" && (col("k") < 100 || col("inner.b") === "b1"),
    col("id") < 4000L && col("id") > 3000L && col("word") >= "w35",
    col("flag") === true && col("f") > 100.0,
    col("id") >= 0L
  )

  test("return the rows that decoding everything then filtering returns") {
    for
      (dict, bytes) <- files
      filter        <- filters
      options       <- Seq(
        ParquetReadOptions.default.withBinaryAsString(true),
        ParquetReadOptions.default.withColumns("word", "tags", "f", "inner").withBatchSize(333),
        ParquetReadOptions.default.withColumns("s").withBatchSize(1),
        // A column read twice
        ParquetReadOptions.default.withColumns("word", "s", "word").withBatchSize(1000)
      )
    do
      check(bytes, filter, options)
  }

  test("return the expected rows") {
    val bytes = files.head._2
    check(bytes, col("k") < 500 && col("s") === "v5", ParquetReadOptions.default).size shouldBe
      rows.count(r => r.k < 500 && r.s.contains("v5"))
    check(bytes, col("id") < 0L, ParquetReadOptions.default) shouldBe Seq.empty
  }

  test("read the row group of readRowGroup as one batch") {
    val f = Parquet.open(files.head._2)
    try
      val opts = ParquetReadOptions.default.withFilter(col("k") < 50).withColumns("word")
      (0 until f.numRowGroups).foreach { i =>
        val late = f.readRowGroup(i, opts)
        f.lateMaterialization = false
        val eager = f.readRowGroup(i, opts)
        f.lateMaterialization = true
        describe(Iterator(late)) shouldBe describe(Iterator(eager))
        late.columnNames shouldBe Seq("word")
      }
    finally
      f.close()
  }

  test("read small batches, windowed chunks, and columns too large to select") {
    val bytes = files.last._2
    for limits <- Seq((1L << 30, 1 << 20), (4096L, 4096)) do
      val f = Parquet.open(bytes)
      try
        f.batchSoftLimit = limits._1
        f.chunkWindowSize = limits._2
        val (late, eager) = both(
          f,
          ParquetReadOptions.default.withFilter(col("k") < 100 && col("d") < 30.0)
        )
        same(late, eager)
        eager.map(_.size).getOrElse(0) shouldBe rows.count(r => r.k < 100 && r.d < 30.0)
      finally
        f.close()
  }

  test("read asynchronously") {
    val f    = Parquet.open(files.head._2)
    val opts = ParquetReadOptions
      .default
      .withBinaryAsString(true)
      .withFilter(col("k") < 30 && col("s") === "v1")
    f.readAllRowsAsync(opts)
      .map { rows =>
        try
          rows.nonEmpty shouldBe true
          rows shouldBe f.readRows(opts).toSeq
        finally
          f.close()
      }
  }

  // Every encoding, page version, codec, and nested shape the test files have, filtered on each of
  // their columns by a value of the column
  private def testFiles: Seq[String] =
    val root = TestFiles.path("duckdb").parent.get
    FileSystem.list(root, ListOptions(recursive = true, extensions = Seq("parquet"))).map(_.path)

  test("match decoding everything then filtering on the test files") {
    var checked = 0
    testFiles.foreach { path =>
      val bytes  = FileSystem.readBytes(wvlet.uni.io.IOPath.parse(path))
      val sample =
        try
          val f = Parquet.open(bytes)
          try
            val opts = ParquetReadOptions.default.withBinaryAsString(true)
            Some((f.schema, f.readBatches(opts).take(1).toSeq.headOption))
          finally
            f.close()
        catch
          case e: Exception =>
            None
      sample.foreach {
        case (schema, Some(b)) if b.numRows > 0 =>
          val prims = schema
            .fields
            .collect {
              case p: ParquetField.Primitive if !p.isRepeated =>
                p.name
            }
          val values = prims.flatMap { c =>
            val v = b.column(c)
            Iterator
              .range(b.numRows / 3, b.numRows)
              .find(i => !v.isNull(i))
              .map(i => c -> v.getValue(i))
          }
          val fs = values.flatMap { (c, v) =>
            Seq(col(c) <= v, col(c) === v) ++
              values.filter(_._1 != c).take(1).map((c2, v2) => col(c) <= v && col(c2) >= v2)
          }
          fs.foreach { filter =>
            val f = Parquet.open(bytes)
            try
              val (late, eager) = both(
                f,
                ParquetReadOptions.default.withBinaryAsString(true).withFilter(filter)
              )
              same(late, eager, path)
              if eager.isRight then
                checked += 1
            catch
              case e: ParquetException =>
              // A file the reader rejects is rejected either way
            finally
              f.close()
          }
        case _ =>
      }
    }
    (checked > 100) shouldBe true
  }

end LateMaterializationTest
