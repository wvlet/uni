package wvlet.uni.parquet

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.Repetition
import wvlet.uni.parquet.format.RowGroupMeta
import wvlet.uni.parquet.format.TimeUnit

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.collection.immutable.VectorMap

/**
  * Writes Apache Iceberg tables: binds the write schema to field IDs, writes the manifest and
  * manifest list (Avro) for the new data files, and commits a snapshot by creating the next
  * `metadata/vN.metadata.json` only if it doesn't exist yet (a Hadoop table).
  *
  * The I/O is a chain of [[Step]]s, so the sync and async writers run the same commit.
  */
private[parquet] object IcebergWrite:
  import IcebergTable.MetadataDir
  import IcebergTable.VersionHint

  /** I/O the commit needs; each step continues with its result */
  sealed trait Step[+A]
  object Step:
    /** The names of the files directly in the folder `dir` (which ends with a separator) */
    case class ListDir[A](dir: String, next: Seq[String] => Step[A])         extends Step[A]
    case class Fetch[A](path: String, next: Array[Byte] => Step[A])          extends Step[A]
    case class Put[A](path: String, bytes: Array[Byte], next: () => Step[A]) extends Step[A]

    /** Creates the file only if it doesn't exist; continues with whether this call created it */
    case class Create[A](path: String, bytes: Array[Byte], next: Boolean => Step[A]) extends Step[A]

    case class Delete[A](path: String, next: () => Step[A]) extends Step[A]
    case class Done[A](value: A)                            extends Step[A]

  /** Where the table's files go: `root` ends with the platform separator or `/` */
  case class Layout(root: String, objects: Boolean, location: String):
    private val sep =
      if objects then
        "/"
      else
        wvlet.uni.io.IOPath.separator

    val metadataDir: String            = s"${root}${MetadataDir}${sep}"
    def metadata(name: String): String = s"${metadataDir}${name}"

    /** The absolute URI of a file of this write, as the metadata records it */
    def uriOf(path: String): String =
      val rel = path.substring(root.length).replace('\\', '/')
      s"${location}/${rel}"

  /** A Hadoop table's current metadata */
  case class Table(version: Int, path: String, metadata: JSONObject)

  /** The folder under the root that holds the data files */
  val DataDir = "data"

  /** A `file:` URI for an absolute local path, as Iceberg metadata records local files */
  def fileUri(path: String): String =
    val p = path.replace('\\', '/')
    if p.startsWith("/") then
      s"file://${p}"
    else
      s"file:///${p}"

  /** The names of the objects directly under the prefix `dir` */
  def namesUnder(
      dir: wvlet.uni.storage.ObjectUri,
      listed: Seq[wvlet.uni.storage.ObjectInfo]
  ): Seq[String] = listed.flatMap { o =>
    val key = o.uri.key
    if key.startsWith(dir.key) then
      val name = key.substring(dir.key.length)
      if name.nonEmpty && !name.contains('/') then
        Some(name)
      else
        None
    else
      None
  }

  /** Manifest entry statuses */
  private val Existing = 0
  private val Deleted  = 2

  import IcebergTable.CatalogMetadata
  import IcebergTable.HadoopMetadata

  private val MaxAttempts = 4

  // ---- Loading ----

  /** Reads the newest `vN.metadata.json`, or none when the table doesn't exist yet */
  def load(layout: Layout): Step[Option[Table]] = Step.ListDir(
    layout.metadataDir,
    names =>
      val metadataFiles = names.filter(IcebergTable.isMetadataFile)
      val versions      = metadataFiles.collect { case n @ HadoopMetadata(v) =>
        v.toInt -> n
      }
      if versions.isEmpty then
        metadataFiles.collectFirst { case n @ CatalogMetadata(_) =>
          n
        } match
          case Some(n) =>
            throw ParquetException(
              s"${layout.root} is an Iceberg table that a catalog owns (${layout
                  .metadataDir}${n}). Uni writes only Hadoop tables (metadata/vN.metadata.json); commit through the table's catalog instead"
            )
          case None =>
            metadataFiles.headOption match
              case Some(n) =>
                throw ParquetException(
                  s"${layout
                      .metadataDir}${n} is a metadata file Uni can't commit after; Uni writes only Hadoop tables (metadata/vN.metadata.json)"
                )
              case None =>
                Step.Done(None)
      else
        // A compressed version is read like a plain one; the next commit writes plain JSON
        val v    = versions.map(_._1).max
        val path = layout.metadata(versions.filter(_._1 == v).map(_._2).minBy(n => (n.length, n)))
        Step.Fetch(
          path,
          bytes =>
            JSON.parse(IcebergTable.metadataText(path, bytes)) match
              case m: JSONObject =>
                Step.Done(Some(Table(v, path, m)))
              case _ =>
                throw ParquetException(s"${path} is not a JSON object")
        )
      end if
  )

  // ---- Planning ----

  /** An identity partition field of the spec */
  case class PartitionField(name: String, source: String, sourceId: Int, fieldId: Int, tpe: String)

  /** Which live data files of the base snapshot a commit removes */
  enum Removal:
    /** None: the commit only adds files */
    case Keep

    /** Every live file: the commit replaces the table's rows */
    case All

    /** These files, by the paths [[ParquetDataset.files]] lists (a copy-on-write rewrite) */
    case Files(paths: Set[String])

  /** What the data files need before any is written, and what the commit builds on */
  case class Plan(
      layout: Layout,
      base: Option[Table],
      /** The file schema with the table's field IDs; it keeps the partition columns */
      fileSchema: ParquetSchema,
      /** The identity partition columns, in spec order */
      partitionBy: IndexedSeq[String],
      schemaId: Int,
      /** The table schema, as Iceberg JSON with `schema-id` */
      schema: JSONObject,
      specId: Int,
      spec: IndexedSeq[PartitionField],
      lastColumnId: Int,
      removal: Removal = Removal.Keep,
      /** Whether the commit adds [[schema]] to the table's schemas and makes it current */
      addsSchema: Boolean = false,
      /**
        * Whether the commit adds [[spec]] to the table's partition specs and makes it the default
        */
      addsSpec: Boolean = false,
      /** Table properties the commit sets */
      properties: Map[String, String] = Map.empty,
      /** The table's format version after the commit: 2, or 3 with row lineage */
      formatVersion: Int = 2
  ):
    /**
      * Sets table properties. `format-version` isn't kept as a property: it picks the version of a
      * new table, or upgrades a version 2 table to 3, as Iceberg's Java library does
      */
    def withProperties(ps: Map[String, String]): Plan =
      val version = ps
        .get(FormatVersionProperty)
        .map(targetVersion(layout, formatVersion, _))
        .getOrElse(formatVersion)
      val p = copy(properties = properties ++ (ps - FormatVersionProperty), formatVersion = version)
      p.checkTypes()
      p

    /** Fails when the schema has a type that needs a higher format version than the table's */
    def checkTypes(): Unit =
      if formatVersion < 3 then
        v3Types(schema)
          .headOption
          .foreach((name, tpe) => throw v3TypeError(name, tpe, formatVersion))

  end Plan

  /** The table property that sets the format version */
  val FormatVersionProperty = "format-version"

  /** The version a `format-version` property asks for: 2 or 3, and not below the current one */
  private def targetVersion(layout: Layout, current: Int, value: String): Int =
    val n = value
      .trim
      .toIntOption
      .getOrElse(
        throw IllegalArgumentException(s"${FormatVersionProperty} must be 2 or 3: ${value}")
      )
    if n != 2 && n != 3 then
      throw IllegalArgumentException(s"${FormatVersionProperty} must be 2 or 3: ${value}")
    if n < current then
      throw ParquetException(
        s"${layout
            .root} is an Iceberg format version ${current} table; its version can't go down to ${n}"
      )
    n

  private def v3TypeError(name: String, tpe: String, version: Int): ParquetException =
    ParquetException(
      s"Column ${name} is ${tpe}, which Iceberg format version ${version} can't hold. Set the table property format-version to 3 (withTableProperties or IcebergTableChange.SetProperty)"
    )

  /** Columns of a schema's JSON whose types need format version 3, with their types */
  private def v3Types(schema: JSONObject): Seq[(String, String)] =
    def visit(name: String, t: Option[JSONValue]): Seq[(String, String)] =
      t match
        case Some(JSONString(tpe)) if tpe == "timestamp_ns" || tpe == "timestamptz_ns" =>
          Seq(name -> tpe)
        case Some(o: JSONObject) =>
          structFields(o).flatMap(f =>
            visit(s"${name}.${string(f, "name").getOrElse("")}", f.get("type"))
          ) ++
            Seq("element", "key", "value").flatMap(k =>
              o.get(k).toSeq.flatMap(v => visit(name, Some(v)))
            )
        case _ =>
          Seq.empty
    structFields(schema).flatMap(f => visit(string(f, "name").getOrElse(""), f.get("type")))

  /**
    * Binds the write to the table: field IDs from the table schema (or new ones for a new table),
    * and the partition spec from `withPartitionBy` (or the table's spec when none is given).
    *
    * [[ParquetWriteMode.Overwrite]] replaces the table's rows; with `replacedFiles`, it removes
    * only those data files. A replace whose rows don't fit the table schema adds a new schema.
    */
  def plan(
      layout: Layout,
      base: Option[Table],
      schema: ParquetSchema,
      partitionBy: Seq[String],
      mode: ParquetWriteMode,
      replacedFiles: Seq[String] = Seq.empty
  ): Plan =
    if mode == ParquetWriteMode.OverwritePartitions then
      throw ParquetException(
        s"Uni doesn't overwrite partitions of Iceberg tables yet; write with Overwrite to replace ${layout
            .root}, or Append to add a snapshot"
      )
    if replacedFiles.nonEmpty && mode != ParquetWriteMode.Overwrite then
      throw IllegalArgumentException(
        s"withReplacedFiles needs ParquetWriteMode.Overwrite, but the mode is ${mode}"
      )
    base match
      case Some(t) =>
        mode match
          case ParquetWriteMode.ErrorIfExists =>
            throw ParquetException(
              s"${layout.root} is already an Iceberg table (${t
                  .path}). Write withMode(ParquetWriteMode.Append) to add a snapshot"
            )
          case ParquetWriteMode.Overwrite if replacedFiles.nonEmpty =>
            planAppend(layout, t, schema, partitionBy, Removal.Files(replacedFiles.toSet))
          case ParquetWriteMode.Overwrite =>
            planReplace(layout, t, schema, partitionBy)
          case _ =>
            planAppend(layout, t, schema, partitionBy, Removal.Keep)
      case None =>
        if replacedFiles.nonEmpty then
          throw ParquetException(
            s"${layout.root} has no Iceberg table, so it has no data files to replace"
          )
        planCreate(layout, schema, partitionBy)

  end plan

  private def planCreate(layout: Layout, schema: ParquetSchema, partitionBy: Seq[String]): Plan =
    var next          = 0
    def nextId(): Int =
      next += 1
      next
    val fields     = assignStruct(schema.fields, () => nextId())
    val fileSchema = ParquetSchema(schema.root.copy(children = fields))
    val spec       = partitionBy
      .toIndexedSeq
      .zipWithIndex
      .map { (name, i) =>
        val f = fields
          .find(_.name == name)
          .getOrElse(
            throw IllegalArgumentException(s"Partition column ${name} is not in the schema")
          )
        PartitionField(name, name, f.fieldId.get, 1000 + i, typeName(f))
      }
    spec.foreach(checkPartitionType)
    Plan(
      layout,
      None,
      fileSchema,
      partitionBy.toIndexedSeq,
      0,
      schemaJson(0, fields),
      0,
      spec,
      next
    )

  end planCreate

  /** The current schema of a table Uni can commit to: its ID and Iceberg JSON */
  private def currentSchema(t: Table): (Int, JSONObject) =
    val m       = t.metadata
    val version = long(m, "format-version").getOrElse(1L)
    if version != 2 && version != 3 then
      throw ParquetException(
        s"${t.path} is an Iceberg format version ${version} table; Uni writes only to version 2 and 3 tables"
      )
    val schemaId = long(m, "current-schema-id").map(_.toInt).getOrElse(0)
    val current  = array(m, "schemas")
      .collect { case s: JSONObject =>
        s
      }
      .find(s => long(s, "schema-id").contains(schemaId.toLong))
      .getOrElse(throw ParquetException(s"${t.path} has no schema with id ${schemaId}"))
    (schemaId, current)

  /** The default partition spec, with source columns from `tableFields` */
  private def defaultSpec(
      t: Table,
      tableFields: IndexedSeq[JSONObject]
  ): (Int, IndexedSeq[PartitionField]) =
    val m        = t.metadata
    val specId   = long(m, "default-spec-id").map(_.toInt).getOrElse(0)
    val specJson = objects(m, "partition-specs")
      .find(s => long(s, "spec-id").contains(specId.toLong))
      .getOrElse(throw ParquetException(s"${t.path} has no partition spec ${specId}"))
    val spec = array(specJson, "fields").collect { case f: JSONObject =>
      val name      = string(f, "name").getOrElse("")
      val transform = string(f, "transform").getOrElse("")
      val sourceId  = long(f, "source-id").map(_.toInt).getOrElse(-1)
      val source    = tableFields
        .find(c => long(c, "id").contains(sourceId.toLong))
        .flatMap(string(_, "name"))
        .getOrElse(
          throw ParquetException(
            s"Partition field ${name} of ${t.path} has a source column that isn't top-level"
          )
        )
      if transform != "identity" then
        throw ParquetException(
          s"${t
              .path} partitions by ${transform}(${source}); Uni writes only identity partitions yet"
        )
      val tpe = tableFields
        .find(c => long(c, "id").contains(sourceId.toLong))
        .flatMap(_.get("type"))
        .collect { case JSONString(s) =>
          s
        }
        .getOrElse("")
      PartitionField(name, source, sourceId, long(f, "field-id").map(_.toInt).getOrElse(-1), tpe)
    }
    spec.foreach(checkPartitionType)
    (specId, spec)

  end defaultSpec

  // Paths are recorded under the table's own location (for example s3a:// or file:/), so the
  // table keeps one scheme
  private def tableLayout(layout: Layout, t: Table): Layout = string(t.metadata, "location")
    .map(l => layout.copy(location = l.stripSuffix("/")))
    .getOrElse(layout)

  private def planAppend(
      layout: Layout,
      t: Table,
      schema: ParquetSchema,
      partitionBy: Seq[String],
      removal: Removal
  ): Plan =
    val m                = t.metadata
    val (schemaId, json) = currentSchema(t)
    val tableFields      = structFields(json)
    val bound            = bindStruct(schema.fields, tableFields, "", t.path)
    val fileSchema       = ParquetSchema(schema.root.copy(children = bound))
    val (specId, spec)   = defaultSpec(t, tableFields)
    val keys             = spec.map(_.source)
    if partitionBy.nonEmpty && partitionBy.toIndexedSeq != keys then
      throw ParquetException(
        s"${t.path} partitions by (${keys.mkString(
            ", "
          )}), but this write partitions by (${partitionBy.mkString(", ")})"
      )
    keys.foreach { k =>
      if schema.field(k).isEmpty then
        throw ParquetException(s"The rows have no partition column ${k}, which ${t.path} needs")
    }
    Plan(
      tableLayout(layout, t),
      Some(t),
      fileSchema,
      keys,
      schemaId,
      json,
      specId,
      spec,
      long(m, "last-column-id").map(_.toInt).getOrElse(0),
      removal,
      formatVersion = formatVersionOf(t)
    )

  end planAppend

  private def formatVersionOf(t: Table): Int =
    long(t.metadata, "format-version").getOrElse(1L).toInt

  /**
    * Replaces every row. Rows that fit the table schema keep it (and the partition spec, unless
    * `partitionBy` names other columns); other rows get a new schema with new field IDs, which
    * keeps the old partition columns when the new schema has them all.
    */
  private def planReplace(
      layout: Layout,
      t: Table,
      schema: ParquetSchema,
      partitionBy: Seq[String]
  ): Plan =
    val m           = t.metadata
    val (_, json)   = currentSchema(t)
    val tableFields = structFields(json)
    val fits        =
      try
        bindStruct(schema.fields, tableFields, "", t.path)
        true
      catch
        case _: ParquetException =>
          false
    // A spec Uni can't write (a non-identity transform) is replaced, not kept
    val oldKeys =
      try
        Some(defaultSpec(t, tableFields)._2.map(_.source))
      catch
        case _: ParquetException =>
          None
    val sameSpec = oldKeys.exists(k =>
      (
        partitionBy.isEmpty || partitionBy.toIndexedSeq == k
      ) && k.forall(schema.field(_).isDefined)
    )
    if fits && sameSpec then
      planAppend(layout, t, schema, partitionBy, Removal.All)
    else
      var next          = long(m, "last-column-id").map(_.toInt).getOrElse(0)
      def nextId(): Int =
        next += 1
        next
      val fields =
        if fits then
          bindStruct(schema.fields, tableFields, "", t.path)
        else
          assignStruct(schema.fields, () => nextId())
      val schemaId =
        if fits then
          currentSchema(t)._1
        else
          objects(m, "schemas").flatMap(long(_, "schema-id")).maxOption.getOrElse(-1L).toInt + 1
      val keys =
        if partitionBy.nonEmpty then
          partitionBy.toIndexedSeq
        else
          oldKeys.filter(_.forall(k => fields.exists(_.name == k))).getOrElse(IndexedSeq.empty)
      val lastPartitionId = long(m, "last-partition-id").map(_.toInt).getOrElse(999)
      val spec            = keys
        .zipWithIndex
        .map { (name, i) =>
          val f = fields
            .find(_.name == name)
            .getOrElse(
              throw IllegalArgumentException(s"Partition column ${name} is not in the schema")
            )
          PartitionField(name, name, f.fieldId.get, lastPartitionId + 1 + i, typeName(f))
        }
      spec.foreach(checkPartitionType)
      val specs = objects(m, "partition-specs")
      // A spec the table already has with the same identity fields is reused, with its field IDs
      def sameFields(s: JSONObject): Boolean =
        val fs = objects(s, "fields")
        fs.size == spec.size &&
        fs.zip(spec)
          .forall((f, p) =>
            long(f, "source-id").contains(p.sourceId.toLong) &&
              string(f, "transform").contains("identity")
          )
      val existing  = specs.find(sameFields)
      val reused    = existing.flatMap(long(_, "spec-id")).map(_.toInt)
      val boundSpec =
        existing match
          case Some(s) =>
            spec
              .zip(objects(s, "fields"))
              .map((p, f) =>
                p.copy(
                  name = string(f, "name").getOrElse(p.name),
                  fieldId = long(f, "field-id").map(_.toInt).getOrElse(p.fieldId)
                )
              )
          case None =>
            spec
      val specId = reused.getOrElse(
        specs.flatMap(long(_, "spec-id")).maxOption.getOrElse(-1L).toInt + 1
      )
      val tableSchema =
        if fits then
          json
        else
          schemaJson(schemaId, fields)
      Plan(
        tableLayout(layout, t),
        Some(t),
        ParquetSchema(schema.root.copy(children = fields)),
        keys,
        schemaId,
        tableSchema,
        specId,
        boundSpec,
        next,
        Removal.All,
        addsSchema = !fits,
        addsSpec = reused.isEmpty,
        formatVersion = formatVersionOf(t)
      )
    end if

  end planReplace

  private def checkPartitionType(p: PartitionField): Unit =
    p.tpe match
      case "string" | "int" | "long" | "date" =>
      case other                              =>
        throw ParquetException(
          s"Partition column ${p
              .source} has the Iceberg type ${other}; Uni writes identity partitions on string, int, long, and date columns"
        )

  // ---- Schema changes ----

  /** A committed metadata version */
  case class Altered(version: Int, metadataPath: String)

  /**
    * Commits the schema changes as a new current schema, in a new metadata version without a new
    * snapshot. Losing the commit race reloads the table and applies the changes again.
    */
  def alter(layout: Layout, changes: Seq[IcebergTableChange]): Step[Altered] =
    def attempt(n: Int): Step[Altered] =
      flatMap(load(layout)) {
        case None =>
          throw ParquetException(s"${layout.root} is not an Iceberg table")
        case Some(t) =>
          val json = alteredMetadata(layout, t, changes)
          val path = layout.metadata(s"v${t.version + 1}.metadata.json")
          Step.Create(
            path,
            json.toJSON.getBytes(StandardCharsets.UTF_8),
            created =>
              if created then
                Step.Done(Altered(t.version + 1, path))
              else if n < MaxAttempts then
                attempt(n + 1)
              else
                throw ParquetException(
                  s"Another writer kept committing to ${layout
                      .root} first; gave up after ${n} attempts"
                )
          )
      }
    attempt(1)

  /** The hint after a schema change */
  def hint(layout: Layout, a: Altered): (String, Array[Byte]) = hint(layout, a.version)

  /** `version-hint.text` naming metadata version `version` */
  def hint(layout: Layout, version: Int): (String, Array[Byte]) =
    layout.metadata(VersionHint) -> version.toString.getBytes(StandardCharsets.UTF_8)

  private def alteredMetadata(
      layout: Layout,
      t: Table,
      changes: Seq[IcebergTableChange]
  ): JSONObject =
    import IcebergTableChange.*
    val m          = t.metadata
    var properties =
      m.get("properties") match
        case Some(JSONObject(ps)) =>
          ps
        case _ =>
          Seq.empty
    var schemaChanged    = false
    val (schemaId, json) = currentSchema(t)
    var fields           = structFields(json)
    var lastColumnId     = long(m, "last-column-id").map(_.toInt).getOrElse(0)
    def nextId(): Int    =
      lastColumnId += 1
      lastColumnId
    def nameOf(f: JSONObject): String  = string(f, "name").getOrElse("")
    def find(name: String): JSONObject = fields
      .find(nameOf(_) == name)
      .getOrElse(
        throw ParquetException(
          s"${layout.root} has no column ${name}. Columns: ${fields.map(nameOf).mkString(", ")}"
        )
      )
    def checkAbsent(name: String): Unit =
      if fields.exists(nameOf(_) == name) then
        throw ParquetException(s"${layout.root} already has a column ${name}")
    def replace(name: String)(f: JSONObject => JSONObject): Unit =
      fields = fields.map(c =>
        if nameOf(c) == name then
          f(c)
        else
          c
      )
    def updated(c: JSONObject, key: String, v: JSONValue): JSONObject = JSONObject(
      c.v
        .map((k, x) =>
          if k == key then
            k -> v
          else
            k -> x
        )
    )
    val specSources: Set[Long] =
      objects(m, "partition-specs")
        .find(s => long(s, "spec-id") == long(m, "default-spec-id"))
        .toSeq
        .flatMap(s => objects(s, "fields").flatMap(long(_, "source-id")))
        .toSet
    var formatVersion = formatVersionOf(t)
    changes.foreach {
      case SetProperty(FormatVersionProperty, value) =>
        // Upgrades the table as a write's withTableProperties does, without storing a property
        formatVersion = targetVersion(layout, formatVersion, value)
      case SetProperty(key, value) =>
        properties = properties.filterNot(_._1 == key) :+ (key -> JSONString(value))
      case RemoveProperty(key) =>
        properties = properties.filterNot(_._1 == key)
      case change =>
        schemaChanged = true
        schemaChange(change)
    }
    def schemaChange(change: IcebergTableChange): Unit =
      change match
        case SetProperty(_, _) | RemoveProperty(_) =>
        case AddColumn(column)                     =>
          checkAbsent(column.name)
          if column.repetition == Repetition.REQUIRED then
            throw ParquetException(
              s"Can't add the required column ${column.name} to ${layout
                  .root}: the existing rows have no value for it. Add it as optional"
            )
          val f = ParquetSchema.fromColumns("schema", Seq(column)).fields.head
          fields = fields :+ fieldJson(assignType(f, () => nextId(), Some(nextId())))
        case DropColumn(name) =>
          val c = find(name)
          if long(c, "id").exists(specSources.contains) then
            throw ParquetException(
              s"Can't drop ${name} from ${layout.root}: the table is partitioned by it"
            )
          if fields.size == 1 then
            throw ParquetException(s"Can't drop ${name}, the last column of ${layout.root}")
          fields = fields.filterNot(nameOf(_) == name)
        case RenameColumn(from, to) =>
          find(from)
          if from != to then
            checkAbsent(to)
            replace(from)(updated(_, "name", JSONString(to)))
        case WidenColumn(name, to) =>
          val c    = find(name)
          val from = c
            .get("type")
            .collect { case JSONString(s) =>
              normalizeType(s)
            }
            .getOrElse(
              throw ParquetException(s"Column ${name} of ${layout.root} is not a primitive column")
            )
          val field =
            ParquetSchema
              .fromColumns("schema", Seq(ParquetColumn(name, to, Repetition.OPTIONAL)))
              .fields
              .head
          val target = normalizeType(typeName(field))
          if from != target && !canWiden(from, target) then
            throw ParquetException(
              s"Can't change column ${name} of ${layout
                  .root} from ${from} to ${target}. Iceberg widens int to long, float to double, and a decimal to a larger precision with the same scale"
            )
          replace(name)(updated(_, "type", JSONString(target)))
        case MakeOptional(name) =>
          find(name)
          replace(name)(updated(_, "required", JSONBoolean(false)))
    val newId     = objects(m, "schemas").flatMap(long(_, "schema-id")).maxOption.getOrElse(-1L) + 1
    val newSchema = JSONObject(
      Seq(
        "type"                 -> JSONString("struct"),
        "schema-id"            -> JSONLong(newId),
        "identifier-field-ids" ->
          json.get("identifier-field-ids").getOrElse(JSONArray(IndexedSeq.empty)),
        "fields" -> JSONArray(fields)
      )
    )
    if formatVersion < 3 then
      v3Types(newSchema)
        .headOption
        .foreach((name, tpe) => throw v3TypeError(name, tpe, formatVersion))
    val now         = System.currentTimeMillis()
    val metadataLog = JSONObject(
      Seq(
        "timestamp-ms"  -> JSONLong(long(m, "last-updated-ms").getOrElse(now)),
        "metadata-file" -> JSONString(tableLayout(layout, t).uriOf(t.path))
      )
    )
    val versionUpdate =
      if formatVersion != formatVersionOf(t) then
        Seq(
          "format-version" -> JSONLong(formatVersion.toLong),
          "next-row-id"    -> JSONLong(long(m, "next-row-id").getOrElse(0L))
        )
      else
        Seq.empty
    val schemaUpdate =
      if schemaChanged then
        Seq(
          "schemas"           -> JSONArray(array(m, "schemas") :+ newSchema),
          "current-schema-id" -> JSONLong(newId),
          "last-column-id"    -> JSONLong(lastColumnId.toLong)
        )
      else
        Seq.empty
    val replaced =
      (
        schemaUpdate ++ versionUpdate ++
          Seq(
            "properties"      -> JSONObject(properties),
            "last-updated-ms" -> JSONLong(now),
            "metadata-log"    -> JSONArray(array(m, "metadata-log") :+ metadataLog)
          )
      ).toMap[String, JSONValue]
    JSONObject(m.v.filterNot((k, _) => replaced.contains(k)) ++ replaced.toSeq)

  end alteredMetadata

  private val DecimalOf = """decimal\((\d+),(\d+)\)""".r

  private def canWiden(from: String, to: String): Boolean =
    (from, to) match
      case ("int", "long") | ("float", "double") =>
        true
      case (DecimalOf(p1, s1), DecimalOf(p2, s2)) =>
        s1 == s2 && p2.toInt > p1.toInt
      case _ =>
        false

  // ---- Schemas ----

  /** Assigns IDs as iceberg-java does: a struct's fields first, then their nested types */
  private def assignStruct(
      fields: IndexedSeq[ParquetField],
      nextId: () => Int
  ): IndexedSeq[ParquetField] =
    val ids = fields.map(_ => nextId())
    fields.zip(ids).map((f, id) => assignType(f, nextId, Some(id)))

  private def assignType(f: ParquetField, nextId: () => Int, id: Option[Int]): ParquetField =
    typeName(f) // fails early on types Iceberg can't hold
    f match
      case p: ParquetField.Primitive =>
        p.copy(fieldId = id)
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) =>
            val (rep, elem) = listParts(g)
            val eid         = nextId()
            g.copy(
              fieldId = id,
              children = IndexedSeq(
                rep.copy(children = IndexedSeq(assignType(elem, nextId, Some(eid))))
              )
            )
          case Some(LogicalType.Map) =>
            val (rep, key, value) = mapParts(g)
            val kid               = nextId()
            val vid               = nextId()
            g.copy(
              fieldId = id,
              children = IndexedSeq(
                rep.copy(children =
                  IndexedSeq(
                    assignType(key, nextId, Some(kid)),
                    assignType(value, nextId, Some(vid))
                  )
                )
              )
            )
          case _ =>
            g.copy(fieldId = id, children = assignStruct(g.children, nextId))
    end match

  end assignType

  /** Takes the table's field IDs, by name; types must match */
  private def bindStruct(
      fields: IndexedSeq[ParquetField],
      table: IndexedSeq[JSONObject],
      prefix: String,
      source: String
  ): IndexedSeq[ParquetField] =
    val names = fields.map(_.name).toSet
    table.foreach { c =>
      val n = string(c, "name").getOrElse("")
      if c.get("required").contains(JSONBoolean(true)) && !names.contains(n) then
        throw ParquetException(
          s"Column ${prefix}${n} of ${source} is required, but the rows don't have it"
        )
    }
    fields.map { f =>
      val name = s"${prefix}${f.name}"
      val c    = table
        .find(c => string(c, "name").contains(f.name))
        .getOrElse(
          throw ParquetException(
            s"Column ${name} is not in the schema of ${source} (${table
                .flatMap(string(_, "name"))
                .mkString(", ")}). Add it with Parquet.alterIcebergTable first"
          )
        )
      if c.get("required").contains(JSONBoolean(true)) && f.isOptional then
        throw ParquetException(
          s"Column ${name} is required in ${source}, but the rows have it as optional"
        )
      bindType(f, c.get("type"), long(c, "id").map(_.toInt), name, source)
    }

  end bindStruct

  private def bindType(
      f: ParquetField,
      t: Option[JSONValue],
      id: Option[Int],
      name: String,
      source: String
  ): ParquetField =
    def mismatch(): Nothing =
      throw ParquetException(
        s"Column ${name} is ${describe(f)} in the rows but ${t
            .map(_.toJSON)
            .getOrElse("missing")} in ${source}"
      )
    (f, t) match
      case (p: ParquetField.Primitive, Some(JSONString(s))) =>
        if normalizeType(primitiveType(p, name)) != normalizeType(s) then
          mismatch()
        p.copy(fieldId = id)
      case (g: ParquetField.Group, Some(o: JSONObject)) =>
        (g.logicalType, string(o, "type")) match
          case (Some(LogicalType.List), Some("list")) =>
            val (rep, elem) = listParts(g)
            if o.get("element-required").contains(JSONBoolean(true)) && elem.isOptional then
              throw ParquetException(
                s"The elements of ${name} are required in ${source}, but optional in the rows"
              )
            val e = bindType(
              elem,
              o.get("element"),
              long(o, "element-id").map(_.toInt),
              s"${name}.element",
              source
            )
            g.copy(fieldId = id, children = IndexedSeq(rep.copy(children = IndexedSeq(e))))
          case (Some(LogicalType.Map), Some("map")) =>
            val (rep, key, value) = mapParts(g)
            if o.get("value-required").contains(JSONBoolean(true)) && value.isOptional then
              throw ParquetException(
                s"The values of ${name} are required in ${source}, but optional in the rows"
              )
            val k = bindType(
              key,
              o.get("key"),
              long(o, "key-id").map(_.toInt),
              s"${name}.key",
              source
            )
            val v = bindType(
              value,
              o.get("value"),
              long(o, "value-id").map(_.toInt),
              s"${name}.value",
              source
            )
            g.copy(fieldId = id, children = IndexedSeq(rep.copy(children = IndexedSeq(k, v))))
          case (None, Some("struct")) =>
            g.copy(
              fieldId = id,
              children = bindStruct(g.children, structFields(o), s"${name}.", source)
            )
          case _ =>
            mismatch()
      case _ =>
        mismatch()
    end match

  end bindType

  private def describe(f: ParquetField): String =
    f match
      case p: ParquetField.Primitive =>
        primitiveType(p, f.name)
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) =>
            "a list"
          case Some(LogicalType.Map) =>
            "a map"
          case _ =>
            "a struct"

  private def normalizeType(s: String): String = s.replaceAll("\\s", "")

  private def listParts(g: ParquetField.Group): (ParquetField.Group, ParquetField) =
    g.children match
      case IndexedSeq(rep: ParquetField.Group) if rep.isRepeated && rep.children.size == 1 =>
        (rep, rep.children.head)
      case _ =>
        throw ParquetException(
          s"List column ${g.pathString} doesn't use the three-level list layout Iceberg needs"
        )

  private def mapParts(g: ParquetField.Group): (ParquetField.Group, ParquetField, ParquetField) =
    g.children match
      case IndexedSeq(rep: ParquetField.Group) if rep.isRepeated && rep.children.size == 2 =>
        (rep, rep.children(0), rep.children(1))
      case _ =>
        throw ParquetException(s"Map column ${g.pathString} doesn't use the key_value layout")

  /** The Iceberg type of a field, as a type name (for nested types, a short description) */
  private def typeName(f: ParquetField): String =
    f match
      case p: ParquetField.Primitive =>
        primitiveType(p, p.pathString)
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) =>
            listParts(g)
            "list"
          case Some(LogicalType.Map) =>
            mapParts(g)
            "map"
          case None =>
            "struct"
          case Some(LogicalType.Variant) =>
            throw ParquetException(s"Column ${g.pathString} is a variant, which Uni doesn't write")
          case Some(other) =>
            throw ParquetException(
              s"Column ${g.pathString} is a ${other} group, which Iceberg tables can't hold"
            )

  /** The Iceberg type of a leaf column */
  def primitiveType(p: ParquetField.Primitive, name: String): String =
    def unsupported(what: String): Nothing =
      throw ParquetException(s"Column ${name} is ${what}, which Iceberg can't hold")
    (p.physicalType, p.logicalType) match
      case (PhysicalType.BOOLEAN, None) =>
        "boolean"
      case (PhysicalType.INT32, None) =>
        "int"
      case (PhysicalType.INT32, Some(LogicalType.Integer(bits, true))) if bits <= 32 =>
        "int"
      case (PhysicalType.INT32, Some(LogicalType.Integer(_, false))) =>
        unsupported("an unsigned integer")
      case (PhysicalType.INT32, Some(LogicalType.Date)) =>
        "date"
      case (PhysicalType.INT64, None) =>
        "long"
      case (PhysicalType.INT64, Some(LogicalType.Integer(64, true))) =>
        "long"
      case (PhysicalType.INT64, Some(LogicalType.Integer(_, false))) =>
        unsupported("an unsigned integer")
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(utc, TimeUnit.MICROS))) =>
        if utc then
          "timestamptz"
        else
          "timestamp"
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(utc, TimeUnit.NANOS))) =>
        // Format version 3 only; Plan.checkTypes refuses it for older tables
        if utc then
          "timestamptz_ns"
        else
          "timestamp_ns"
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, unit))) =>
        unsupported(s"a ${unit} timestamp (Iceberg stores microseconds or nanoseconds)")
      case (PhysicalType.INT64, Some(LogicalType.Time(_, TimeUnit.MICROS))) =>
        "time"
      case (_, Some(LogicalType.Time(_, unit))) =>
        unsupported(s"a ${unit} time (Iceberg stores microseconds)")
      case (PhysicalType.FLOAT, None) =>
        "float"
      case (PhysicalType.DOUBLE, None) =>
        "double"
      case (PhysicalType.BYTE_ARRAY, Some(LogicalType.String | LogicalType.Enum)) =>
        "string"
      case (PhysicalType.BYTE_ARRAY, None) =>
        "binary"
      case (PhysicalType.FIXED_LEN_BYTE_ARRAY, Some(LogicalType.Uuid)) if p.typeLength == 16 =>
        "uuid"
      case (PhysicalType.FIXED_LEN_BYTE_ARRAY, None) =>
        s"fixed[${p.typeLength}]"
      case (
            PhysicalType.INT32 | PhysicalType.INT64 | PhysicalType.FIXED_LEN_BYTE_ARRAY |
            PhysicalType.BYTE_ARRAY,
            Some(LogicalType.Decimal(scale, precision))
          ) =>
        s"decimal(${precision}, ${scale})"
      case (PhysicalType.INT96, _) =>
        unsupported("an INT96 timestamp")
      case (_, Some(LogicalType.Geometry(_) | LogicalType.Geography(_, _))) =>
        throw ParquetException(s"Column ${name} is a geospatial column, which Uni doesn't write")
      case (t, lt) =>
        unsupported(s"${t}${lt.map(l => s" (${l})").getOrElse("")}")
    end match

  end primitiveType

  private def schemaJson(id: Int, fields: IndexedSeq[ParquetField]): JSONObject = JSONObject(
    Seq(
      "type"                 -> JSONString("struct"),
      "schema-id"            -> JSONLong(id.toLong),
      "identifier-field-ids" -> JSONArray(IndexedSeq.empty),
      "fields"               -> JSONArray(fields.map(fieldJson))
    )
  )

  private def fieldJson(f: ParquetField): JSONObject = JSONObject(
    Seq(
      "id"       -> JSONLong(f.fieldId.get.toLong),
      "name"     -> JSONString(f.name),
      "required" -> JSONBoolean(!f.isOptional),
      "type"     -> typeJson(f)
    )
  )

  private def typeJson(f: ParquetField): JSONValue =
    f match
      case p: ParquetField.Primitive =>
        JSONString(primitiveType(p, p.pathString))
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) =>
            val (_, e) = listParts(g)
            JSONObject(
              Seq(
                "type"             -> JSONString("list"),
                "element-id"       -> JSONLong(e.fieldId.get.toLong),
                "element"          -> typeJson(e),
                "element-required" -> JSONBoolean(!e.isOptional)
              )
            )
          case Some(LogicalType.Map) =>
            val (_, k, v) = mapParts(g)
            JSONObject(
              Seq(
                "type"           -> JSONString("map"),
                "key-id"         -> JSONLong(k.fieldId.get.toLong),
                "key"            -> typeJson(k),
                "value-id"       -> JSONLong(v.fieldId.get.toLong),
                "value"          -> typeJson(v),
                "value-required" -> JSONBoolean(!v.isOptional)
              )
            )
          case _ =>
            JSONObject(
              Seq("type" -> JSONString("struct"), "fields" -> JSONArray(g.children.map(fieldJson)))
            )

  private def structFields(o: JSONObject): IndexedSeq[JSONObject] = array(o, "fields").collect {
    case f: JSONObject =>
      f
  }

  // ---- Data files ----

  /** A data file of this write, with what the manifest records about it */
  case class DataFile(
      path: String,
      partitions: VectorMap[String, Any],
      numRows: Long,
      size: Long,
      rowGroups: IndexedSeq[RowGroupMeta]
  )

  /** Per-column metrics by field ID, from the row-group statistics of a top-level leaf column */
  private case class Metrics(
      columnSizes: Seq[(Int, Long)],
      valueCounts: Seq[(Int, Long)],
      nullCounts: Seq[(Int, Long)],
      lower: Seq[(Int, Array[Byte])],
      upper: Seq[(Int, Array[Byte])]
  )

  private def metrics(schema: ParquetSchema, f: DataFile): Metrics =
    val sizes  = Seq.newBuilder[(Int, Long)]
    val counts = Seq.newBuilder[(Int, Long)]
    val nulls  = Seq.newBuilder[(Int, Long)]
    val lower  = Seq.newBuilder[(Int, Array[Byte])]
    val upper  = Seq.newBuilder[(Int, Array[Byte])]
    schema
      .columns
      .foreach { c =>
        c.fieldId
          .filter(_ => c.maxRepetitionLevel == 0)
          .foreach { id =>
            val chunks = f.rowGroups.flatMap(_.columns.lift(c.columnIndex))
            if chunks.size == f.rowGroups.size then
              sizes += id  -> chunks.map(_.totalCompressedSize).sum
              counts += id -> chunks.map(_.numValues).sum
              val stats = chunks.flatMap(_.statistics)
              if stats.size == chunks.size && stats.forall(_.nullCount.isDefined) then
                nulls += id -> stats.map(_.nullCount.get).sum
              boundOrder(c).foreach { cmp =>
                // Row groups with only nulls have no min or max
                val ranges     = stats.filterNot(_.isLegacyMinMax).flatMap(s => s.min.zip(s.max))
                val withValues = chunks.count(ch =>
                  ch.statistics.forall(s => !s.nullCount.contains(ch.numValues))
                )
                if ranges.nonEmpty && ranges.size == withValues then
                  lower += id ->
                    ranges
                      .map(_._1)
                      .reduce((a, b) =>
                        if cmp(a, b) <= 0 then
                          a
                        else
                          b
                      )
                  upper += id ->
                    ranges
                      .map(_._2)
                      .reduce((a, b) =>
                        if cmp(a, b) >= 0 then
                          a
                        else
                          b
                      )
              }
            end if
          }
      }
    Metrics(sizes.result(), counts.result(), nulls.result(), lower.result(), upper.result())

  end metrics

  /**
    * How to compare the PLAIN-encoded min and max of a column, for types whose Iceberg bound bytes
    * are the same bytes. Float, double, decimal, and UUID bounds are left out.
    */
  private def boundOrder(c: ParquetField.Primitive): Option[(Array[Byte], Array[Byte]) => Int] =
    def le(b: Array[Byte], n: Int): Long =
      var v = 0L
      var i = n - 1
      while i >= 0 do
        v =
          (
            v << 8
          ) | (b(i) & 0xffL)
        i -= 1
      v
    val unsigned: (Array[Byte], Array[Byte]) => Int = (a, b) => compareUnsigned(a, b)
    try
      primitiveType(c, c.pathString) match
        case "int" | "date" =>
          Some((a, b) => Integer.compare(le(a, 4).toInt, le(b, 4).toInt))
        case "long" | "timestamp" | "timestamptz" | "time" =>
          Some((a, b) => java.lang.Long.compare(le(a, 8), le(b, 8)))
        case "boolean" | "string" | "binary" =>
          Some(unsigned)
        case t if t.startsWith("fixed") =>
          Some(unsigned)
        case _ =>
          None
    catch
      case _: ParquetException =>
        None

  /** Unsigned lexicographic order; Scala.js has no `Arrays.compareUnsigned` */
  private def compareUnsigned(a: Array[Byte], b: Array[Byte]): Int =
    val n = math.min(a.length, b.length)
    var i = 0
    while i < n do
      val c =
        (
          a(i) & 0xff
        ) - (b(i) & 0xff)
      if c != 0 then
        return c
      i += 1
    a.length - b.length

  // ---- Avro schemas ----

  private def partitionAvroType(p: PartitionField): String =
    val t =
      p.tpe match
        case "date" =>
          """{"type":"int","logicalType":"date"}"""
        case other =>
          s""""${other}""""
    s"""{"name":${JSONString(p.name).toJSON},"type":["null",${t}],"default":null,"field-id":${p
        .fieldId}}"""

  private def mapType(keyId: Int, valueId: Int, value: String): String =
    s"""["null",{"type":"array","items":{"type":"record","name":"k${keyId}_v${valueId}","fields":[{"name":"key","type":"int","field-id":${keyId}},{"name":"value","type":"${value}","field-id":${valueId}}]},"logicalType":"map"}]"""

  private def manifestSchema(spec: IndexedSeq[PartitionField], formatVersion: Int): String =
    val partition                                     = spec.map(partitionAvroType).mkString(",")
    def opt(name: String, id: Int, t: String): String =
      s"""{"name":"${name}","type":${t},"default":null,"field-id":${id}}"""
    val dataFile =
      (
        Seq(
          """{"name":"content","type":"int","field-id":134}""",
          """{"name":"file_path","type":"string","field-id":100}""",
          """{"name":"file_format","type":"string","field-id":101}""",
          s"""{"name":"partition","type":{"type":"record","name":"r102","fields":[${partition}]},"field-id":102}""",
          """{"name":"record_count","type":"long","field-id":103}""",
          """{"name":"file_size_in_bytes","type":"long","field-id":104}""",
          opt("column_sizes", 108, mapType(117, 118, "long")),
          opt("value_counts", 109, mapType(119, 120, "long")),
          opt("null_value_counts", 110, mapType(121, 122, "long")),
          opt("nan_value_counts", 137, mapType(138, 139, "long")),
          opt("lower_bounds", 125, mapType(126, 127, "bytes")),
          opt("upper_bounds", 128, mapType(129, 130, "bytes")),
          opt("key_metadata", 131, """["null","bytes"]"""),
          opt(
            "split_offsets",
            132,
            """["null",{"type":"array","items":"long","element-id":133}]"""
          ),
          opt("equality_ids", 135, """["null",{"type":"array","items":"int","element-id":136}]"""),
          opt("sort_order_id", 140, """["null","int"]""")
        ) ++ (
          if formatVersion >= 3 then
            Seq(
              opt("first_row_id", 142, """["null","long"]"""),
              opt("referenced_data_file", 143, """["null","string"]"""),
              opt("content_offset", 144, """["null","long"]"""),
              opt("content_size_in_bytes", 145, """["null","long"]""")
            )
          else
            Seq.empty
        )
      ).mkString(",")
    s"""{"type":"record","name":"manifest_entry","fields":[{"name":"status","type":"int","field-id":0},${opt(
        "snapshot_id",
        1,
        """["null","long"]"""
      )},${opt("sequence_number", 3, """["null","long"]""")},${opt(
        "file_sequence_number",
        4,
        """["null","long"]"""
      )},{"name":"data_file","type":{"type":"record","name":"r2","fields":[${dataFile}]},"field-id":2}]}"""

  end manifestSchema

  private def manifestListSchema(formatVersion: Int): String =
    """{"type":"record","name":"manifest_file","fields":[""" + (
      Seq(
        """{"name":"manifest_path","type":"string","field-id":500}""",
        """{"name":"manifest_length","type":"long","field-id":501}""",
        """{"name":"partition_spec_id","type":"int","field-id":502}""",
        """{"name":"content","type":"int","field-id":517}""",
        """{"name":"sequence_number","type":"long","field-id":515}""",
        """{"name":"min_sequence_number","type":"long","field-id":516}""",
        """{"name":"added_snapshot_id","type":"long","field-id":503}""",
        """{"name":"added_files_count","type":"int","field-id":504}""",
        """{"name":"existing_files_count","type":"int","field-id":505}""",
        """{"name":"deleted_files_count","type":"int","field-id":506}""",
        """{"name":"added_rows_count","type":"long","field-id":512}""",
        """{"name":"existing_rows_count","type":"long","field-id":513}""",
        """{"name":"deleted_rows_count","type":"long","field-id":514}""",
        """{"name":"partitions","type":["null",{"type":"array","items":{"type":"record","name":"r508","fields":[{"name":"contains_null","type":"boolean","field-id":509},{"name":"contains_nan","type":["null","boolean"],"default":null,"field-id":518},{"name":"lower_bound","type":["null","bytes"],"default":null,"field-id":510},{"name":"upper_bound","type":["null","bytes"],"default":null,"field-id":511}]},"element-id":508}],"default":null,"field-id":507}""",
        """{"name":"key_metadata","type":["null","bytes"],"default":null,"field-id":519}"""
      ) ++ (
        if formatVersion >= 3 then
          Seq("""{"name":"first_row_id","type":["null","long"],"default":null,"field-id":520}""")
        else
          Seq.empty
      )
    ).mkString(",") + "]}"

  // ---- Values ----

  /** A partition value as the manifest's partition struct holds it */
  private def avroPartitionValue(v: Any, tpe: String): Any =
    (v, tpe) match
      case (null, _) =>
        null
      case (d: LocalDate, _) =>
        d.toEpochDay.toInt
      case (i: Int, "long") =>
        i.toLong
      case (l: Long, "int") =>
        l.toInt
      case (other, _) =>
        other

  /** Iceberg's single-value serialization of a partition value */
  private def boundBytes(v: Any): Array[Byte] =
    def le(x: Long, n: Int): Array[Byte] =
      Array.tabulate(n)(i =>
        (
          (
            x >>> (8 * i)
          ) & 0xff
        ).toByte
      )
    v match
      case s: String =>
        s.getBytes(StandardCharsets.UTF_8)
      case i: Int =>
        le(i.toLong, 4)
      case l: Long =>
        le(l, 8)
      case other =>
        throw IllegalStateException(s"Unexpected partition value ${other}")

  private def compareValues(a: Any, b: Any): Int =
    (a, b) match
      case (x: String, y: String) =>
        compareUnsigned(x.getBytes(StandardCharsets.UTF_8), y.getBytes(StandardCharsets.UTF_8))
      case (x: Int, y: Int) =>
        Integer.compare(x, y)
      case (x: Long, y: Long) =>
        java.lang.Long.compare(x, y)
      case _ =>
        0

  // ---- Commit ----

  /** The committed snapshot */
  case class Committed(snapshotId: Long, version: Int, metadataPath: String)

  /** A random positive ID; Scala Native has no SecureRandom, so not `UUID.randomUUID` */
  private def newId(): Long = scala.util.Random.nextLong() & Long.MaxValue

  private def newUuid(): String =
    val r   = scala.util.Random
    val msb =
      (
        r.nextLong() & ~0xf000L
      ) | 0x4000L
    val lsb =
      (
        r.nextLong() & 0x3fffffffffffffffL
      ) | Long.MinValue
    java.util.UUID(msb, lsb).toString

  /** The local path or object URI of a file URI the table's metadata records */
  private def resolvePath(layout: Layout, t: Table, uri: String): String =
    val location                = string(t.metadata, "location").getOrElse("").stripSuffix("/")
    def norm(p: String): String =
      if p.startsWith("s3a://") || p.startsWith("s3n://") then
        s"s3${p.substring(3)}"
      else if p.startsWith("file:") then
        ParquetDataset.localPathOfFileUri(p).getOrElse(p)
      else
        p
    val n = norm(uri)
    val l = norm(location)
    if n.startsWith(s"${l}/") then
      val rel = n.substring(l.length + 1)
      if layout.objects then
        s"${layout.root}${rel}"
      else
        ParquetDataset.localPath(s"${layout.root}${rel}")
    else if layout.objects then
      n
    else
      ParquetDataset.localPath(n)

  /**
    * The commit: writes the manifest, then for each attempt a manifest list on the current parent
    * and `v{N+1}.metadata.json` (only if absent). Losing the race reloads the table and retries, up
    * to [[MaxAttempts]] times. Files of a failed commit are deleted by `onFailure` of the caller,
    * which gets them from [[CommitState.written]].
    */
  final class CommitState(val plan: Plan, files: IndexedSeq[DataFile]):
    val snapshotId: Long = newId()
    private val commitId = newUuid()
    private var written  = List.empty[String]

    /** The manifests and lists this commit wrote, which a failed commit deletes */
    def writtenFiles: Seq[String] = written

    // Recorded before the put runs, so a put that fails partway is cleaned up too
    private def put[A](path: String, bytes: Array[Byte])(next: => Step[A]): Step[A] =
      written = path :: written
      Step.Put(path, bytes, () => next)

    private val layout = plan.layout
    private val spec   = plan.spec

    def run(): Step[Committed] =
      val manifestPath = layout.metadata(s"${commitId}-m0.avro")
      val manifest     = manifestBytes()
      put(manifestPath, manifest)(attempt(1, plan.base, manifestPath, manifest.length.toLong))

    private def manifestBytes(): Array[Byte] =
      val entries = files.map { f =>
        val m                                = metrics(plan.fileSchema, f)
        def mapOf[V](xs: Seq[(Int, V)]): Any =
          if xs.isEmpty then
            null
          else
            xs.map((k, v) => Map("key" -> k, "value" -> v))
        Map(
          "status"      -> 1,
          "snapshot_id" -> snapshotId,
          "data_file"   ->
            Map(
              "content"     -> 0,
              "file_path"   -> layout.uriOf(f.path),
              "file_format" -> "PARQUET",
              "partition"   ->
                spec
                  .map(p =>
                    p.name -> avroPartitionValue(f.partitions.getOrElse(p.source, null), p.tpe)
                  )
                  .toMap,
              "record_count"       -> f.numRows,
              "file_size_in_bytes" -> f.size,
              "column_sizes"       -> mapOf(m.columnSizes),
              "value_counts"       -> mapOf(m.valueCounts),
              "null_value_counts"  -> mapOf(m.nullCounts),
              "lower_bounds"       -> mapOf(m.lower),
              "upper_bounds"       -> mapOf(m.upper),
              "split_offsets" -> f.rowGroups.flatMap(_.columns.headOption.map(_.startOffset)).sorted
            )
        )
      }
      val specJson = JSONArray(spec.map(specFieldJson))
      Avro.write(
        manifestSchema(spec, plan.formatVersion),
        Seq(
          "schema"            -> plan.schema.toJSON,
          "schema-id"         -> plan.schemaId.toString,
          "partition-spec"    -> specJson.toJSON,
          "partition-spec-id" -> plan.specId.toString,
          "format-version"    -> plan.formatVersion.toString,
          "content"           -> "data"
        ),
        entries
      )

    end manifestBytes

    private def partitionSummaries: Seq[Map[String, Any]] = spec.map { p =>
      val values = files.map(f => avroPartitionValue(f.partitions.getOrElse(p.source, null), p.tpe))
      val set    = values.filter(_ != null)
      Map(
        "contains_null" -> values.exists(_ == null),
        "contains_nan"  -> false,
        "lower_bound"   ->
          set
            .reduceOption((a, b) =>
              if compareValues(a, b) <= 0 then
                a
              else
                b
            )
            .map(boundBytes)
            .orNull,
        "upper_bound" ->
          set
            .reduceOption((a, b) =>
              if compareValues(a, b) >= 0 then
                a
              else
                b
            )
            .map(boundBytes)
            .orNull
      )
    }

    /** What a commit removed from the base snapshot */
    private case class Removed(paths: Set[String], files: Long, rows: Long, bytes: Long):
      def +(e: Map[String, Any], path: String): Removed =
        val d = dataFileOf(e)
        Removed(
          paths + path,
          files + 1,
          rows + num(d, "record_count"),
          bytes + num(d, "file_size_in_bytes")
        )

    private val NoneRemoved = Removed(Set.empty, 0, 0, 0)

    private def dataFileOf(e: Map[String, Any]): Map[String, Any] =
      e.get("data_file") match
        case Some(d: Map[?, ?]) =>
          d.asInstanceOf[Map[String, Any]]
        case _ =>
          throw ParquetException(s"A manifest entry of ${layout.root} has no data_file")

    private def num(m: Map[String, Any], key: String): Long =
      m.get(key) match
        case Some(l: Long) =>
          l
        case Some(i: Int) =>
          i.toLong
        case _ =>
          0L

    /**
      * Live entries of a v3 manifest, with the first row IDs they inherited from the manifest
      * written out (as Iceberg's Java library assigns them, in entry order), so a rewritten
      * manifest keeps its rows' IDs
      */
    private def withRowIds(
        c: Avro.Container,
        listEntry: Map[String, Any],
        live: IndexedSeq[Map[String, Any]]
    ): IndexedSeq[Map[String, Any]] =
      // A v2-schema manifest gets the field when it is rewritten (rewrittenSchema)
      val hasField =
        plan.formatVersion >= 3 || c.meta("avro.schema").exists(_.contains("\"first_row_id\""))
      listEntry.get("first_row_id") match
        case Some(first: Long) if hasField =>
          var next = first
          live.map { e =>
            val d = dataFileOf(e)
            if d.get("first_row_id").forall(_ == null) then
              val id = next
              next += num(d, "record_count")
              e.updated("data_file", d.updated("first_row_id", id))
            else
              e
          }
        case _ =>
          live

    /**
      * The Avro schema of a rewritten manifest: its own, with the v3 `data_file` fields added in a
      * v3 commit when it lacks them (a manifest from before an upgrade), so the row IDs its files
      * inherited are written out
      */
    private def rewrittenSchema(schemaJson: String): String =
      if plan.formatVersion < 3 || schemaJson.contains("\"first_row_id\"") then
        schemaJson
      else
        val v3Fields = Seq(
          """{"name":"first_row_id","type":["null","long"],"default":null,"field-id":142}""",
          """{"name":"referenced_data_file","type":["null","string"],"default":null,"field-id":143}""",
          """{"name":"content_offset","type":["null","long"],"default":null,"field-id":144}""",
          """{"name":"content_size_in_bytes","type":["null","long"],"default":null,"field-id":145}"""
        ).map(f => JSON.parse(f))
        val root   = JSON.parse(schemaJson).asInstanceOf[JSONObject]
        val fields = array(root, "fields").map {
          case f: JSONObject if string(f, "name").contains("data_file") =>
            f.get("type") match
              case Some(r: JSONObject) =>
                val withV3 = JSONObject(
                  r.v
                    .map((k, v) =>
                      if k == "fields" then
                        k -> JSONArray(array(r, "fields") ++ v3Fields)
                      else
                        k -> v
                    )
                )
                JSONObject(
                  f.v
                    .map((k, v) =>
                      if k == "type" then
                        k -> withV3
                      else
                        k -> v
                    )
                )
              case _ =>
                f
          case other =>
            other
        }
        JSONObject(
          root
            .v
            .map((k, v) =>
              if k == "fields" then
                k -> JSONArray(fields)
              else
                k -> v
            )
        ).toJSON

    /** A path as [[ParquetDataset.files]] lists it, for matching the files to remove */
    private def pathKey(p: String): String =
      if layout.objects then
        if p.startsWith("s3a://") || p.startsWith("s3n://") then
          s"s3${p.substring(3)}"
        else
          p
      else
        ParquetDataset.localPath(p)

    private val removedKeys: Set[String] =
      plan.removal match
        case Removal.Files(ps) =>
          ps.map(pathKey)
        case _ =>
          Set.empty

    /**
      * The parent's manifest list entries for the new list. A manifest with files to remove is
      * rewritten: its other live files become EXISTING entries and the removed ones DELETED.
      */
    private def rewriteParents(
        n: Int,
        base: Table,
        parents: IndexedSeq[Map[String, Any]],
        seq: Long,
        rewritten: List[String]
    )(next: (Seq[Any], Removed, List[String]) => Step[Committed]): Step[Committed] =
      def loop(i: Int, acc: Vector[Any], removed: Removed, out: List[String]): Step[Committed] =
        if i >= parents.size then
          plan.removal match
            case Removal.Files(_) if removed.paths.size < removedKeys.size =>
              val missing =
                (
                  removedKeys -- removed.paths
                ).toSeq.sorted
              throw ParquetException(
                if n == 1 then
                  s"${missing.mkString(", ")} is not a live data file of ${layout.root}"
                else
                  s"Another writer removed ${missing.mkString(", ")} from ${layout
                      .root} during this write"
              )
            case _ =>
          next(acc, removed, out)
        else
          val r = parents(i)
          // A data manifest with no live files is left out, as iceberg-java does
          val dead =
            num(r, "content") == 0 &&
              Seq(
                "added_files_count",
                "existing_files_count",
                "added_data_files_count",
                "existing_data_files_count"
              ).exists(r.get(_).exists(_ != null)) &&
              num(r, "added_files_count") + num(r, "existing_files_count") +
              num(r, "added_data_files_count") + num(r, "existing_data_files_count") == 0
          if dead then
            loop(i + 1, acc, removed, out)
          else if plan.removal == Removal.Keep then
            loop(i + 1, acc :+ parentEntry(r), removed, out)
          else if num(r, "content") != 0 then
            // Delete files of removed rows don't matter once every row is gone
            if plan.removal == Removal.All then
              loop(i + 1, acc, removed, out)
            else
              loop(i + 1, acc :+ parentEntry(r), removed, out)
          else
            val listed = r
              .get("manifest_path")
              .collect { case s: String =>
                s
              }
              .getOrElse(
                throw ParquetException(
                  s"A manifest list entry of ${layout.root} has no manifest_path"
                )
              )
            Step.Fetch(
              resolve(base, listed),
              bytes =>
                val c           = Avro.read(bytes, listed)
                val manifestSeq = num(r, "sequence_number")
                val live        = withRowIds(
                  c,
                  r,
                  c.records
                    .map(_.asInstanceOf[Map[String, Any]])
                    .filter(e => num(e, "status") != Deleted)
                )
                def keyOf(e: Map[String, Any]): String = pathKey(
                  resolve(
                    base,
                    dataFileOf(e)
                      .get("file_path")
                      .collect { case s: String =>
                        s
                      }
                      .getOrElse("")
                  )
                )
                val (gone, kept) = live.partition(e =>
                  plan.removal match
                    case Removal.All =>
                      true
                    case _ =>
                      removedKeys.contains(keyOf(e))
                )
                if live.isEmpty then
                  loop(i + 1, acc, removed, out)
                else if gone.isEmpty then
                  loop(i + 1, acc :+ parentEntry(r), removed, out)
                else
                  // The snapshot ID and sequence numbers inherited from the old manifest are written
                  // out, so the files keep their provenance in the new manifest
                  val inherited = Seq(
                    "snapshot_id"          -> r.getOrElse("added_snapshot_id", null),
                    "sequence_number"      -> manifestSeq,
                    "file_sequence_number" -> manifestSeq
                  )
                  def withSeq(e: Map[String, Any]): Map[String, Any] =
                    inherited.foldLeft(e) { case (m, (k, v)) =>
                      if m.contains(k) && m(k) == null then
                        m.updated(k, v)
                      else
                        m
                    }
                  val entries =
                    kept.map(e => withSeq(e).updated("status", Existing)) ++
                      gone.map(e =>
                        withSeq(e).updated("status", Deleted).updated("snapshot_id", snapshotId)
                      )
                  val schemaJson = c
                    .meta("avro.schema")
                    .getOrElse(throw ParquetException(s"${listed} has no Avro schema"))
                  val meta = c
                    .metadata
                    .toSeq
                    .filterNot((k, _) => k.startsWith("avro."))
                    .map((k, v) =>
                      if k == "format-version" && plan.formatVersion >= 3 then
                        k -> plan.formatVersion.toString
                      else
                        k -> String(v, StandardCharsets.UTF_8)
                    )
                  val out2  = Avro.write(rewrittenSchema(schemaJson), meta, entries)
                  val path  = layout.metadata(s"${commitId}-r${n}-${i}.avro")
                  val seqs  = entries.map(e => num(e, "sequence_number"))
                  val entry = Map(
                    "manifest_path"        -> layout.uriOf(path),
                    "manifest_length"      -> out2.length.toLong,
                    "partition_spec_id"    -> r.getOrElse("partition_spec_id", 0),
                    "content"              -> 0,
                    "sequence_number"      -> seq,
                    "min_sequence_number"  -> seqs.minOption.getOrElse(seq),
                    "added_snapshot_id"    -> snapshotId,
                    "added_files_count"    -> 0,
                    "existing_files_count" -> kept.size,
                    "deleted_files_count"  -> gone.size,
                    "added_rows_count"     -> 0L,
                    "existing_rows_count"  -> kept.map(e => num(dataFileOf(e), "record_count")).sum,
                    "deleted_rows_count"   -> gone.map(e => num(dataFileOf(e), "record_count")).sum,
                    "partitions"           -> r.getOrElse("partitions", null),
                    "key_metadata"         -> null
                  )
                  put(path, out2) {
                    loop(
                      i + 1,
                      acc :+ entry,
                      gone.foldLeft(removed)((x, e) => x + (e, keyOf(e))),
                      path :: out
                    )
                  }
                end if
            )
          end if
      loop(0, Vector.empty, NoneRemoved, rewritten)

    end rewriteParents

    private def deleteAll(paths: List[String])(next: => Step[Committed]): Step[Committed] =
      paths match
        case Nil =>
          next
        case p :: rest =>
          Step.Delete(
            p,
            () =>
              written = written.filterNot(_ == p)
              deleteAll(rest)(next)
          )

    private def attempt(
        n: Int,
        base: Option[Table],
        manifestPath: String,
        manifestLength: Long
    ): Step[Committed] =
      val m              = base.map(_.metadata)
      val parent         = m.flatMap(long(_, "current-snapshot-id")).filter(_ >= 0)
      val lastSeq        = m.flatMap(long(_, "last-sequence-number")).getOrElse(0L)
      val seq            = lastSeq + 1
      val parentSnapshot = parent.flatMap(id =>
        m.toSeq.flatMap(objects(_, "snapshots")).find(s => long(s, "snapshot-id").contains(id))
      )
      def withParentManifests(
          next: IndexedSeq[Map[String, Any]] => Step[Committed]
      ): Step[Committed] =
        parentSnapshot match
          case None =>
            next(IndexedSeq.empty)
          case Some(s) =>
            string(s, "manifest-list") match
              case Some(list) =>
                Step.Fetch(
                  resolve(base.get, list),
                  bytes =>
                    next(Avro.read(bytes, list).records.map(_.asInstanceOf[Map[String, Any]]))
                )
              case None =>
                throw ParquetException(
                  s"The current snapshot of ${layout
                      .root} lists its manifests inline (format version 1); Uni can't commit to it"
                )
      withParentManifests { parents =>
        rewriteParents(n, base.orNull, parents, seq, Nil) { (parentEntries, removed, rewritten) =>
          val newEntry = Map(
            "manifest_path"        -> layout.uriOf(manifestPath),
            "manifest_length"      -> manifestLength,
            "partition_spec_id"    -> plan.specId,
            "content"              -> 0,
            "sequence_number"      -> seq,
            "min_sequence_number"  -> seq,
            "added_snapshot_id"    -> snapshotId,
            "added_files_count"    -> files.size,
            "existing_files_count" -> 0,
            "deleted_files_count"  -> 0,
            "added_rows_count"     -> files.map(_.numRows).sum,
            "existing_rows_count"  -> 0L,
            "deleted_rows_count"   -> 0L,
            "partitions"           -> partitionSummaries
          )
          // A commit that only removes files lists no manifest of its own
          val listed =
            if files.isEmpty && plan.removal != Removal.Keep then
              parentEntries
            else
              newEntry +: parentEntries
          // Row lineage (format version 3): a data manifest without a first row ID gets the next
          // IDs, one per added or existing row, as Iceberg's Java library assigns them
          val firstRowId        = m.flatMap(long(_, "next-row-id")).getOrElse(0L)
          var nextRowId         = firstRowId
          val entries: Seq[Any] =
            if plan.formatVersion < 3 then
              listed
            else
              listed.map { e =>
                val r = e.asInstanceOf[Map[String, Any]]
                if num(r, "content") == 0 && r.get("first_row_id").forall(_ == null) then
                  val id = nextRowId
                  nextRowId += num(r, "added_rows_count") + num(r, "existing_rows_count")
                  r.updated("first_row_id", id)
                else
                  r
              }
          val lineage = Option.when(plan.formatVersion >= 3)(firstRowId -> (nextRowId - firstRowId))
          val list    = Avro.write(
            manifestListSchema(plan.formatVersion),
            Seq(
              "snapshot-id"        -> snapshotId.toString,
              "parent-snapshot-id" -> parent.map(_.toString).getOrElse("null"),
              "sequence-number"    -> seq.toString,
              "format-version"     -> plan.formatVersion.toString
            ) ++ lineage.map((first, _) => "first-row-id" -> first.toString),
            entries
          )
          val listPath = layout.metadata(s"snap-${snapshotId}-${n}-${commitId}.avro")
          put(listPath, list) {
            val version = base.map(_.version).getOrElse(0) + 1
            val path    = layout.metadata(s"v${version}.metadata.json")
            val json    = metadataJson(
              base,
              parentSnapshot,
              seq,
              layout.uriOf(listPath),
              removed,
              lineage
            )
            createAttempted = true
            Step.Create(
              path,
              json.toJSON.getBytes(StandardCharsets.UTF_8),
              created =>
                if created then
                  Step.Done(Committed(snapshotId, version, path))
                else
                  // Another writer committed this version first: rebase on it, unless it is our
                  // own commit whose response was lost and retried
                  flatMap(load(layout)) {
                    case Some(t) if hasSnapshot(t, snapshotId) =>
                      Step.Done(Committed(snapshotId, t.version, t.path))
                    case Some(t) if n < MaxAttempts =>
                      checkRebase(t)
                      deleteAll(listPath :: rewritten)(
                        attempt(n + 1, Some(t), manifestPath, manifestLength)
                      )
                    case _ =>
                      throw ParquetException(
                        s"Another writer kept committing to ${layout
                            .root} first; gave up after ${n} attempts"
                      )
                  }
            )
          }
        }
      }

    end attempt

    private def checkRebase(t: Table): Unit =
      val m = t.metadata
      plan.base match
        case None =>
          throw ParquetException(
            s"Another writer created the Iceberg table ${layout.root} during this write"
          )
        case Some(b) =>
          def same(key: String): Boolean = long(m, key) == long(b.metadata, key)
          if !same("format-version") then
            throw ParquetException(
              s"Another writer changed the format version of ${layout.root} during this write"
            )
          if !same("current-schema-id") || !same("default-spec-id") then
            throw ParquetException(
              s"Another writer changed the schema or partition spec of ${layout
                  .root} during this write"
            )

    /**
      * Whether a metadata create ran, so a failure may still have committed: a request can land on
      * the server and then fail on the client
      */
    def mayHaveCommitted: Boolean = createAttempted

    private var createAttempted = false

    /** After an ambiguous failure: the commit, if the newest metadata holds this snapshot */
    def verify(): Step[Option[Committed]] =
      flatMap(load(layout)) {
        case Some(t) if hasSnapshot(t, snapshotId) =>
          Step.Done(Some(Committed(snapshotId, t.version, t.path)))
        case _ =>
          Step.Done(None)
      }

    private def hasSnapshot(t: Table, id: Long): Boolean = array(t.metadata, "snapshots").exists {
      case s: JSONObject =>
        long(s, "snapshot-id").contains(id)
      case _ =>
        false
    }

    private def resolve(t: Table, uri: String): String = resolvePath(layout, t, uri)

    private def parentEntry(r: Any): Any =
      val m                        = r.asInstanceOf[Map[String, Any]]
      def num(names: String*): Any = names.flatMap(m.get).find(_ != null).orNull
      Map(
        "manifest_path"        -> m.getOrElse("manifest_path", null),
        "manifest_length"      -> m.getOrElse("manifest_length", null),
        "partition_spec_id"    -> m.getOrElse("partition_spec_id", 0),
        "content"              -> m.getOrElse("content", 0),
        "sequence_number"      -> m.get("sequence_number").filter(_ != null).getOrElse(0L),
        "min_sequence_number"  -> m.get("min_sequence_number").filter(_ != null).getOrElse(0L),
        "added_snapshot_id"    -> m.getOrElse("added_snapshot_id", null),
        "added_files_count"    -> num("added_files_count", "added_data_files_count"),
        "existing_files_count" -> num("existing_files_count", "existing_data_files_count"),
        "deleted_files_count"  -> num("deleted_files_count", "deleted_data_files_count"),
        "added_rows_count"     -> num("added_rows_count"),
        "existing_rows_count"  -> num("existing_rows_count"),
        "deleted_rows_count"   -> num("deleted_rows_count"),
        "partitions"           -> m.getOrElse("partitions", null),
        "key_metadata"         -> m.getOrElse("key_metadata", null),
        "first_row_id"         -> m.getOrElse("first_row_id", null)
      )

    private def metadataJson(
        base: Option[Table],
        parentSnapshot: Option[JSONObject],
        seq: Long,
        manifestList: String,
        removed: Removed,
        /** The snapshot's first row ID and the number of row IDs it assigned (format version 3) */
        lineage: Option[(Long, Long)]
    ): JSONObject =
      val now           = System.currentTimeMillis()
      val added         = files.size.toLong
      val rows          = files.map(_.numRows).sum
      val bytes         = files.map(_.size).sum
      val parentSummary = parentSnapshot
        .flatMap(_.get("summary"))
        .collect { case o: JSONObject =>
          o
        }
      def total(key: String, add: Long, sub: Long = 0): Option[(String, JSONValue)] =
        parentSnapshot match
          case None =>
            Some(key -> JSONString(add.toString))
          case Some(_) if plan.removal == Removal.All =>
            // Every earlier file is gone, so the totals are this commit's
            Some(key -> JSONString(add.toString))
          case Some(_) =>
            parentSummary
              .flatMap(string(_, key))
              .flatMap(_.toLongOption)
              .map(v =>
                key -> JSONString(
                  (
                    v + add - sub
                  ).toString
                )
              )
      val partitions =
        files.map(f => plan.partitionBy.map(k => f.partitions.getOrElse(k, null))).distinct.size
      val operation =
        if plan.removal == Removal.Keep then
          "append"
        else if files.isEmpty then
          "delete"
        else
          "overwrite"
      val removedSummary =
        if removed.files == 0 then
          Seq.empty
        else
          Seq(
            "deleted-data-files" -> JSONString(removed.files.toString),
            "deleted-records"    -> JSONString(removed.rows.toString),
            "removed-files-size" -> JSONString(removed.bytes.toString)
          )
      val summary = JSONObject(
        Seq(
          "operation"               -> JSONString(operation),
          "added-data-files"        -> JSONString(added.toString),
          "added-records"           -> JSONString(rows.toString),
          "added-files-size"        -> JSONString(bytes.toString),
          "changed-partition-count" -> JSONString(partitions.toString)
        ) ++ removedSummary ++
          Seq(
            total("total-records", rows, removed.rows),
            total("total-files-size", bytes, removed.bytes),
            total("total-data-files", added, removed.files),
            total("total-delete-files", 0),
            total("total-position-deletes", 0),
            total("total-equality-deletes", 0)
          ).flatten
      )
      val snapshot = JSONObject(
        Seq("snapshot-id" -> JSONLong(snapshotId)) ++
          parentSnapshot
            .flatMap(long(_, "snapshot-id"))
            .map(p => "parent-snapshot-id" -> JSONLong(p)) ++
          Seq(
            "sequence-number" -> JSONLong(seq),
            "timestamp-ms"    -> JSONLong(now),
            "manifest-list"   -> JSONString(manifestList),
            "summary"         -> summary,
            "schema-id"       -> JSONLong(plan.schemaId.toLong)
          ) ++
          lineage
            .toSeq
            .flatMap((first, added) =>
              Seq("first-row-id" -> JSONLong(first), "added-rows" -> JSONLong(added))
            )
      )
      val lineageUpdate = lineage
        .toSeq
        .map((first, added) => "next-row-id" -> JSONLong(first + added))
      val ref = JSONObject(
        Seq(
          "main" ->
            JSONObject(Seq("snapshot-id" -> JSONLong(snapshotId), "type" -> JSONString("branch")))
        )
      )
      val logEntry = JSONObject(
        Seq("timestamp-ms" -> JSONLong(now), "snapshot-id" -> JSONLong(snapshotId))
      )
      base match
        case None =>
          JSONObject(
            Seq(
              "format-version"       -> JSONLong(plan.formatVersion.toLong),
              "table-uuid"           -> JSONString(newUuid()),
              "location"             -> JSONString(layout.location),
              "last-sequence-number" -> JSONLong(seq),
              "last-updated-ms"      -> JSONLong(now),
              "last-column-id"       -> JSONLong(plan.lastColumnId.toLong),
              "current-schema-id"    -> JSONLong(0),
              "schemas"              -> JSONArray(IndexedSeq(plan.schema)),
              "default-spec-id"      -> JSONLong(0),
              "partition-specs"      ->
                JSONArray(
                  IndexedSeq(
                    JSONObject(
                      Seq("spec-id" -> JSONLong(0), "fields" -> JSONArray(spec.map(specFieldJson)))
                    )
                  )
                ),
              "last-partition-id" -> JSONLong(spec.map(_.fieldId).maxOption.getOrElse(999).toLong),
              "default-sort-order-id" -> JSONLong(0),
              "sort-orders"           ->
                JSONArray(
                  IndexedSeq(
                    JSONObject(
                      Seq("order-id" -> JSONLong(0), "fields" -> JSONArray(IndexedSeq.empty))
                    )
                  )
                ),
              "properties" ->
                JSONObject(plan.properties.toSeq.sortBy(_._1).map((k, v) => k -> JSONString(v))),
              "current-snapshot-id" -> JSONLong(snapshotId),
              "refs"                -> ref,
              "snapshots"           -> JSONArray(IndexedSeq(snapshot)),
              "statistics"          -> JSONArray(IndexedSeq.empty),
              "snapshot-log"        -> JSONArray(IndexedSeq(logEntry)),
              "metadata-log"        -> JSONArray(IndexedSeq.empty)
            ) ++ lineageUpdate
          )
        case Some(t) =>
          val old         = t.metadata
          val metadataLog = JSONObject(
            Seq(
              "timestamp-ms"  -> JSONLong(long(old, "last-updated-ms").getOrElse(now)),
              "metadata-file" -> JSONString(layout.uriOf(t.path))
            )
          )
          val refs =
            old.get("refs") match
              case Some(JSONObject(rs)) =>
                JSONObject(rs.filterNot(_._1 == "main") ++ ref.v)
              case _ =>
                ref
          val schemaUpdate =
            if plan.addsSchema then
              Seq(
                "schemas"           -> JSONArray(array(old, "schemas") :+ plan.schema),
                "current-schema-id" -> JSONLong(plan.schemaId.toLong),
                "last-column-id"    -> JSONLong(plan.lastColumnId.toLong)
              )
            else
              Seq.empty
          val specUpdate =
            if plan.addsSpec then
              Seq(
                "partition-specs" ->
                  JSONArray(
                    array(old, "partition-specs") :+
                      JSONObject(
                        Seq(
                          "spec-id" -> JSONLong(plan.specId.toLong),
                          "fields"  -> JSONArray(spec.map(specFieldJson))
                        )
                      )
                  ),
                "last-partition-id" ->
                  JSONLong(
                    (
                      spec.map(_.fieldId) :+
                        long(old, "last-partition-id").map(_.toInt).getOrElse(999)
                    ).max.toLong
                  )
              )
            else
              Seq.empty
          val propertyUpdate =
            if plan.properties.isEmpty then
              Seq.empty
            else
              val oldProps =
                old.get("properties") match
                  case Some(JSONObject(ps)) =>
                    ps.filterNot((k, _) => plan.properties.contains(k))
                  case _ =>
                    Seq.empty
              Seq(
                "properties" ->
                  JSONObject(
                    oldProps ++ plan.properties.toSeq.sortBy(_._1).map((k, v) => k -> JSONString(v))
                  )
              )
          val replaced =
            Map[String, JSONValue](
              "default-spec-id"      -> JSONLong(plan.specId.toLong),
              "last-sequence-number" -> JSONLong(seq),
              "last-updated-ms"      -> JSONLong(now),
              "current-snapshot-id"  -> JSONLong(snapshotId),
              "refs"                 -> refs,
              "snapshots"            -> JSONArray(array(old, "snapshots") :+ snapshot),
              "snapshot-log"         -> JSONArray(array(old, "snapshot-log") :+ logEntry),
              "metadata-log"         -> JSONArray(array(old, "metadata-log") :+ metadataLog),
              "format-version"       -> JSONLong(plan.formatVersion.toLong)
            ) ++ schemaUpdate ++ specUpdate ++ propertyUpdate ++ lineageUpdate
          val kept = old.v.filterNot((k, _) => replaced.contains(k))
          JSONObject(kept ++ replaced.toSeq)
      end match

    end metadataJson

  end CommitState

  private def specFieldJson(p: PartitionField): JSONObject = JSONObject(
    Seq(
      "name"      -> JSONString(p.name),
      "transform" -> JSONString("identity"),
      "source-id" -> JSONLong(p.sourceId.toLong),
      "field-id"  -> JSONLong(p.fieldId.toLong)
    )
  )

  /** The hint readers such as DuckDB use to find the current version */
  def hint(layout: Layout, c: Committed): (String, Array[Byte]) = hint(layout, c.version)

  // ---- Steps ----

  def flatMap[A, B](s: Step[A])(f: A => Step[B]): Step[B] =
    s match
      case Step.Done(v) =>
        f(v)
      case Step.ListDir(d, next) =>
        Step.ListDir(d, names => flatMap(next(names))(f))
      case Step.Fetch(p, next) =>
        Step.Fetch(p, b => flatMap(next(b))(f))
      case Step.Put(p, b, next) =>
        Step.Put(p, b, () => flatMap(next())(f))
      case Step.Create(p, b, next) =>
        Step.Create(p, b, ok => flatMap(next(ok))(f))
      case Step.Delete(p, next) =>
        Step.Delete(p, () => flatMap(next())(f))

  // ---- Snapshot expiry ----

  /**
    * A committed expiry: the new metadata version, and the files no snapshot left in it references,
    * for the caller to delete after writing the hint
    */
  case class Expired(version: Int, metadataPath: String, snapshots: Int, unreferenced: Seq[String])

  /**
    * Expires every snapshot but the newest `retainLast` (by commit time), the current one, and
    * those a branch or tag references, in a new metadata version. The files only expired snapshots
    * reference (manifest lists, manifests, and data files), and the metadata files written before
    * the oldest kept snapshot, are returned for deletion after the commit. Files outside the table
    * folder are never returned, nor is a file a kept snapshot still lists. None when there is
    * nothing to expire.
    */
  def expire(layout: Layout, retainLast: Int): Step[Option[Expired]] =
    require(retainLast >= 1, s"retainLast must be at least 1: ${retainLast}")
    def attempt(n: Int): Step[Option[Expired]] =
      flatMap(load(layout)) {
        case None =>
          throw ParquetException(s"${layout.root} is not an Iceberg table")
        case Some(t) =>
          val m                         = t.metadata
          val snapshots                 = objects(m, "snapshots")
          def idOf(s: JSONObject): Long = long(s, "snapshot-id").getOrElse(-1L)
          val pinned                    =
            long(m, "current-snapshot-id").toSet ++ (
              m.get("refs") match
                case Some(JSONObject(rs)) =>
                  rs.collect { case (_, r: JSONObject) =>
                      long(r, "snapshot-id")
                    }
                    .flatten
                case _ =>
                  Nil
            )
          val newest =
            snapshots
              .sortBy(s =>
                (long(s, "timestamp-ms").getOrElse(0L), long(s, "sequence-number").getOrElse(0L))
              )
              .reverse
              .take(retainLast)
              .map(idOf)
              .toSet
          val (kept, expired) = snapshots.partition(s =>
            newest.contains(idOf(s)) || pinned.contains(idOf(s))
          )
          if expired.isEmpty then
            Step.Done(None)
          else
            flatMap(manifestsOf(layout, t, snapshots)) { manifests =>
              flatMap(dataFilesOf(layout, t, manifests.values.flatten.toSeq.distinct)) {
                dataFiles =>
                  commitExpiry(
                    n,
                    layout,
                    t,
                    kept,
                    expired,
                    manifests,
                    dataFiles,
                    () => attempt(n + 1)
                  )
              }
            }
      }
    attempt(1)

  end expire

  /** Each snapshot's manifest list (when it has one) and manifests, as paths */
  private def manifestsOf(
      layout: Layout,
      t: Table,
      snapshots: IndexedSeq[JSONObject]
  ): Step[Map[Long, Seq[String]]] =
    def loop(i: Int, acc: Map[Long, Seq[String]]): Step[Map[Long, Seq[String]]] =
      if i >= snapshots.size then
        Step.Done(acc)
      else
        val s  = snapshots(i)
        val id = long(s, "snapshot-id").getOrElse(-1L)
        string(s, "manifest-list") match
          case Some(list) =>
            Step.Fetch(
              resolvePath(layout, t, list),
              bytes =>
                val paths = Avro
                  .read(bytes, list)
                  .records
                  .flatMap(r =>
                    r.asInstanceOf[Map[String, Any]]
                      .get("manifest_path")
                      .collect { case p: String =>
                        resolvePath(layout, t, p)
                      }
                  )
                loop(i + 1, acc + (id -> paths))
            )
          case None =>
            // Format v1 may list the manifests in the snapshot
            val paths = array(s, "manifests").collect { case JSONString(p) =>
              resolvePath(layout, t, p)
            }
            loop(i + 1, acc + (id -> paths))
    loop(0, Map.empty)

  end manifestsOf

  /**
    * Whether a manifest entry is a live position-delete file (Parquet), not a deletion vector or an
    * equality delete, which hold no paths
    */
  private def isLivePositionDelete(e: Map[String, Any]): Boolean =
    e.get("status").forall(_ != Deleted) &&
      e.get("data_file")
        .exists {
          case d: Map[?, ?] =>
            val df = d.asInstanceOf[Map[String, Any]]
            df.get("content").contains(1) &&
            !df.get("file_format").exists(f => String.valueOf(f).equalsIgnoreCase("puffin"))
          case _ =>
            false
        }

  /**
    * Why the current snapshot's files can't be relocated, read from its manifests: position delete
    * files name data files by path inside their Parquet rows, which a rename can't rewrite
    */
  def relocationFileProblem(layout: Layout, t: Table): Step[Option[String]] =
    val m       = t.metadata
    val current =
      long(m, "current-snapshot-id")
        .filter(_ >= 0)
        .flatMap(id => objects(m, "snapshots").find(s => long(s, "snapshot-id").contains(id)))
        .toIndexedSeq
    flatMap(manifestsOf(layout, t, current)) { manifests =>
      val paths                              = manifests.values.flatten.toIndexedSeq
      def loop(i: Int): Step[Option[String]] =
        if i >= paths.size then
          Step.Done(None)
        else
          Step.Fetch(
            paths(i),
            bytes =>
              val positionDeletes = Avro
                .read(bytes, paths(i))
                .records
                .exists(r => isLivePositionDelete(r.asInstanceOf[Map[String, Any]]))
              if positionDeletes then
                Step.Done(
                  Some(
                    "it has position delete files, whose rows name data files by their old paths; compact the table first"
                  )
                )
              else
                loop(i + 1)
          )
      loop(0)
    }

  end relocationFileProblem

  /** The data files each manifest lists: (path, whether the entry is live) */
  private def dataFilesOf(
      layout: Layout,
      t: Table,
      manifests: Seq[String]
  ): Step[Map[String, Seq[(String, Boolean)]]] =
    def loop(
        i: Int,
        acc: Map[String, Seq[(String, Boolean)]]
    ): Step[Map[String, Seq[(String, Boolean)]]] =
      if i >= manifests.size then
        Step.Done(acc)
      else
        val path = manifests(i)
        Step.Fetch(
          path,
          bytes =>
            val files = Avro
              .read(bytes, path)
              .records
              .flatMap { r =>
                val e      = r.asInstanceOf[Map[String, Any]]
                val status =
                  e.get("status") match
                    case Some(i: Int) =>
                      i
                    case _ =>
                      Existing
                e.get("data_file")
                  .collect { case d: Map[?, ?] =>
                    d.asInstanceOf[Map[String, Any]]
                      .get("file_path")
                      .collect { case p: String =>
                        resolvePath(layout, t, p) -> (status != Deleted)
                      }
                  }
                  .flatten
              }
            loop(i + 1, acc + (path -> files))
        )
    loop(0, Map.empty)

  end dataFilesOf

  private def commitExpiry(
      n: Int,
      layout: Layout,
      t: Table,
      kept: IndexedSeq[JSONObject],
      expired: IndexedSeq[JSONObject],
      manifests: Map[Long, Seq[String]],
      dataFiles: Map[String, Seq[(String, Boolean)]],
      retry: () => Step[Option[Expired]]
  ): Step[Option[Expired]] =
    val m                                  = t.metadata
    def idOf(s: JSONObject): Long          = long(s, "snapshot-id").getOrElse(-1L)
    def listOf(s: JSONObject): Seq[String] =
      string(s, "manifest-list").map(resolvePath(layout, t, _)).toSeq
    val keptIds = kept.map(idOf).toSet
    // Everything a kept snapshot reads stays
    val keptManifests = kept.flatMap(s => manifests.getOrElse(idOf(s), Nil)).toSet
    val keep          =
      kept.flatMap(listOf).toSet ++ keptManifests ++
        keptManifests.flatMap(mf =>
          dataFiles
            .getOrElse(mf, Nil)
            .collect { case (p, true) =>
              p
            }
        )
    val expiredManifests = expired.flatMap(s => manifests.getOrElse(idOf(s), Nil)).distinct
    val candidates       =
      expired.flatMap(listOf) ++ expiredManifests ++
        expiredManifests.flatMap(mf => dataFiles.getOrElse(mf, Nil).map(_._1))
    val root              = layout.root
    val oldest            = kept.flatMap(long(_, "timestamp-ms")).minOption.getOrElse(Long.MaxValue)
    val (oldLog, keptLog) = array(m, "metadata-log")
      .collect { case o: JSONObject =>
        o
      }
      .partition(e => long(e, "timestamp-ms").exists(_ < oldest))
    val oldMetadata = oldLog.flatMap(string(_, "metadata-file")).map(resolvePath(layout, t, _))
    val deleted     =
      (
        candidates.filterNot(keep.contains) ++ oldMetadata
      ).distinct.filter(_.startsWith(root))
    val now      = System.currentTimeMillis()
    val replaced = Map[String, JSONValue](
      "last-updated-ms" -> JSONLong(now),
      "snapshots"       -> JSONArray(kept),
      "snapshot-log"    ->
        JSONArray(
          array(m, "snapshot-log").filter {
            case o: JSONObject =>
              long(o, "snapshot-id").exists(keptIds.contains)
            case _ =>
              false
          }
        ),
      "metadata-log" ->
        JSONArray(
          keptLog :+
            JSONObject(
              Seq(
                "timestamp-ms"  -> JSONLong(long(m, "last-updated-ms").getOrElse(now)),
                "metadata-file" -> JSONString(tableLayout(layout, t).uriOf(t.path))
              )
            )
        )
    )
    val json   = JSONObject(m.v.filterNot((k, _) => replaced.contains(k)) ++ replaced.toSeq)
    val path   = layout.metadata(s"v${t.version + 1}.metadata.json")
    val bytes  = json.toJSON.getBytes(StandardCharsets.UTF_8)
    val result = Some(Expired(t.version + 1, path, expired.size, deleted))
    // Another writer committed first: reload and choose the snapshots again
    createMetadata(n, layout, path, bytes, result)(retry, () => Step.Done(()))

  end commitExpiry

  // ---- Relocation ----

  /**
    * Why the table with metadata `m` can't be relocated, checked before its folder moves: snapshots
    * that refs other than `main` keep would be lost, write paths would still name the old folder,
    * and a format v1 snapshot listing its manifests inline can't be rewritten
    */
  def relocationProblem(m: JSONObject): Option[String] =
    val refs =
      m.get("refs") match
        case Some(JSONObject(rs)) =>
          rs.map(_._1).filterNot(_ == "main")
        case _ =>
          Nil
    val writePaths =
      m.get("properties") match
        case Some(JSONObject(ps)) =>
          ps.map(_._1)
            .filter(k =>
              k == "write.data.path" || k == "write.metadata.path" ||
                k == "write.object-storage.path"
            )
        case _ =>
          Nil
    val current = long(m, "current-snapshot-id")
      .filter(_ >= 0)
      .flatMap(id => objects(m, "snapshots").find(s => long(s, "snapshot-id").contains(id)))
    if refs.nonEmpty then
      Some(s"it has branches or tags (${refs.mkString(", ")}), whose snapshots a rename would drop")
    else if writePaths.nonEmpty then
      Some(s"it sets ${writePaths.mkString(", ")}, which would still name the old folder")
    else if current.exists(string(_, "manifest-list").isEmpty) then
      Some("its current snapshot lists its manifests inline (format version 1)")
    else
      None

  /**
    * Points the table at `layout`'s location after its folder moved there: rewrites the current
    * snapshot's manifests and manifest list with paths under the new location, and commits a new
    * metadata version with that location and only the current snapshot. Older snapshots can't be
    * read after the move, so their files, the replaced manifests, and the older metadata files are
    * returned as [[Expired.unreferenced]].
    */
  def relocate(layout: Layout): Step[Expired] =
    def attempt(n: Int): Step[Expired] =
      flatMap(load(layout)) {
        case None =>
          throw ParquetException(s"${layout.root} is not an Iceberg table")
        case Some(t) =>
          val m         = t.metadata
          val snapshots = objects(m, "snapshots")
          val current   = long(m, "current-snapshot-id")
            .filter(_ >= 0)
            .flatMap(id => snapshots.find(s => long(s, "snapshot-id").contains(id)))
          flatMap(manifestsOf(layout, t, snapshots)) { manifests =>
            flatMap(dataFilesOf(layout, t, manifests.values.flatten.toSeq.distinct)) { dataFiles =>
              val commitId = newUuid()
              def committed(snapshot: Option[JSONObject], written: Seq[String]): Step[Expired] =
                commitRelocation(
                  n,
                  layout,
                  t,
                  snapshot,
                  snapshots,
                  manifests,
                  dataFiles,
                  written,
                  () => attempt(n + 1)
                )
              current match
                case None =>
                  committed(None, Nil)
                case Some(s) =>
                  rewriteSnapshot(layout, t, s, commitId)((snapshot, written) =>
                    committed(Some(snapshot), written)
                  )
            }
          }
      }
    attempt(1)

  end relocate

  /**
    * Writes the snapshot's manifests and manifest list again with file paths under `layout`'s
    * location, and continues with the snapshot pointing at the new list and the files written
    */
  private def rewriteSnapshot(layout: Layout, t: Table, s: JSONObject, commitId: String)(
      next: (JSONObject, Seq[String]) => Step[Expired]
  ): Step[Expired] =
    def avroMeta(c: Avro.Container): (String, Seq[(String, String)]) =
      val schema = c
        .meta("avro.schema")
        .getOrElse(throw ParquetException("A manifest has no Avro schema"))
      schema ->
        c.metadata
          .toSeq
          .filterNot((k, _) => k.startsWith("avro."))
          .map((k, v) => k -> String(v, StandardCharsets.UTF_8))
    // A file outside the table folder didn't move, so its path stays as recorded
    def moved(uri: String): String =
      val p = resolvePath(layout, t, uri)
      if p.startsWith(layout.root) then
        layout.uriOf(p)
      else
        uri
    val list = string(s, "manifest-list").getOrElse(
      throw ParquetException(
        s"The current snapshot of ${layout
            .root} lists its manifests inline (format version 1); Uni can't relocate it"
      )
    )
    Step.Fetch(
      resolvePath(layout, t, list),
      listBytes =>
        val listed  = Avro.read(listBytes, list)
        val entries = listed.records.map(_.asInstanceOf[Map[String, Any]])
        def loop(i: Int, out: Vector[Map[String, Any]], written: Vector[String]): Step[Expired] =
          if i >= entries.size then
            val (schema, meta) = avroMeta(listed)
            val id             = long(s, "snapshot-id").getOrElse(0L)
            val listPath       = layout.metadata(s"snap-${id}-relocated-${commitId}.avro")
            Step.Put(
              listPath,
              Avro.write(schema, meta, out),
              () =>
                val snapshot = JSONObject(
                  s.v
                    .map((k, v) =>
                      if k == "manifest-list" then
                        k -> JSONString(layout.uriOf(listPath))
                      else
                        k -> v
                    )
                )
                next(snapshot, written :+ listPath)
            )
          else
            val e        = entries(i)
            val manifest = e
              .get("manifest_path")
              .collect { case p: String =>
                p
              }
              .getOrElse(
                throw ParquetException(
                  s"A manifest list entry of ${layout.root} has no manifest_path"
                )
              )
            Step.Fetch(
              resolvePath(layout, t, manifest),
              bytes =>
                val c              = Avro.read(bytes, manifest)
                val (schema, meta) = avroMeta(c)
                val records        = c
                  .records
                  .map { r =>
                    val entry = r.asInstanceOf[Map[String, Any]]
                    entry.get("data_file") match
                      case Some(d: Map[?, ?]) =>
                        val df = d.asInstanceOf[Map[String, Any]]
                        // A backstop: renameIcebergTable refuses these before moving the folder
                        if isLivePositionDelete(entry) then
                          throw ParquetException(
                            s"${layout
                                .root} has position delete files, whose rows name data files by their old paths; compact the table before renaming it"
                          )
                        val withPath =
                          df.get("file_path") match
                            case Some(p: String) =>
                              df.updated("file_path", moved(p))
                            case _ =>
                              df
                        val withTarget =
                          withPath.get("referenced_data_file") match
                            case Some(p: String) =>
                              // A deletion vector's data file; the path bounds name it too
                              withPath
                                .updated("referenced_data_file", moved(p))
                                .updated("lower_bounds", null)
                                .updated("upper_bounds", null)
                            case _ =>
                              withPath
                        entry.updated("data_file", withTarget)
                      case _ =>
                        entry
                    end match
                  }
                val out2 = Avro.write(schema, meta, records)
                val path = layout.metadata(s"${commitId}-m${i}.avro")
                Step.Put(
                  path,
                  out2,
                  () =>
                    val updated = e
                      .updated("manifest_path", layout.uriOf(path))
                      .updated("manifest_length", out2.length.toLong)
                    loop(i + 1, out :+ updated, written :+ path)
                )
            )
        loop(0, Vector.empty, Vector.empty)
    )

  end rewriteSnapshot

  private def commitRelocation(
      n: Int,
      layout: Layout,
      t: Table,
      current: Option[JSONObject],
      snapshots: IndexedSeq[JSONObject],
      manifests: Map[Long, Seq[String]],
      dataFiles: Map[String, Seq[(String, Boolean)]],
      written: Seq[String],
      retry: () => Step[Expired]
  ): Step[Expired] =
    val m      = t.metadata
    val liveId = current.flatMap(long(_, "snapshot-id"))
    // The current snapshot's live data files are the only old files the new version reads
    val live =
      liveId
        .toSeq
        .flatMap(manifests.getOrElse(_, Nil))
        .flatMap(mf =>
          dataFiles
            .getOrElse(mf, Nil)
            .collect { case (p, true) =>
              p
            }
        )
        .toSet
    val oldLists = snapshots.flatMap(s => string(s, "manifest-list").map(resolvePath(layout, t, _)))
    val oldMetadata =
      (
        1 to t.version
      ).flatMap(v => IcebergTable.hadoopMetadataNames(v.toString).map(layout.metadata))
    val unreferenced =
      (
        oldLists ++ manifests.values.flatten ++ dataFiles.values.flatten.map(_._1) ++ oldMetadata
      ).distinct.filterNot(live.contains).filter(_.startsWith(layout.root))
    val now      = System.currentTimeMillis()
    val replaced = Map[String, JSONValue](
      "location"        -> JSONString(layout.location),
      "last-updated-ms" -> JSONLong(now),
      "snapshots"       -> JSONArray(current.toIndexedSeq),
      "snapshot-log"    ->
        JSONArray(
          array(m, "snapshot-log").filter {
            case o: JSONObject =>
              liveId.exists(id => long(o, "snapshot-id").contains(id))
            case _ =>
              false
          }
        ),
      "metadata-log" -> JSONArray(IndexedSeq.empty),
      // Statistics files name the old folder; engines compute them again
      "statistics"           -> JSONArray(IndexedSeq.empty),
      "partition-statistics" -> JSONArray(IndexedSeq.empty),
      "refs"                 ->
        JSONObject(
          liveId
            .toSeq
            .map(id =>
              "main" ->
                JSONObject(Seq("snapshot-id" -> JSONLong(id), "type" -> JSONString("branch")))
            )
        )
    )
    val json   = JSONObject(m.v.filterNot((k, _) => replaced.contains(k)) ++ replaced.toSeq)
    val path   = layout.metadata(s"v${t.version + 1}.metadata.json")
    val bytes  = json.toJSON.getBytes(StandardCharsets.UTF_8)
    val result = Expired(t.version + 1, path, snapshots.size - current.size, unreferenced)
    // The files this attempt wrote are replaced by the next attempt's, or unused on giving up
    createMetadata(n, layout, path, bytes, result)(
      () => deleteFiles(written)(retry),
      () => deleteFiles(written)(() => Step.Done(()))
    )

  end commitRelocation

  /**
    * Creates metadata version `path` and continues with `result`. Another writer's version at that
    * path continues with `retry`, up to [[MaxAttempts]], or fails after `cleanup`; a create whose
    * response was lost but landed (the same bytes) counts as created
    */
  private def createMetadata[A](
      n: Int,
      layout: Layout,
      path: String,
      bytes: Array[Byte],
      result: A
  )(retry: () => Step[A], cleanup: () => Step[Unit]): Step[A] = Step.Create(
    path,
    bytes,
    created =>
      if created then
        Step.Done(result)
      else
        Step.Fetch(
          path,
          existing =>
            if java.util.Arrays.equals(existing, bytes) then
              Step.Done(result)
            else if n < MaxAttempts then
              retry()
            else
              flatMap(cleanup()) { _ =>
                throw ParquetException(
                  s"Another writer kept committing to ${layout
                      .root} first; gave up after ${n} attempts"
                )
              }
        )
  )

  private def deleteFiles[A](paths: Seq[String])(next: () => Step[A]): Step[A] =
    paths match
      case Seq() =>
        next()
      case p +: rest =>
        Step.Delete(p, () => deleteFiles(rest)(next))

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

  private def array(o: JSONObject, key: String): IndexedSeq[JSONValue] =
    o.get(key) match
      case Some(JSONArray(vs)) =>
        vs
      case _ =>
        IndexedSeq.empty

  private def objects(o: JSONObject, key: String): IndexedSeq[JSONObject] = array(o, key).collect {
    case x: JSONObject =>
      x
  }

end IcebergWrite
