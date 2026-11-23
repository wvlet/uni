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
  case class DataFile(
      path: String,
      size: Long,
      partitions: Map[String, Any],
      /** Column bounds from the manifest entry, for skipping the file by a filter */
      stats: Option[DatasetFile.Stats] = None
  )

  /** The table at the chosen snapshot */
  case class Resolved(
      root: String,
      /** The snapshot schema's top-level columns */
      columns: IndexedSeq[ParquetColumn],
      /** Identity partition columns whose values come from the manifests */
      partitionColumns: IndexedSeq[(String, ParquetType.Primitive)],
      /** Identity partition source columns that each file must hold, since no value fills them */
      requiredInFiles: Set[String],
      files: IndexedSeq[DataFile],
      /** The field IDs of the files' columns, for reading renamed and re-added columns */
      fieldIds: FieldIds = FieldIds.empty,
      /** The table properties */
      properties: Map[String, String] = Map.empty,
      /**
        * The `initial-default` of top-level primitive columns that have one (format version 3), for
        * files written before the column was added: a Boolean, Int, Long, Float, Double, BigInt
        * (the unscaled value of a decimal), or Array[Byte] (strings, binary, fixed, and UUIDs)
        */
      defaults: Map[String, Any] = Map.empty,
      /** The deleted row positions of each data file, sorted, from delete files and vectors */
      deletes: IcebergDeletes.Positions = Map.empty
  )

  /**
    * How data files map to the table's columns. Files whose top-level columns carry field IDs the
    * table has known are read by ID: a column takes its current name, and a dropped one is hidden.
    * Other files are read by name, which fails when the table renamed a column or added one back.
    */
  case class FieldIds(
      /** The current top-level column name of each field ID */
      current: Map[Int, String],
      /** Every top-level field ID of any schema */
      known: Set[Int],
      /** Why the table can't be read by name, if it can't */
      byNameError: Option[String]
  )

  object FieldIds:
    val empty: FieldIds = FieldIds(Map.empty, Set.empty, None)

    /** A name no column can have, for a file column the table dropped */
    def droppedName(id: Int): String = s"\u0000dropped-${id}"

  private val HadoopMetadata   = """v(\d+)\.metadata\.json""".r
  private val CatalogMetadata  = """(\d+)-.+\.metadata\.json""".r
  private val MaxFormatVersion = 3

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
            val v = String(bytes.head, StandardCharsets.UTF_8).trim
            // A writer updates the hint after it commits, so a newer vN can exist; iceberg-java
            // rolls forward the same way
            val newer = v
              .toIntOption
              .flatMap { hinted =>
                meta
                  .flatMap { l =>
                    l.segments.last match
                      case HadoopMetadata(n) if n.toLong > hinted =>
                        Some(n.toLong -> l)
                      case _ =>
                        None
                  }
                  .maxByOption(_._1)
                  .map(_._2)
              }
            val file =
              if isMetadataFile(v) then
                byName(v.split('/').last)
              else
                newer
                  .orElse(byName(s"v${v}.metadata.json"))
                  .orElse(byName(s"v${v}.gz.metadata.json"))
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
        s"${source} is an Iceberg format version ${formatVersion} table; Uni reads versions 1 to 3"
      )
    m.get("encryption-keys") match
      case Some(JSONArray(keys)) if keys.nonEmpty =>
        throw ParquetException(s"${source} is an encrypted Iceberg table, which Uni doesn't read")
      case _ =>
    val location = string(m, "location").getOrElse(
      throw ParquetException(s"${source} has no table location")
    )
    val paths       = Paths(givenRoot, location, objects)
    val schemas     = schemasOf(m, source)
    val byNameError = schemaHistoryError(schemas.values.toSeq, source)
    val properties  =
      m.get("properties") match
        case Some(JSONObject(ps)) =>
          ps.collect { case (k, JSONString(v)) =>
              k -> v
            }
            .toMap
        case _ =>
          Map.empty[String, String]
    val knownIds = schemas.values.flatten.flatMap(long(_, "id")).map(_.toInt).toSet
    val specs    = specsOf(m)

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
    def idsOf(fields: IndexedSeq[JSONObject]): FieldIds = FieldIds(
      fields.flatMap(f => long(f, "id").map(_.toInt -> string(f, "name").getOrElse(""))).toMap,
      knownIds,
      byNameError
    )
    def schemaFields(id: Int): IndexedSeq[JSONObject] = schemas.getOrElse(
      id,
      throw ParquetException(s"${source} has no schema with id ${id}")
    )

    wanted match
      case None =>
        // A table without snapshots: no files, the current schema and default spec
        val fields = schemaFields(currentSchemaId)
        val spec   = long(m, "default-spec-id").map(_.toInt).flatMap(specs.get).getOrElse(Seq.empty)
        Step.Done(
          resolved(paths.root, fields, Seq(spec), IndexedSeq.empty, source, idsOf(fields)).copy(
            properties = properties
          )
        )
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
        // The current snapshot reads with the current schema, which a schema change without a new
        // snapshot updates; time travel reads with the snapshot's own schema
        val fields = schemaFields(
          if current.contains(id) then
            currentSchemaId
          else
            long(snapshot, "schema-id").map(_.toInt).getOrElse(currentSchemaId)
        )
        def readManifests(manifests: IndexedSeq[Manifest]): Step = Step.Fetch(
          manifests.map(mf => paths.resolve(mf.path)),
          bytes =>
            val files     = mutable.ArrayBuffer.empty[(DataFile, Int)]
            val sequences = mutable.HashMap.empty[String, Long]
            val deletes   = mutable.ArrayBuffer.empty[IcebergDeletes.DeleteFile]
            manifests
              .zip(bytes)
              .foreach { (mf, b) =>
                val c      = Avro.read(b, mf.path)
                val specId = c.meta("partition-spec-id").flatMap(_.toIntOption).getOrElse(mf.specId)
                // A v1 manifest list has no content field, so the manifest's own metadata counts too
                val isDeletes = mf.deletes || c.meta("content").contains("deletes")
                c.records
                  .foreach { r =>
                    if status(r) != Deleted then
                      val seq = sequenceNumber(r, mf)
                      if isDeletes then
                        deletes += deleteFile(r, mf.path, paths, seq)
                      else
                        val f = dataFile(r, mf.path, paths, specs.get(specId), fields)
                        sequences(f.path) = seq
                        files += (f -> specId)
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
            val table = resolved(
              paths.root,
              fields,
              usedSpecs,
              files.map(_._1).toIndexedSeq,
              source,
              idsOf(fields)
            ).copy(properties = properties)
            if deletes.isEmpty then
              Step.Done(table)
            else
              val deletePaths = deletes.map(_.path).distinct.toIndexedSeq
              Step.Fetch(
                deletePaths,
                bytes =>
                  Step.Done(
                    table.copy(deletes =
                      IcebergDeletes.positions(
                        deletes.toIndexedSeq,
                        deletePaths.zip(bytes).toMap,
                        sequences.toMap,
                        paths.resolve
                      )
                    )
                  )
              )
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
                Manifest(p, 0, 0L)
              }
            )
          case _ =>
            throw ParquetException(s"Snapshot ${id} in ${source} has no manifest list")
    end match

  end fromMetadata

  // ---- Manifests ----

  /** A manifest of a snapshot, with its sequence number (0 in format version 1) */
  private case class Manifest(
      path: String,
      specId: Int,
      sequenceNumber: Long,
      /** Whether the manifest list says it lists delete files */
      deletes: Boolean = false
  )

  private val Added = 1

  /**
    * An entry's data sequence number: its own, or for a file added by the manifest's snapshot,
    * which may leave it null, the manifest's
    */
  private def sequenceNumber(entry: Any, mf: Manifest): Long =
    entry.asInstanceOf[Map[String, Any]].get("sequence_number") match
      case Some(l: Long) =>
        l
      case Some(i: Int) =>
        i.toLong
      case _ if status(entry) == Added =>
        mf.sequenceNumber
      case _ =>
        0L

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
        val deletes = m
          .get("content")
          .collect { case i: Int =>
            i
          }
          .contains(1)
        val seq = m
          .get("sequence_number")
          .collect {
            case l: Long =>
              l
            case i: Int =>
              i.toLong
          }
          .getOrElse(0L)
        Some(Manifest(path, specId, seq, deletes))
      case _ =>
        None
    }

  /** A live entry of a delete manifest */
  private def deleteFile(
      entry: Any,
      manifest: String,
      paths: Paths,
      sequenceNumber: Long
  ): IcebergDeletes.DeleteFile =
    val d =
      entry.asInstanceOf[Map[String, Any]].get("data_file") match
        case Some(r: Map[?, ?]) =>
          r.asInstanceOf[Map[String, Any]]
        case _ =>
          throw ParquetException(s"An entry of ${manifest} has no data_file")
    def str(key: String): Option[String] = d
      .get(key)
      .collect { case s: String =>
        s
      }
    def num(key: String): Option[Long] = d
      .get(key)
      .collect {
        case l: Long =>
          l
        case i: Int =>
          i.toLong
      }
    val path = str("file_path").getOrElse(
      throw ParquetException(s"An entry of ${manifest} has no file_path")
    )
    if num("content").contains(2L) then
      throw ParquetException(
        s"The snapshot of ${paths
            .root} has equality delete files (${path}), which Uni doesn't apply yet. Compact the table to rewrite them into data files"
      )
    IcebergDeletes.DeleteFile(
      paths.resolve(path),
      str("file_format").getOrElse("PARQUET"),
      sequenceNumber,
      str("referenced_data_file").map(paths.resolve),
      num("content_offset"),
      num("content_size_in_bytes")
    )

  end deleteFile

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
      throw ParquetException(s"Data manifest ${manifest} lists a delete file")
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
    DataFile(paths.resolve(path), size, values, statsOf(d, fields))

  end dataFile

  /**
    * The manifest entry's record count and bounds of top-level columns, keyed by the read schema's
    * column names. Iceberg bounds use the single-value binary serialization, which matches the
    * PLAIN encoding for the types kept here; a bound written before an `int` to `long` promotion is
    * widened. Other types (floating-point, decimals, nested) are left out
    */
  private def statsOf(
      d: Map[String, Any],
      fields: IndexedSeq[JSONObject]
  ): Option[DatasetFile.Stats] =
    def longOf(v: Any): Option[Long] =
      v match
        case l: Long =>
          Some(l)
        case i: Int =>
          Some(i.toLong)
        case _ =>
          None
    def entries(name: String): Map[Int, Any] =
      d.get(name) match
        case Some(xs: Seq[?]) =>
          xs.collect { case e: Map[?, ?] =>
              val m = e.asInstanceOf[Map[String, Any]]
              (m.get("key"), m.get("value")) match
                case (Some(k: Int), Some(v)) =>
                  Some(k -> v)
                case _ =>
                  None
            }
            .flatten
            .toMap
        case _ =>
          Map.empty
    d.get("record_count")
      .flatMap(longOf)
      .map { numRows =>
        val lower   = entries("lower_bounds")
        val upper   = entries("upper_bounds")
        val nulls   = entries("null_value_counts")
        val columns = fields.flatMap { f =>
          for
            id   <- long(f, "id").map(_.toInt)
            name <- string(f, "name")
            tpe  <- string(f, "type")
            if bounded(tpe)
          yield
            def bound(m: Map[Int, Any]): Option[Array[Byte]] = m
              .get(id)
              .collect { case b: Array[Byte] =>
                b
              }
              .flatMap(plainOf(tpe, _))
            name ->
              DatasetFile.ColumnStats(bound(lower), bound(upper), nulls.get(id).flatMap(longOf))
        }
        DatasetFile.Stats(numRows, columns.toMap)
      }

  end statsOf

  /** Iceberg primitive types whose bounds compare as PLAIN-encoded Parquet statistics */
  private def bounded(tpe: String): Boolean =
    tpe match
      case "boolean" | "int" | "long" | "date" | "timestamp" | "timestamptz" | "timestamp_ns" |
          "timestamptz_ns" | "string" =>
        true
      case _ =>
        false

  /** A bound in the PLAIN encoding of the column type, or None when its size doesn't fit */
  private def plainOf(tpe: String, b: Array[Byte]): Option[Array[Byte]] =
    tpe match
      case "boolean" =>
        Option.when(b.length == 1)(b)
      case "int" | "date" =>
        Option.when(b.length == 4)(b)
      case "timestamp" | "timestamptz" | "timestamp_ns" | "timestamptz_ns" =>
        Option.when(b.length == 8)(b)
      case "long" =>
        if b.length == 8 then
          Some(b)
        else if b.length == 4 then
          // Written as an int before the column was promoted to long: sign-extend (little-endian)
          val ext: Byte =
            if b(3) < 0 then
              -1
            else
              0
          Some(b ++ Array.fill(4)(ext))
        else
          None
      case _ =>
        Some(b)

  private def resolved(
      root: String,
      fields: IndexedSeq[JSONObject],
      specs: Seq[Seq[PartitionField]],
      files: IndexedSeq[DataFile],
      source: String,
      fieldIds: FieldIds
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
    val defaults = defaultsOf(fields, source)
    Resolved(
      root,
      columns,
      partitionColumns,
      // A default fills a partition source column that older files lack
      required -- defaults.keySet,
      typed.sortBy(_.path),
      fieldIds,
      defaults = defaults
    )

  end resolved

  // ---- Default values (format version 3) ----

  private def hasDefault(f: JSONObject): Boolean =
    f.get("initial-default") match
      case None | Some(JSONNull) =>
        false
      case _ =>
        true

  /** The initial defaults of top-level columns, as the values [[Resolved.defaults]] holds */
  private def defaultsOf(fields: IndexedSeq[JSONObject], source: String): Map[String, Any] =
    fields
      .filter(hasDefault)
      .map { f =>
        val name           = string(f, "name").getOrElse("")
        val v              = f.get("initial-default").get
        def bad(): Nothing =
          throw ParquetException(
            s"Column ${name} of ${source} has an initial-default ${v
                .toJSON}, which Uni can't read for its type"
          )
        val value: Any =
          (f.get("type"), v) match
            case (Some(JSONString(tpe)), _) =>
              try
                defaultValue(tpe, v).getOrElse(bad())
              catch
                case e: ParquetException =>
                  throw e
                case e: Exception =>
                  bad()
            case _ =>
              bad()
        name -> value
      }
      .toMap

  /** Iceberg's single-value JSON serialization of a primitive value, decoded */
  private def defaultValue(tpe: String, v: JSONValue): Option[Any] =
    def num: Option[BigDecimal] =
      v match
        case JSONLong(l) =>
          Some(BigDecimal(l))
        case JSONDouble(d) =>
          Some(BigDecimal(d))
        case _ =>
          None
    def str: Option[String] =
      v match
        case JSONString(s) =>
          Some(s)
        case _ =>
          None
    // Timestamps have no zone (`timestamp`) or end with `+00:00` (`timestamptz`)
    def instant(s: String): java.time.Instant =
      val utc =
        if s.endsWith("+00:00") then
          s.dropRight(6) + "Z"
        else if s.endsWith("Z") then
          s
        else
          s + "Z"
      java.time.Instant.parse(utc)
    def since(s: String, perSecond: Long): Long =
      val i = instant(s)
      Math.addExact(
        Math.multiplyExact(i.getEpochSecond, perSecond),
        i.getNano.toLong / (1000000000L / perSecond)
      )
    tpe match
      case "boolean" =>
        v match
          case JSONBoolean(b) =>
            Some(b)
          case _ =>
            None
      case "int" =>
        num.map(_.toIntExact)
      case "long" =>
        num.map(_.toLongExact)
      case "float" =>
        num.map(_.toFloat)
      case "double" =>
        num.map(_.toDouble)
      case "string" =>
        str.map(_.getBytes(StandardCharsets.UTF_8))
      case "date" =>
        str.map(s => LocalDate.parse(s).toEpochDay.toInt)
      case "time" =>
        str.map(s => wvlet.uni.weaver.codec.PrimitiveWeaver.parseLocalTime(s).toNanoOfDay / 1000L)
      case "timestamp" | "timestamptz" =>
        str.map(since(_, 1000000L))
      case "timestamp_ns" | "timestamptz_ns" =>
        str.map(since(_, 1000000000L))
      case "uuid" =>
        str.map { s =>
          val u = java.util.UUID.fromString(s)
          val b = java.nio.ByteBuffer.allocate(16)
          b.putLong(u.getMostSignificantBits).putLong(u.getLeastSignificantBits)
          b.array()
        }
      case "binary" =>
        str.map(hex)
      case FixedType(n) =>
        str.map(hex).filter(_.length == n.toInt)
      case DecimalType(_, sc) =>
        str.map(s => BigDecimal(s).setScale(sc.toInt).bigDecimal.unscaledValue).map(BigInt(_))
      case _ =>
        None
    end match

  end defaultValue

  private def hex(s: String): Array[Byte] =
    require(s.length % 2 == 0, s"Odd-length hex string: ${s}")
    Array.tabulate(s.length / 2)(i => Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16).toByte)

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
    * Files without field IDs are matched by name, so a field id must keep its name in every schema,
    * and a name must keep its field id: a rename, or a column dropped and added again, would read
    * the wrong values from older files
    */
  private def schemaHistoryError(
      schemas: Seq[IndexedSeq[JSONObject]],
      source: String
  ): Option[String] =
    try
      checkSchemaHistory(schemas, source)
      None
    catch
      case e: ParquetException =>
        Some(e.getMessage)

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
            s"Column ${prev} of ${source} was renamed to ${name}. Its data files have no field IDs, so Uni matches columns by name and can't read the renamed column"
          )
        case _ =>
          nameOfId(id) = name
      idOfName.get(name) match
        case Some(prev) if prev != id =>
          throw ParquetException(
            s"Column ${name} of ${source} was dropped and added again. Its data files have no field IDs, so Uni matches columns by name, and older files would show the dropped column's values"
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
  // geometry(crs) and geography(crs, algorithm); a CRS such as `srid:4326` has no comma
  private val GeometryType  = """geometry\(([^,)]+)\)""".r
  private val GeographyType = """geography\(([^,)]+)(?:,([^)]+))?\)""".r

  /** Geospatial values: WKB in a binary column */
  private def geo(lt: LogicalType): ParquetType = ParquetType.Primitive(
    PhysicalType.BYTE_ARRAY,
    Some(lt)
  )

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
          case "timestamp_ns" =>
            ParquetType.Primitive(
              PhysicalType.INT64,
              Some(LogicalType.Timestamp(false, TimeUnit.NANOS))
            )
          case "timestamptz_ns" =>
            ParquetType.TIMESTAMP_NANOS
          case "unknown" =>
            // Always null, and never stored in data files
            ParquetType.Primitive(PhysicalType.INT32, Some(LogicalType.Unknown))
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
          case "variant" =>
            ParquetType.VariantGroup
          case "geometry" =>
            geo(LogicalType.Geometry(None))
          case GeometryType(crs) =>
            geo(LogicalType.Geometry(Some(crs.trim)))
          case "geography" =>
            geo(LogicalType.Geography(None, None))
          case GeographyType(crs, algorithm) =>
            geo(LogicalType.Geography(Some(crs.trim), Option(algorithm).map(_.trim.toLowerCase)))
          case other =>
            unsupported(other)
      case Some(o: JSONObject) =>
        string(o, "type") match
          case Some("struct") =>
            ParquetType.Struct(
              o.get("fields") match
                case Some(JSONArray(fs)) =>
                  fs.collect { case f: JSONObject =>
                    if hasDefault(f) then
                      throw ParquetException(
                        s"Field ${string(f, "name").getOrElse(
                            ""
                          )} of column ${name} in ${source} has an initial-default, which Uni reads only for top-level columns"
                      )
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
