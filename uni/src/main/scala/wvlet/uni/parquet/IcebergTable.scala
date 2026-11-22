package wvlet.uni.parquet

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.parquet.ParquetDataset.Listed
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.Repetition
import wvlet.uni.parquet.format.TimeUnit
import wvlet.uni.storage.ObjectUri

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.collection.mutable

/**
  * Reads the metadata of an Apache Iceberg table: a `metadata/` folder with `.metadata.json` files
  * holds the schemas, partition specs, and snapshots; a snapshot's manifest list (Avro) names its
  * manifests (Avro), which list the data files.
  *
  * Each level needs the bytes of the one before it, so the read is a chain of [[Step]]s: fetch
  * these files, then continue with their bytes. The sync and async readers run the same steps.
  */
private[parquet] object IcebergTable:
  val MetadataDir = "metadata"
  val VersionHint = "version-hint.text"

  /** A step of the read: fetch `paths`, then continue with their bytes in the same order */
  enum Step:
    case Fetch(paths: IndexedSeq[String], next: IndexedSeq[Array[Byte]] => Step)
    case Done(table: Resolved)

  /** A live data file, with the values of the partition columns by column name */
  case class DataFile(path: String, size: Long, partitions: Map[String, Any])

  /** The table at the chosen snapshot */
  case class Resolved(
      root: String,
      /** The snapshot schema's top-level columns */
      columns: IndexedSeq[ParquetColumn],
      /** Identity partition columns whose values come from the manifests */
      partitionColumns: IndexedSeq[(String, ParquetType.Primitive)],
      /** Identity partition source columns that each file must hold, since no value fills them */
      requiredInFiles: Set[String],
      files: IndexedSeq[DataFile]
  )

  private val HadoopMetadata   = """v(\d+)\.metadata\.json""".r
  private val CatalogMetadata  = """(\d+)-.+\.metadata\.json""".r
  private val MaxFormatVersion = 2

  def isMetadataFile(path: String): Boolean = path.endsWith(".metadata.json")

  /**
    * Whether a listing below a root has a `metadata/` folder with `.metadata.json` files directly
    * under it
    */
  def isTable(listed: Seq[Listed]): Boolean = listed.exists(l =>
    l.segments.size == 2 && l.segments.head == MetadataDir && isMetadataFile(l.segments.last)
  )

  /**
    * The steps that read a table from the listing of its root (or of `metadata/` with `metadata` as
    * the first segment). `tableRoot` ends with `/`.
    */
  def fromListing(
      tableRoot: String,
      listed: Seq[Listed],
      snapshotId: Option[Long],
      objects: Boolean
  ): Step =
    val meta = listed.filter(l => l.segments.size == 2 && l.segments.head == MetadataDir)
    def byName(name: String): Option[Listed] = meta.find(_.segments.last == name)
    meta.find(_.segments.last == VersionHint) match
      case Some(hint) =>
        Step.Fetch(
          IndexedSeq(hint.path),
          bytes =>
            val v    = String(bytes.head, StandardCharsets.UTF_8).trim
            val file =
              if isMetadataFile(v) then
                byName(v.split('/').last)
              else
                byName(s"v${v}.metadata.json").orElse(byName(s"v${v}.gz.metadata.json"))
            file match
              case Some(f) =>
                fromMetadataFile(tableRoot, f.path, snapshotId, objects)
              case None =>
                throw ParquetException(
                  s"${tableRoot}${MetadataDir}/${VersionHint} names version ${v}, but its metadata file isn't in ${tableRoot}${MetadataDir}/"
                )
        )
      case None =>
        val versioned = meta.flatMap { l =>
          l.segments.last match
            case HadoopMetadata(v) =>
              Some(v.toLong -> l)
            case CatalogMetadata(v) =>
              Some(v.toLong -> l)
            case _ =>
              None
        }
        if versioned.isEmpty then
          throw ParquetException(
            s"${tableRoot} is not an Iceberg table: ${tableRoot}${MetadataDir}/ has no metadata file"
          )
        val newest = versioned.map(_._1).max
        versioned.filter(_._1 == newest).map(_._2) match
          case Seq(f) =>
            fromMetadataFile(tableRoot, f.path, snapshotId, objects)
          case several =>
            throw ParquetException(
              s"${tableRoot}${MetadataDir}/ has several metadata files for version ${newest}: ${several
                  .map(_.segments.last)
                  .mkString(
                    ", "
                  )}. Pass the current one, which the table's catalog names, as the root"
            )
    end match

  end fromListing

  /**
    * The steps that read a table from one metadata file. `tableRoot` is where the table is now;
    * paths under the location the metadata records are read from there.
    */
  def fromMetadataFile(
      tableRoot: String,
      metadataPath: String,
      snapshotId: Option[Long],
      objects: Boolean
  ): Step =
    if metadataPath.endsWith(".gz.metadata.json") then
      throw ParquetException(s"${metadataPath} is gzip-compressed, which Uni doesn't read yet")
    Step.Fetch(
      IndexedSeq(metadataPath),
      bytes =>
        JSON.parse(String(bytes.head, StandardCharsets.UTF_8)) match
          case m: JSONObject =>
            fromMetadata(tableRoot, metadataPath, m, snapshotId, objects)
          case _ =>
            throw ParquetException(s"${metadataPath} is not a JSON object")
    )

  /** The table root for a metadata file path: the folder above `metadata/`, else none */
  def rootOfMetadataFile(path: String): Option[String] =
    val i = lastSeparator(path)
    if i < 0 then
      None
    else
      val dir = path.substring(0, i)
      val j   = lastSeparator(dir)
      if j >= 0 && dir.substring(j + 1) == MetadataDir then
        Some(dir.substring(0, j + 1))
      else
        None

  /** The last `/`, or `\` in a local Windows path */
  def lastSeparator(path: String): Int = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))

  private def fromMetadata(
      givenRoot: String,
      source: String,
      m: JSONObject,
      snapshotId: Option[Long],
      objects: Boolean
  ): Step =
    val formatVersion = long(m, "format-version").getOrElse(1L)
    if formatVersion > MaxFormatVersion then
      throw ParquetException(
        s"${source} is an Iceberg format version ${formatVersion} table; Uni reads versions 1 and 2"
      )
    val location = string(m, "location").getOrElse(
      throw ParquetException(s"${source} has no table location")
    )
    val paths   = Paths(givenRoot, location, objects)
    val schemas = schemasOf(m, source)
    checkSchemaHistory(schemas.values.toSeq, source)
    val specs = specsOf(m)

    val current   = long(m, "current-snapshot-id").filter(_ >= 0)
    val snapshots =
      m.get("snapshots") match
        case Some(JSONArray(vs)) =>
          vs.collect { case o: JSONObject =>
            o
          }
        case _ =>
          IndexedSeq.empty
    val wanted          = snapshotId.orElse(current)
    val currentSchemaId = long(m, "current-schema-id")
      .map(_.toInt)
      .getOrElse(schemas.keys.headOption.getOrElse(0))
    def schemaFields(id: Int): IndexedSeq[JSONObject] = schemas.getOrElse(
      id,
      throw ParquetException(s"${source} has no schema with id ${id}")
    )

    wanted match
      case None =>
        // A table without snapshots: no files, the current schema and default spec
        val fields = schemaFields(currentSchemaId)
        val spec   = long(m, "default-spec-id").map(_.toInt).flatMap(specs.get).getOrElse(Seq.empty)
        Step.Done(resolved(paths.root, fields, Seq(spec), IndexedSeq.empty, source))
      case Some(id) =>
        val snapshot = snapshots
          .find(s => long(s, "snapshot-id").contains(id))
          .getOrElse {
            val known = snapshots.map { s =>
              s"${long(s, "snapshot-id").getOrElse(-1L)} (${long(s, "timestamp-ms")
                  .map(java.time.Instant.ofEpochMilli)
                  .getOrElse("?")})"
            }
            throw ParquetException(
              s"${paths.root} has no snapshot ${id}. Snapshots: ${
                  if known.isEmpty then
                    "none"
                  else
                    known.mkString(", ")
                }"
            )
          }
        val fields = schemaFields(
          long(snapshot, "schema-id").map(_.toInt).getOrElse(currentSchemaId)
        )
        def readManifests(manifests: IndexedSeq[Manifest]): Step = Step.Fetch(
          manifests.map(mf => paths.resolve(mf.path)),
          bytes =>
            val files = mutable.ArrayBuffer.empty[(DataFile, Int)]
            manifests
              .zip(bytes)
              .foreach { (mf, b) =>
                val c      = Avro.read(b, mf.path)
                val specId = c.meta("partition-spec-id").flatMap(_.toIntOption).getOrElse(mf.specId)
                val content = c.meta("content").getOrElse("data")
                if content == "deletes" then
                  // A v1 inline manifest list has no content field to tell this earlier
                  if c.records.exists(r => status(r) != Deleted) then
                    unsupportedDeletes(paths.root)
                else
                  c.records
                    .foreach { r =>
                      if status(r) != Deleted then
                        files += (dataFile(r, mf.path, paths, specs.get(specId), fields) -> specId)
                    }
              }
            val usedSpecs = files
              .map(_._2)
              .distinct
              .toSeq
              .map(id =>
                specs.getOrElse(
                  id,
                  throw ParquetException(s"${source} has no partition spec ${id}")
                )
              )
            Step.Done(resolved(paths.root, fields, usedSpecs, files.map(_._1).toIndexedSeq, source))
        )
        (snapshot.get("manifest-list"), snapshot.get("manifests")) match
          case (Some(JSONString(list)), _) =>
            Step.Fetch(
              IndexedSeq(paths.resolve(list)),
              bytes => readManifests(manifestList(Avro.read(bytes.head, list), paths.root))
            )
          case (_, Some(JSONArray(vs))) =>
            // Format v1 may list the manifests in the snapshot
            readManifests(
              vs.collect { case JSONString(p) =>
                Manifest(p, 0)
              }
            )
          case _ =>
            throw ParquetException(s"Snapshot ${id} in ${source} has no manifest list")
    end match

  end fromMetadata

  // ---- Manifests ----

  private case class Manifest(path: String, specId: Int)

  private val Deleted = 2

  private def status(entry: Any): Int =
    entry match
      case r: Map[?, ?] =>
        r.asInstanceOf[Map[String, Any]].get("status") match
          case Some(i: Int) =>
            i
          case _ =>
            0
      case _ =>
        0

  private def unsupportedDeletes(root: String): Nothing =
    throw ParquetException(
      s"The snapshot of ${root} has delete files (merge-on-read deletes), which Uni doesn't apply yet. Compact the table to rewrite them into data files"
    )

  private def manifestList(c: Avro.Container, root: String): IndexedSeq[Manifest] = c
    .records
    .flatMap {
      case r: Map[?, ?] =>
        val m    = r.asInstanceOf[Map[String, Any]]
        val path = m
          .get("manifest_path")
          .collect { case s: String =>
            s
          }
          .getOrElse(
            throw ParquetException(s"A manifest list entry of ${root} has no manifest_path")
          )
        val specId = m
          .get("partition_spec_id")
          .collect { case i: Int =>
            i
          }
          .getOrElse(0)
        val content = m
          .get("content")
          .collect { case i: Int =>
            i
          }
          .getOrElse(0)
        if content == 1 then
          def count(names: String*): Option[Long] = names
            .flatMap(m.get)
            .collectFirst {
              case i: Int =>
                i.toLong
              case l: Long =>
                l
            }
          val live =
            for
              added    <- count("added_files_count", "added_data_files_count")
              existing <- count("existing_files_count", "existing_data_files_count")
            yield added + existing
          if live.forall(_ > 0) then
            unsupportedDeletes(root)
          None
        else
          Some(Manifest(path, specId))
      case _ =>
        None
    }

  private def dataFile(
      entry: Any,
      manifest: String,
      paths: Paths,
      spec: Option[Seq[PartitionField]],
      fields: IndexedSeq[JSONObject]
  ): DataFile =
    val e = entry.asInstanceOf[Map[String, Any]]
    val d =
      e.get("data_file") match
        case Some(r: Map[?, ?]) =>
          r.asInstanceOf[Map[String, Any]]
        case _ =>
          throw ParquetException(s"An entry of ${manifest} has no data_file")
    val content = d
      .get("content")
      .collect { case i: Int =>
        i
      }
      .getOrElse(0)
    if content != 0 then
      unsupportedDeletes(paths.root)
    val path = d
      .get("file_path")
      .collect { case s: String =>
        s
      }
      .getOrElse(throw ParquetException(s"An entry of ${manifest} has no file_path"))
    val format = d
      .get("file_format")
      .collect { case s: String =>
        s
      }
      .getOrElse("PARQUET")
    if !format.equalsIgnoreCase("PARQUET") then
      throw ParquetException(
        s"${paths.root} has ${format} data files (${path}); Uni reads only Parquet"
      )
    val size = d
      .get("file_size_in_bytes")
      .collect {
        case l: Long =>
          l
        case i: Int =>
          i.toLong
      }
      .getOrElse(-1L)
    val partition =
      d.get("partition") match
        case Some(p: Map[?, ?]) =>
          p.asInstanceOf[Map[String, Any]]
        case _ =>
          Map.empty[String, Any]
    // Identity values by source column name; typed later when the partition columns are known
    val values =
      spec
        .getOrElse(Seq.empty)
        .filter(_.transform == "identity")
        .flatMap { pf =>
          nameOf(fields, pf.sourceId).map(n => n -> partition.getOrElse(pf.name, null))
        }
        .toMap
    DataFile(paths.resolve(path), size, values)

  end dataFile

  private def resolved(
      root: String,
      fields: IndexedSeq[JSONObject],
      specs: Seq[Seq[PartitionField]],
      files: IndexedSeq[DataFile],
      source: String
  ): Resolved =
    val columns = fields.map(column(_, source))
    // An identity source column is a partition column when every spec of the files has it as an
    // identity field and its type is one a partition column can hold
    val identities = specs.map(
      _.filter(_.transform == "identity").flatMap(pf => nameOf(fields, pf.sourceId)).toSet
    )
    val inAll =
      if identities.isEmpty then
        Set.empty[String]
      else
        identities.reduce(_ intersect _)
    val partitionColumns = fields.flatMap { f =>
      val name = string(f, "name").getOrElse("")
      if inAll.contains(name) then
        partitionType(f.get("type")).map(name -> _)
      else
        None
    }
    val keys     = partitionColumns.map(_._1).toSet
    val required = identities.flatten.toSet -- keys
    val typed    = files.map { f =>
      f.copy(partitions =
        partitionColumns
          .map((name, tpe) => name -> partitionValue(f.partitions.getOrElse(name, null), tpe))
          .toMap
      )
    }
    Resolved(root, columns, partitionColumns, required, typed.sortBy(_.path))

  end resolved

  private def partitionType(t: Option[JSONValue]): Option[ParquetType.Primitive] =
    t match
      case Some(JSONString("string")) =>
        Some(ParquetType.STRING)
      case Some(JSONString("int")) =>
        Some(ParquetType.INT32)
      case Some(JSONString("long")) =>
        Some(ParquetType.INT64)
      case Some(JSONString("date")) =>
        Some(ParquetType.DATE)
      case _ =>
        None

  private def partitionValue(v: Any, tpe: ParquetType.Primitive): Any =
    (v, tpe.logicalType) match
      case (null, _) =>
        null
      case (days: Int, Some(LogicalType.Date)) =>
        LocalDate.ofEpochDay(days.toLong)
      case (i: Int, _) if tpe.physicalType == PhysicalType.INT64 =>
        i.toLong
      case (other, _) =>
        other

  // ---- Schemas and partition specs ----

  private case class PartitionField(sourceId: Int, name: String, transform: String)

  /** Each schema's top-level fields by schema id */
  private def schemasOf(m: JSONObject, source: String): Map[Int, IndexedSeq[JSONObject]] =
    def fieldsOf(s: JSONObject): IndexedSeq[JSONObject] =
      s.get("fields") match
        case Some(JSONArray(fs)) =>
          fs.collect { case f: JSONObject =>
            f
          }
        case _ =>
          IndexedSeq.empty
    val listed =
      m.get("schemas") match
        case Some(JSONArray(vs)) =>
          vs.collect { case s: JSONObject =>
            long(s, "schema-id").map(_.toInt).getOrElse(0) -> fieldsOf(s)
          }
        case _ =>
          IndexedSeq.empty
    val single =
      m.get("schema") match
        case Some(s: JSONObject) =>
          Seq(long(s, "schema-id").map(_.toInt).getOrElse(0) -> fieldsOf(s))
        case _ =>
          Seq.empty
    val all = (single ++ listed).toMap
    if all.isEmpty then
      throw ParquetException(s"${source} has no schema")
    all

  private def specsOf(m: JSONObject): Map[Int, Seq[PartitionField]] =
    def fieldsOf(fs: IndexedSeq[JSONValue]): Seq[PartitionField] = fs.collect {
      case f: JSONObject =>
        PartitionField(
          long(f, "source-id").map(_.toInt).getOrElse(-1),
          string(f, "name").getOrElse(""),
          string(f, "transform").getOrElse("")
        )
    }
    val listed =
      m.get("partition-specs") match
        case Some(JSONArray(vs)) =>
          vs.collect { case s: JSONObject =>
            long(s, "spec-id").map(_.toInt).getOrElse(0) -> (
              s.get("fields") match
                case Some(JSONArray(fs)) =>
                  fieldsOf(fs)
                case _ =>
                  Seq.empty
            )
          }
        case _ =>
          IndexedSeq.empty
    val v1 =
      m.get("partition-spec") match
        case Some(JSONArray(fs)) =>
          Seq(0 -> fieldsOf(fs))
        case _ =>
          Seq.empty
    (v1 ++ listed).toMap

  end specsOf

  private def nameOf(fields: IndexedSeq[JSONObject], id: Int): Option[String] = fields
    .find(f => long(f, "id").contains(id.toLong))
    .flatMap(string(_, "name"))

  /**
    * The dataset matches columns by name, so a field id must keep its name in every schema, and a
    * name must keep its field id: a rename, or a column dropped and added again, would read the
    * wrong values from older files
    */
  private def checkSchemaHistory(schemas: Seq[IndexedSeq[JSONObject]], source: String): Unit =
    val nameOfId = mutable.HashMap.empty[Long, String]
    val idOfName = mutable.HashMap.empty[String, Long]
    def visit(prefix: String, fields: IndexedSeq[JSONObject]): Unit = fields.foreach { f =>
      val name = s"${prefix}${string(f, "name").getOrElse("")}"
      long(f, "id").foreach(id => record(id, name))
      nested(name, f.get("type"))
    }
    def record(id: Long, name: String): Unit =
      nameOfId.get(id) match
        case Some(prev) if prev != name =>
          throw ParquetException(
            s"Column ${prev} of ${source} was renamed to ${name}. Uni matches columns by name, so it can't read a table with renamed columns yet"
          )
        case _ =>
          nameOfId(id) = name
      idOfName.get(name) match
        case Some(prev) if prev != id =>
          throw ParquetException(
            s"Column ${name} of ${source} was dropped and added again. Uni matches columns by name, so older files would show the dropped column's values"
          )
        case _ =>
          idOfName(name) = id
    def nested(name: String, t: Option[JSONValue]): Unit =
      t match
        case Some(o: JSONObject) =>
          string(o, "type") match
            case Some("struct") =>
              o.get("fields") match
                case Some(JSONArray(fs)) =>
                  visit(
                    s"${name}.",
                    fs.collect { case f: JSONObject =>
                      f
                    }
                  )
                case _ =>
            case Some("list") =>
              long(o, "element-id").foreach(record(_, s"${name}.element"))
              nested(s"${name}.element", o.get("element"))
            case Some("map") =>
              long(o, "key-id").foreach(record(_, s"${name}.key"))
              long(o, "value-id").foreach(record(_, s"${name}.value"))
              nested(s"${name}.key", o.get("key"))
              nested(s"${name}.value", o.get("value"))
            case _ =>
        case _ =>
    schemas.foreach(visit("", _))

  end checkSchemaHistory

  // ---- Iceberg types ----

  private val DecimalType = """decimal\(\s*(\d+)\s*,\s*(\d+)\s*\)""".r
  private val FixedType   = """fixed\[\s*(\d+)\s*\]""".r

  private def column(f: JSONObject, source: String): ParquetColumn =
    val name = string(f, "name").getOrElse("")
    ParquetColumn(
      name,
      typeOf(name, f.get("type"), source),
      repetition(f.get("required").contains(JSONBoolean(true)))
    )

  private def repetition(required: Boolean): Repetition =
    if required then
      Repetition.REQUIRED
    else
      Repetition.OPTIONAL

  private def typeOf(name: String, t: Option[JSONValue], source: String): ParquetType =
    def unsupported(desc: String): Nothing =
      throw ParquetException(
        s"Column ${name} of ${source} has the Iceberg type ${desc}, which Uni doesn't read"
      )
    t match
      case Some(JSONString(s)) =>
        s match
          case "boolean" =>
            ParquetType.BOOLEAN
          case "int" =>
            ParquetType.INT32
          case "long" =>
            ParquetType.INT64
          case "float" =>
            ParquetType.FLOAT
          case "double" =>
            ParquetType.DOUBLE
          case "date" =>
            ParquetType.DATE
          case "time" =>
            ParquetType.TIME_MICROS
          case "timestamp" =>
            ParquetType.Primitive(
              PhysicalType.INT64,
              Some(LogicalType.Timestamp(false, TimeUnit.MICROS))
            )
          case "timestamptz" =>
            ParquetType.TIMESTAMP_MICROS
          case "string" =>
            ParquetType.STRING
          case "uuid" =>
            ParquetType.UUID
          case "binary" =>
            ParquetType.BINARY
          case DecimalType(p, sc) =>
            ParquetType.decimal(p.toInt, sc.toInt)
          case FixedType(n) =>
            ParquetType.fixed(n.toInt)
          case other =>
            unsupported(other)
      case Some(o: JSONObject) =>
        string(o, "type") match
          case Some("struct") =>
            ParquetType.Struct(
              o.get("fields") match
                case Some(JSONArray(fs)) =>
                  fs.collect { case f: JSONObject =>
                    column(f, source)
                  }
                case _ =>
                  Seq.empty
            )
          case Some("list") =>
            ParquetType.ListOf(
              typeOf(name, o.get("element"), source),
              !o.get("element-required").contains(JSONBoolean(true))
            )
          case Some("map") =>
            ParquetType.MapOf(
              typeOf(name, o.get("key"), source),
              typeOf(name, o.get("value"), source),
              !o.get("value-required").contains(JSONBoolean(true))
            )
          case _ =>
            unsupported(o.toJSON)
      case other =>
        unsupported(other.map(_.toJSON).getOrElse("(missing)"))
    end match

  end typeOf

  // ---- Paths ----

  /**
    * Resolves the absolute paths the metadata records. Paths under the table's recorded location
    * are read under the root the table is at now, so a copied or moved table reads too.
    */
  private case class Paths(root: String, location: String, objects: Boolean):
    private val base = normalize(location).stripSuffix("/")

    def resolve(p: String): String =
      val n = normalize(p)
      if n.startsWith(s"${base}/") then
        local(s"${root}${n.substring(base.length + 1)}")
      else if objects then
        if !n.contains("://") then
          outside(p)
        val uri  = ObjectUri.parse(n)
        val here = ObjectUri.parse(root)
        if uri.scheme != here.scheme || uri.bucket != here.bucket then
          outside(p)
        n
      else if n.startsWith("/") then
        local(n)
      else
        outside(p)

    // On Windows, `/C:/a` becomes `C:\a`, and a relocated path gets `\` separators
    private def local(path: String): String =
      if objects then
        path
      else
        ParquetDataset.localPath(path)

    private def outside(p: String): Nothing =
      throw ParquetException(
        s"The Iceberg table ${root} lists ${p}, which is outside the table location ${location} and not in the same ${
            if objects then
              "bucket"
            else
              "file system"
          }"
      )

    private def normalize(p: String): String =
      if p.startsWith("s3a://") || p.startsWith("s3n://") then
        s"s3${p.substring(3)}"
      else if p.startsWith("file:") then
        ParquetDataset.localPathOfFileUri(p).getOrElse(p)
      else
        p

  end Paths

  // ---- JSON helpers ----

  private def string(o: JSONObject, key: String): Option[String] = o
    .get(key)
    .collect { case JSONString(s) =>
      s
    }

  private def long(o: JSONObject, key: String): Option[Long] =
    o.get(key) match
      case Some(JSONLong(v)) =>
        Some(v)
      case Some(JSONDouble(v)) =>
        Some(v.toLong)
      case _ =>
        None

end IcebergTable
