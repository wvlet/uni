package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.Repetition
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class NwUserV1(name: String, age: Int) derives Weaver
// A newer writer: wider age, a new field, and a different field order
case class NwUserV2(email: Option[String], age: Long, name: String) derives Weaver
case class NwUserOut(name: String, age: Long, email: Option[String]) derives Weaver
case class NwUserBad(name: String, age: String) derives Weaver
case class NwRecV1(id: Long, user: NwUserV1) derives Weaver
case class NwRecV2(id: Long, user: NwUserV2) derives Weaver
case class NwRecOut(id: Long, user: NwUserOut) derives Weaver
case class NwRecBad(id: Long, user: NwUserBad) derives Weaver

case class NwIntList(id: Long, xs: Seq[Int]) derives Weaver
case class NwLongList(id: Long, xs: Seq[Long]) derives Weaver
case class NwLongValue(id: Long, xs: Long) derives Weaver
case class NwFloatMap(id: Long, m: Map[String, Float]) derives Weaver
case class NwDoubleMap(id: Long, m: Map[String, Double]) derives Weaver

case class NwPointV1(x: Int) derives Weaver
case class NwPointV2(x: Long, y: Option[Int]) derives Weaver
case class NwPathV1(id: Long, pts: Seq[NwPointV1]) derives Weaver
case class NwPathV2(id: Long, pts: Seq[NwPointV2]) derives Weaver

class DatasetNestedWideningTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def datasetDir(files: (String, Array[Byte])*): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-nested")
    dirs = root :: dirs
    files.foreach((name, bytes) => FileSystem.writeBytes(root.resolve(name), bytes))
    root

  private def withDataset[A](root: IOPath)(body: ParquetDataset => A): A =
    val ds = Parquet.dataset(root.path)
    try body(ds)
    finally ds.close()

  private def skipInBrowser(): Unit =
    if FileSystem.isBrowser then
      skip("No local file system in the browser")

  private def errorOf(root: IOPath): String =
    withDataset(root) { ds =>
      intercept[ParquetException](ds.schema).getMessage
    }

  test("widen and add struct fields across files") {
    skipInBrowser()
    val v1       = NwRecV1(1L, NwUserV1("a", 20))
    val v2       = NwRecV2(2L, NwUserV2(Some("b@example.com"), 1L << 40, "b"))
    val expected = Seq(
      NwRecOut(1L, NwUserOut("a", 20L, None)),
      NwRecOut(2L, NwUserOut("b", 1L << 40, Some("b@example.com")))
    )
    // Either file order gives the same values
    for (first, second) <- Seq(("1", "2"), ("2", "1")) do
      val root = datasetDir(
        s"${first}.parquet"  -> Parquet.writeBytes(Seq(v1)),
        s"${second}.parquet" -> Parquet.writeBytes(Seq(v2))
      )
      withDataset(root) { ds =>
        val user = ds.schema.field("user").get.asInstanceOf[ParquetField.Group]
        user.field("age").get.asInstanceOf[ParquetField.Primitive].physicalType shouldBe
          PhysicalType.INT64
        user.field("email").get.isOptional shouldBe true
        ds.readAs[NwRecOut]().toSeq.sortBy(_.id) shouldBe expected
        ds.readRows().toSeq.sortBy(_("id").asInstanceOf[Long]).head("user") shouldBe
          Map("name" -> "a", "age" -> 20L, "email" -> null)
      }
  }

  test("widen list elements and map values") {
    skipInBrowser()
    val lists = datasetDir(
      "1.parquet" -> Parquet.writeBytes(Seq(NwIntList(1L, Seq(1, 2)), NwIntList(2L, Seq.empty))),
      "2.parquet" -> Parquet.writeBytes(Seq(NwLongList(3L, Seq(1L << 40))))
    )
    withDataset(lists) { ds =>
      ds.readAs[NwLongList]().toSeq shouldBe
        Seq(NwLongList(1L, Seq(1L, 2L)), NwLongList(2L, Seq.empty), NwLongList(3L, Seq(1L << 40)))
    }
    val maps = datasetDir(
      "1.parquet" -> Parquet.writeBytes(Seq(NwDoubleMap(1L, Map("a" -> 0.1)))),
      "2.parquet" -> Parquet.writeBytes(Seq(NwFloatMap(2L, Map("b" -> 1.5f, "c" -> 2.0f))))
    )
    withDataset(maps) { ds =>
      ds.readAs[NwDoubleMap]().toSeq shouldBe
        Seq(NwDoubleMap(1L, Map("a" -> 0.1)), NwDoubleMap(2L, Map("b" -> 1.5, "c" -> 2.0)))
    }
  }

  test("evolve structs inside lists") {
    skipInBrowser()
    val root = datasetDir(
      "1.parquet" ->
        Parquet.writeBytes(
          Seq(
            NwPathV1(1L, Seq(NwPointV1(1), NwPointV1(2), NwPointV1(3))),
            NwPathV1(2L, Seq.empty),
            NwPathV1(3L, Seq(NwPointV1(4)))
          )
        ),
      "2.parquet" -> Parquet.writeBytes(Seq(NwPathV2(4L, Seq(NwPointV2(5L, Some(6))))))
    )
    withDataset(root) { ds =>
      ds.readAs[NwPathV2]().toSeq shouldBe
        Seq(
          NwPathV2(1L, Seq(NwPointV2(1L, None), NwPointV2(2L, None), NwPointV2(3L, None))),
          NwPathV2(2L, Seq.empty),
          NwPathV2(3L, Seq(NwPointV2(4L, None))),
          NwPathV2(4L, Seq(NwPointV2(5L, Some(6))))
        )
    }
  }

  /** A file with one list column `xs`, in a layout another writer would use */
  private def listFile(list: ParquetField.Group, rows: Seq[Seq[Long]]): Array[Byte] =
    val schema = ParquetSchema.fromElements(
      SchemaElements.of(
        ParquetSchema(
          ParquetField.Group("schema", Repetition.REQUIRED, None, Seq.empty, 0, 0, IndexedSeq(list))
        )
      )
    )
    val xs      = schema.field("xs").get.asInstanceOf[ParquetField.Group]
    val rep     = xs.children(0)
    val element =
      rep match
        case g: ParquetField.Group =>
          g.children(0)
        case p =>
          p
    val flat                   = rows.flatten
    val elements: ColumnVector =
      element.asInstanceOf[ParquetField.Primitive].physicalType match
        case PhysicalType.INT32 =>
          IntColumn(element, flat.map(_.toInt).toArray, null)
        case _ =>
          LongColumn(element, flat.toArray, null)
    val offsets = rows.scanLeft(0)(_ + _.size).toArray
    val out     = java.io.ByteArrayOutputStream()
    val w       = Parquet.newBatchWriter(out, schema)
    w.write(ColumnBatch(rows.size, IndexedSeq(ListColumn(xs, offsets, elements, null))))
    w.close()
    out.toByteArray

  private def leaf(name: String, rep: Repetition, tpe: PhysicalType): ParquetField.Primitive =
    ParquetField.Primitive(name, rep, tpe, 0, None, Seq.empty, 0, 0, 0)

  private def listOf(rep: ParquetField): ParquetField.Group = ParquetField.Group(
    "xs",
    Repetition.OPTIONAL,
    Some(LogicalType.List),
    Seq.empty,
    0,
    0,
    IndexedSeq(rep)
  )

  private def threeLevel(repName: String, element: ParquetField): ParquetField.Group = listOf(
    ParquetField.Group(repName, Repetition.REPEATED, None, Seq.empty, 0, 0, IndexedSeq(element))
  )

  test("merge lists written with different wrapper names and layouts") {
    skipInBrowser()
    val root = datasetDir(
      // parquet-mr and pyarrow 13+
      "1.parquet" ->
        listFile(
          threeLevel("list", leaf("element", Repetition.REQUIRED, PhysicalType.INT32)),
          Seq(Seq(1L, 2L))
        ),
      // older pyarrow
      "2.parquet" ->
        listFile(
          threeLevel("list", leaf("item", Repetition.OPTIONAL, PhysicalType.INT64)),
          Seq(Seq(3L))
        ),
      // the legacy 2-level layout
      "3.parquet" ->
        listFile(listOf(leaf("array", Repetition.REPEATED, PhysicalType.INT32)), Seq(Seq(4L, 5L)))
    )
    withDataset(root) { ds =>
      ds.readRows().map(_("xs").asInstanceOf[Seq[Long]]).toSeq.sortBy(_.head) shouldBe
        Seq(Seq(1L, 2L), Seq(3L), Seq(4L, 5L))
      val xs = ds.schema.field("xs").get.asInstanceOf[ParquetField.Group]
      xs.children(0).asInstanceOf[ParquetField.Group].children(0).name shouldBe "element"
    }
  }

  test("name the nested path and both files in type errors") {
    skipInBrowser()
    val root = datasetDir(
      "a.parquet" -> Parquet.writeBytes(Seq(NwRecV1(1L, NwUserV1("a", 1)))),
      "b.parquet" -> Parquet.writeBytes(Seq(NwRecV2(2L, NwUserV2(None, 2L, "b")))),
      "c.parquet" -> Parquet.writeBytes(Seq(NwRecBad(3L, NwUserBad("c", "x"))))
    )
    val msg = errorOf(root)
    msg shouldContain "Column user.age has different types"
    // b.parquet set the INT64 type that conflicts with c.parquet's string
    msg shouldContain "b.parquet (INT64"
    msg shouldContain "c.parquet (BYTE_ARRAY"
  }

  test("keep errors for a list against a single value") {
    skipInBrowser()
    val root = datasetDir(
      "1.parquet" -> Parquet.writeBytes(Seq(NwLongList(1L, Seq(1L)))),
      "2.parquet" -> Parquet.writeBytes(Seq(NwLongValue(2L, 2L)))
    )
    errorOf(root) shouldContain "Column xs has different types"
  }

end DatasetNestedWideningTest
