package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets

/** Temporary Iceberg tables and readers of their metadata, for row delete and update tests */
trait IcebergTableFixtures:
  self: UniTest =>

  private var dirs: List[IOPath] = Nil

  protected val d1 = java.time.LocalDate.of(2026, 11, 20)
  protected val d2 = java.time.LocalDate.of(2026, 11, 21)

  protected val sales = Seq(
    IcebergSale(1, "apple", d1, Some("JP")),
    IcebergSale(2, "banana", d1, None),
    IcebergSale(3, "cherry", d2, Some("US")),
    IcebergSale(4, "durian", d2, Some("JP")),
    IcebergSale(5, "elderberry", d2, Some("US"))
  )

  protected def create(properties: Map[String, String] = Map.empty): String =
    val root = newRoot()
    Parquet.writeDataset(
      root,
      sales,
      ParquetDatasetWriteOptions
        .default
        .withTableFormat(ParquetTableFormat.Iceberg)
        .withPartitionBy("dt")
        .withTableProperties(properties)
    )
    root

  protected val v3 = Map(IcebergWrite.FormatVersionProperty -> "3")

  protected def col(name: String) = ParquetFilter.col(name)

  protected def ids(root: String): Seq[Long] =
    val ds = Parquet.dataset(root)
    try ds.readRows().map(_("id").asInstanceOf[Long]).toSeq.sorted
    finally ds.close()

  protected def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-rows")
    dirs = root :: dirs
    root.resolve("sales").path

  protected def metadata(root: String, version: Int): JSONObject = JSON
    .parse(
      String(
        FileSystem.readBytes(IOPath.parse(s"${root}/metadata/v${version}.metadata.json")),
        StandardCharsets.UTF_8
      )
    )
    .asInstanceOf[JSONObject]

  protected def newestVersion(root: String): Int =
    String(
      FileSystem.readBytes(IOPath.parse(s"${root}/metadata/version-hint.text")),
      StandardCharsets.UTF_8
    ).trim.toInt

  protected def currentSnapshot(m: JSONObject): JSONObject =
    val id = m.get("current-snapshot-id").get.asInstanceOf[JSONLong].v
    m.get("snapshots")
      .get
      .asInstanceOf[JSONArray]
      .v
      .collect { case s: JSONObject =>
        s
      }
      .find(_.get("snapshot-id").contains(JSONLong(id)))
      .get

  protected def summary(root: String, key: String): Option[String] =
    currentSnapshot(metadata(root, newestVersion(root)))
      .get("summary")
      .collect { case o: JSONObject =>
        o.get(key)
          .collect { case JSONString(v) =>
            v
          }
      }
      .flatten

  /** A metadata file by the name in its recorded URI, which may name the table's old location */
  protected def read(root: String, uri: String): Avro.Container =
    val local = IOPath.parse(s"${root}/metadata/${uri.split('/').last}")
    Avro.read(FileSystem.readBytes(local), local.path)

  /** The live entries of the current snapshot's delete manifests */
  protected def liveDeletes(root: String): Seq[Map[String, Any]] =
    val s = currentSnapshot(metadata(root, newestVersion(root)))
    read(root, s.get("manifest-list").get.asInstanceOf[JSONString].v)
      .records
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("content") == 1)
      .flatMap(e => read(root, e("manifest_path").toString).records)
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("status") != 2)
      .map(_("data_file").asInstanceOf[Map[String, Any]])

  /**
    * A Hadoop-table copy of a catalog fixture: its newest metadata file becomes `v1.metadata.json`
    */
  protected def hadoopCopy(name: String): String =
    val root   = newRoot()
    val listed = ParquetDataset.listLocal(TestFiles.path(s"iceberg/${name}"))
    val newest = listed.map(_.segments.mkString("/")).filter(_.endsWith(".metadata.json")).max
    listed.foreach { l =>
      val rel = l.segments.mkString("/")
      if !rel.endsWith(".metadata.json") || rel == newest then
        val to =
          if rel == newest then
            "metadata/v1.metadata.json"
          else
            rel
        val path = IOPath.parse(s"${root}/${to}")
        FileSystem.createDirectory(path.parent.get)
        FileSystem.writeBytes(path, FileSystem.readBytes(IOPath.parse(l.path)))
    }
    FileSystem.writeString(IOPath.parse(s"${root}/metadata/version-hint.text"), "1")
    root

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

end IcebergTableFixtures
