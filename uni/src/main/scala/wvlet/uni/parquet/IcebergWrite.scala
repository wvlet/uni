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
      if objects then
        path
      else
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

  private val HadoopMetadata  = """v(\d+)\.metadata\.json""".r
  private val CatalogMetadata = """(\d+)-.+\.metadata\.json""".r
  private val MaxAttempts     = 4

  // ---- Loading ----

  /** Reads the newest `vN.metadata.json`, or none when the table doesn't exist yet */
  def load(layout: Layout): Step[Option[Table]] = Step.ListDir(
    layout.metadataDir,
    names =>
      val metadataFiles = names.filter(IcebergTable.isMetadataFile)
      val versions      = metadataFiles.collect { case HadoopMetadata(v) =>
        v.toInt
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
        val v    = versions.max
        val path = layout.metadata(s"v${v}.metadata.json")
        Step.Fetch(
          path,
          bytes =>
            JSON.parse(String(bytes, StandardCharsets.UTF_8)) match
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

  /** What the data files need before any is written, and what the commit builds on */
  final class Plan(
      val layout: Layout,
      val base: Option[Table],
      /** The file schema with the table's field IDs; it keeps the partition columns */
      val fileSchema: ParquetSchema,
      /** The identity partition columns, in spec order */
      val partitionBy: IndexedSeq[String],
      val schemaId: Int,
      /** The table schema, as Iceberg JSON with `schema-id` */
      val schema: JSONObject,
      val specId: Int,
      val spec: IndexedSeq[PartitionField],
      val lastColumnId: Int
  )

  /**
    * Binds the write to the table: field IDs from the table schema (or new ones for a new table),
    * and the partition spec from `withPartitionBy` (or the table's spec when none is given)
    */
  def plan(
      layout: Layout,
      base: Option[Table],
      schema: ParquetSchema,
      partitionBy: Seq[String],
      mode: ParquetWriteMode
  ): Plan =
    mode match
      case ParquetWriteMode.Overwrite | ParquetWriteMode.OverwritePartitions =>
        throw ParquetException(
          s"Uni doesn't overwrite Iceberg tables yet (${mode}); write with ErrorIfExists to create ${layout
              .root} or Append to add a snapshot"
        )
      case _ =>
    base match
      case Some(t) =>
        if mode == ParquetWriteMode.ErrorIfExists then
          throw ParquetException(
            s"${layout.root} is already an Iceberg table (${t
                .path}). Write withMode(ParquetWriteMode.Append) to add a snapshot"
          )
        planAppend(layout, t, schema, partitionBy)
      case None =>
        planCreate(layout, schema, partitionBy)

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

  private def planAppend(
      layout: Layout,
      t: Table,
      schema: ParquetSchema,
      partitionBy: Seq[String]
  ): Plan =
    val m       = t.metadata
    val version = long(m, "format-version").getOrElse(1L)
    if version != 2 then
      throw ParquetException(
        s"${t.path} is an Iceberg format version ${version} table; Uni appends only to version 2 tables"
      )
    val schemaId = long(m, "current-schema-id").map(_.toInt).getOrElse(0)
    val current  = array(m, "schemas")
      .collect { case s: JSONObject =>
        s
      }
      .find(s => long(s, "schema-id").contains(schemaId.toLong))
      .getOrElse(throw ParquetException(s"${t.path} has no schema with id ${schemaId}"))
    val tableFields = structFields(current)
    val bound       = bindStruct(schema.fields, tableFields, "", t.path)
    val fileSchema  = ParquetSchema(schema.root.copy(children = bound))
    val specId      = long(m, "default-spec-id").map(_.toInt).getOrElse(0)
    val specJson    = array(m, "partition-specs")
      .collect { case s: JSONObject =>
        s
      }
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
    val keys = spec.map(_.source)
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
      layout,
      Some(t),
      fileSchema,
      keys,
      schemaId,
      current,
      specId,
      spec,
      long(m, "last-column-id").map(_.toInt).getOrElse(0)
    )

  end planAppend

  private def checkPartitionType(p: PartitionField): Unit =
    p.tpe match
      case "string" | "int" | "long" | "date" =>
      case other                              =>
        throw ParquetException(
          s"Partition column ${p
              .source} has the Iceberg type ${other}; Uni writes identity partitions on string, int, long, and date columns"
        )

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
                .mkString(", ")}). Uni doesn't add columns to Iceberg tables yet"
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
          case Some(other) =>
            throw ParquetException(
              s"Column ${g.pathString} is a ${other} group, which Iceberg tables can't hold"
            )

  /** The Iceberg type of a leaf column */
  def primitiveType(p: ParquetField.Primitive, name: String): String =
    def unsupported(what: String): Nothing =
      throw ParquetException(
        s"Column ${name} is ${what}, which Iceberg format version 2 can't hold"
      )
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
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, unit))) =>
        unsupported(s"a ${unit} timestamp (Iceberg stores microseconds)")
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
        v = (v << 8) | (b(i) & 0xffL)
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
      val c = (a(i) & 0xff) - (b(i) & 0xff)
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

  private def mapType(name: String, keyId: Int, valueId: Int, value: String): String =
    s"""["null",{"type":"array","items":{"type":"record","name":"k${keyId}_v${valueId}","fields":[{"name":"key","type":"int","field-id":${keyId}},{"name":"value","type":"${value}","field-id":${valueId}}]},"logicalType":"map"}]"""

  private def manifestSchema(spec: IndexedSeq[PartitionField]): String =
    val partition                                     = spec.map(partitionAvroType).mkString(",")
    def opt(name: String, id: Int, t: String): String =
      s"""{"name":"${name}","type":${t},"default":null,"field-id":${id}}"""
    val dataFile = Seq(
      """{"name":"content","type":"int","field-id":134}""",
      """{"name":"file_path","type":"string","field-id":100}""",
      """{"name":"file_format","type":"string","field-id":101}""",
      s"""{"name":"partition","type":{"type":"record","name":"r102","fields":[${partition}]},"field-id":102}""",
      """{"name":"record_count","type":"long","field-id":103}""",
      """{"name":"file_size_in_bytes","type":"long","field-id":104}""",
      opt("column_sizes", 108, mapType("column_sizes", 117, 118, "long")),
      opt("value_counts", 109, mapType("value_counts", 119, 120, "long")),
      opt("null_value_counts", 110, mapType("null_value_counts", 121, 122, "long")),
      opt("nan_value_counts", 137, mapType("nan_value_counts", 138, 139, "long")),
      opt("lower_bounds", 125, mapType("lower_bounds", 126, 127, "bytes")),
      opt("upper_bounds", 128, mapType("upper_bounds", 129, 130, "bytes")),
      opt("key_metadata", 131, """["null","bytes"]"""),
      opt("split_offsets", 132, """["null",{"type":"array","items":"long","element-id":133}]"""),
      opt("equality_ids", 135, """["null",{"type":"array","items":"int","element-id":136}]"""),
      opt("sort_order_id", 140, """["null","int"]""")
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

  private val ManifestListSchema: String =
    """{"type":"record","name":"manifest_file","fields":[""" +
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
    def le(x: Long, n: Int): Array[Byte] = Array.tabulate(n)(i => ((x >>> (8 * i)) & 0xff).toByte)
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
    val msb = (r.nextLong() & ~0xf000L) | 0x4000L
    val lsb = (r.nextLong() & 0x3fffffffffffffffL) | Long.MinValue
    java.util.UUID(msb, lsb).toString

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
        manifestSchema(spec),
        Seq(
          "schema"            -> plan.schema.toJSON,
          "schema-id"         -> plan.schemaId.toString,
          "partition-spec"    -> specJson.toJSON,
          "partition-spec-id" -> plan.specId.toString,
          "format-version"    -> "2",
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
        m.toSeq
          .flatMap(array(_, "snapshots"))
          .collect { case s: JSONObject =>
            s
          }
          .find(s => long(s, "snapshot-id").contains(id))
      )
      def withParentManifests(next: Seq[Any] => Step[Committed]): Step[Committed] =
        parentSnapshot match
          case None =>
            next(Seq.empty)
          case Some(s) =>
            string(s, "manifest-list") match
              case Some(list) =>
                Step.Fetch(
                  resolve(base.get, list),
                  bytes => next(Avro.read(bytes, list).records.map(parentEntry))
                )
              case None =>
                throw ParquetException(
                  s"The current snapshot of ${layout
                      .root} lists its manifests inline (format version 1); Uni can't append to it"
                )
      withParentManifests { parentEntries =>
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
        val list = Avro.write(
          ManifestListSchema,
          Seq(
            "snapshot-id"        -> snapshotId.toString,
            "parent-snapshot-id" -> parent.map(_.toString).getOrElse("null"),
            "sequence-number"    -> seq.toString,
            "format-version"     -> "2"
          ),
          newEntry +: parentEntries
        )
        val listPath = layout.metadata(s"snap-${snapshotId}-${n}-${commitId}.avro")
        put(listPath, list) {
          val version = base.map(_.version).getOrElse(0) + 1
          val path    = layout.metadata(s"v${version}.metadata.json")
          val json    = metadataJson(base, parentSnapshot, seq, layout.uriOf(listPath))
          Step.Create(
            path,
            json.toJSON.getBytes(StandardCharsets.UTF_8),
            created =>
              if created then
                Step.Done(Committed(snapshotId, version, path))
              else
                // Another writer committed this version first: rebase on it, unless it is our own
                // commit whose response was lost and retried
                flatMap(load(layout)) {
                  case Some(t) if hasSnapshot(t, snapshotId) =>
                    Step.Done(Committed(snapshotId, t.version, t.path))
                  case Some(t) if n < MaxAttempts =>
                    checkRebase(t)
                    Step.Delete(
                      listPath,
                      () =>
                        written = written.filterNot(_ == listPath)
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
          if !same("current-schema-id") || !same("default-spec-id") || !same("format-version") then
            throw ParquetException(
              s"Another writer changed the schema or partition spec of ${layout
                  .root} during this write"
            )

    private def hasSnapshot(t: Table, id: Long): Boolean = array(t.metadata, "snapshots").exists {
      case s: JSONObject =>
        long(s, "snapshot-id").contains(id)
      case _ =>
        false
    }

    private def resolve(t: Table, uri: String): String =
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
        "key_metadata"         -> m.getOrElse("key_metadata", null)
      )

    private def metadataJson(
        base: Option[Table],
        parentSnapshot: Option[JSONObject],
        seq: Long,
        manifestList: String
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
      def total(key: String, add: Long): Option[(String, JSONValue)] =
        parentSnapshot match
          case None =>
            Some(key -> JSONString(add.toString))
          case Some(_) =>
            parentSummary
              .flatMap(string(_, key))
              .flatMap(_.toLongOption)
              .map(v => key -> JSONString((v + add).toString))
      val partitions =
        files.map(f => plan.partitionBy.map(k => f.partitions.getOrElse(k, null))).distinct.size
      val summary = JSONObject(
        Seq(
          "operation"               -> JSONString("append"),
          "added-data-files"        -> JSONString(added.toString),
          "added-records"           -> JSONString(rows.toString),
          "added-files-size"        -> JSONString(bytes.toString),
          "changed-partition-count" -> JSONString(partitions.toString)
        ) ++
          Seq(
            total("total-records", rows),
            total("total-files-size", bytes),
            total("total-data-files", added),
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
          )
      )
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
              "format-version"       -> JSONLong(2),
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
              "properties"          -> JSONObject(Seq.empty),
              "current-snapshot-id" -> JSONLong(snapshotId),
              "refs"                -> ref,
              "snapshots"           -> JSONArray(IndexedSeq(snapshot)),
              "statistics"          -> JSONArray(IndexedSeq.empty),
              "snapshot-log"        -> JSONArray(IndexedSeq(logEntry)),
              "metadata-log"        -> JSONArray(IndexedSeq.empty)
            )
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
          val replaced = Map[String, JSONValue](
            "last-sequence-number" -> JSONLong(seq),
            "last-updated-ms"      -> JSONLong(now),
            "current-snapshot-id"  -> JSONLong(snapshotId),
            "refs"                 -> refs,
            "snapshots"            -> JSONArray(array(old, "snapshots") :+ snapshot),
            "snapshot-log"         -> JSONArray(array(old, "snapshot-log") :+ logEntry),
            "metadata-log"         -> JSONArray(array(old, "metadata-log") :+ metadataLog)
          )
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
  def hint(layout: Layout, c: Committed): (String, Array[Byte]) =
    layout.metadata(VersionHint) -> c.version.toString.getBytes(StandardCharsets.UTF_8)

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

end IcebergWrite
