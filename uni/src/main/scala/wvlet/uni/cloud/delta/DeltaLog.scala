package wvlet.uni.cloud.delta

import wvlet.uni.cloud.parquet.*

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.cloud.parquet.ParquetDataset.Listed
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.cloud.parquet.format.TimeUnit

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Reads the transaction log of a Delta Lake table: `_delta_log/` under the table root holds one
  * JSON file of actions per commit, and Parquet checkpoints that fold the log up to a version.
  *
  * Reading is split in two, so the sync and async readers share it: [[plan]] picks the log files a
  * version needs from a listing, and [[replay]] turns their bytes into the table's live files.
  */
private[cloud] object DeltaLog:
  val LogDir = "_delta_log"

  /** A live data file: its path as the log records it, partition values (null for null), size */
  case class AddFile(path: String, partitionValues: Map[String, String], size: Long)

  /** The table at one version */
  case class Snapshot(
      version: Long,
      files: IndexedSeq[AddFile],
      schema: JSONObject,
      partitionColumns: IndexedSeq[String]
  )

  /** The log files a read of `version` needs: a checkpoint (maybe in parts), then later commits */
  case class LogPlan(version: Long, checkpoint: Seq[Listed], commits: Seq[Listed]):
    def files: Seq[Listed] = checkpoint ++ commits

  /** Reader features that don't change which rows or columns a read returns */
  private val SupportedReaderFeatures = Set(
    "timestampNtz",
    "vacuumProtocolCheck",
    // Changed column types show in the file footers: numeric widening applies, anything else fails
    "typeWidening",
    "typeWidening-preview"
  )

  private val CommitFile     = """(\d{20})\.json""".r
  private val CheckpointFile = """(\d{20})\.checkpoint\.parquet""".r
  private val CheckpointPart = """(\d{20})\.checkpoint\.(\d{10})\.(\d{10})\.parquet""".r

  /** Whether a listing below a root has a `_delta_log/` folder directly under it */
  def isTable(listed: Seq[Listed]): Boolean = listed.exists(l =>
    l.segments.size == 2 && l.segments.head == LogDir
  )

  def plan(tableRoot: String, listed: Seq[Listed], version: Option[Long]): LogPlan =
    val log     = listed.filter(l => l.segments.size == 2 && l.segments.head == LogDir)
    val commits = mutable.TreeMap.empty[Long, Listed]
    val single  = mutable.TreeMap.empty[Long, Listed]
    val parts   = mutable.TreeMap.empty[(Long, Int), mutable.TreeMap[Int, Listed]]
    log.foreach { l =>
      l.segments.last match
        case CommitFile(v) =>
          commits(v.toLong) = l
        case CheckpointFile(v) =>
          single(v.toLong) = l
        case CheckpointPart(v, part, n) =>
          parts.getOrElseUpdate((v.toLong, n.toInt), mutable.TreeMap.empty)(part.toInt) = l
        case _ =>
        // _last_checkpoint, compacted logs, CRC files, and v2 checkpoints (refused by protocol)
    }
    // A multi-part checkpoint counts only when every part is listed
    val checkpoints = mutable.TreeMap.empty[Long, Seq[Listed]]
    parts.foreach { case ((v, n), ps) =>
      if ps.size == n && ps.keySet == (1 to n).toSet && !checkpoints.contains(v) then
        checkpoints(v) = ps.values.toSeq
    }
    single.foreach((v, l) => checkpoints(v) = Seq(l))
    if commits.isEmpty && checkpoints.isEmpty then
      throw ParquetException(s"No commits found in ${tableRoot}${LogDir}/")

    val latest = (commits.keys ++ checkpoints.keys).max
    val target = version.getOrElse(latest)

    // Commits after `from` up to `to` are all listed
    def chained(from: Long, to: Long): Boolean = (from + 1 to to).forall(commits.contains)
    // Ways to start: a checkpoint, or commit 0 (an empty table before it)
    val starts: Seq[(Long, Seq[Listed])] =
      checkpoints.toSeq ++ (
        if commits.contains(0) then
          Seq((-1L, Seq.empty))
        else
          Seq.empty
      )
    val readable      = starts.filter((s, _) => chained(s, latest))
    def range: String =
      if readable.isEmpty then
        "none"
      else
        s"${math.max(readable.map(_._1).min, 0)} to ${latest}"
    if target < 0 || target > latest then
      throw ParquetException(
        s"Version ${target} of ${tableRoot} doesn't exist: the readable versions are ${range}"
      )
    starts.filter((s, _) => s <= target && chained(s, target)).maxByOption(_._1) match
      case Some((start, cp)) =>
        LogPlan(target, cp, (start + 1 to target).map(commits))
      case None =>
        throw ParquetException(
          s"Version ${target} of ${tableRoot} can't be read: the log no longer has its commits. The readable versions are ${range}"
        )

  end plan

  /**
    * Replays the log: the checkpoint's actions, then each commit's in order. `fetched` holds the
    * bytes of `plan.files`, in the same order.
    */
  def replay(tableRoot: String, plan: LogPlan, fetched: Seq[Array[Byte]]): Snapshot =
    val live                         = mutable.LinkedHashMap.empty[String, AddFile]
    var metaData: Option[JSONObject] = None
    var protocol: Option[JSONObject] = None

    def apply(action: JSONObject, source: String): Unit = action
      .v
      .foreach {
        case ("add", a: JSONObject) =>
          a.get("deletionVector") match
            case Some(_: JSONObject) =>
              unsupported(tableRoot, "deletionVectors")
            case _ =>
          val path = string(a, "path", source)
          live(path) = AddFile(path, partitionValues(a), long(a, "size"))
        case ("remove", r: JSONObject) =>
          live.remove(string(r, "path", source))
        case ("metaData", m: JSONObject) =>
          metaData = Some(m)
        case ("protocol", p: JSONObject) =>
          protocol = Some(p)
        case _ =>
        // commitInfo, txn, cdc, domainMetadata, and nulls of absent checkpoint columns
      }

    val (cpBytes, commitBytes) = fetched.splitAt(plan.checkpoint.size)
    plan
      .checkpoint
      .zip(cpBytes)
      .foreach { (l, bytes) =>
        val file = Parquet.open(bytes)
        try
          val cols = Seq("protocol", "metaData", "add", "remove").filter(
            file.schema.field(_).isDefined
          )
          file.readJson(ParquetReadOptions.default.withColumns(cols*)).foreach(apply(_, l.path))
        finally
          file.close()
      }
    plan
      .commits
      .zip(commitBytes)
      .foreach { (l, bytes) =>
        String(bytes, StandardCharsets.UTF_8)
          .split('\n')
          .iterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .foreach { line =>
            JSON.parse(line) match
              case o: JSONObject =>
                apply(o, l.path)
              case other =>
                throw ParquetException(s"${l.path} has a line that isn't a JSON object: ${line}")
          }
      }

    val p = protocol.getOrElse(
      throw ParquetException(s"${tableRoot}${LogDir}/ has no protocol action")
    )
    checkProtocol(tableRoot, p)
    val m = metaData.getOrElse(
      throw ParquetException(s"${tableRoot}${LogDir}/ has no metaData action")
    )
    val schemaString = string(m, "schemaString", tableRoot)
    val schema       =
      JSON.parse(schemaString) match
        case o: JSONObject =>
          o
        case _ =>
          throw ParquetException(s"The schema of ${tableRoot} isn't a JSON object")
    val partitionColumns =
      m.get("partitionColumns") match
        case Some(JSONArray(vs)) =>
          vs.collect { case JSONString(s) =>
            s
          }
        case _ =>
          IndexedSeq.empty
    Snapshot(plan.version, live.values.toIndexedSeq, schema, partitionColumns)

  end replay

  private def checkProtocol(tableRoot: String, p: JSONObject): Unit =
    long(p, "minReaderVersion") match
      case 1 =>
      case 2 =>
        unsupported(tableRoot, "columnMapping")
      case 3 =>
        val features =
          p.get("readerFeatures") match
            case Some(JSONArray(vs)) =>
              vs.collect { case JSONString(s) =>
                s
              }
            case _ =>
              IndexedSeq.empty
        val unknown = features.filterNot(SupportedReaderFeatures.contains)
        if unknown.nonEmpty then
          unsupported(tableRoot, unknown.mkString(", "))
      case v =>
        throw ParquetException(
          s"${tableRoot} needs Delta reader version ${v}; Uni reads versions 1 to 3"
        )

  private def unsupported(tableRoot: String, features: String): Nothing =
    throw ParquetException(
      s"${tableRoot} uses Delta reader features that Uni doesn't support: ${features}"
    )

  private def partitionValues(a: JSONObject): Map[String, String] =
    a.get("partitionValues") match
      case Some(JSONObject(kvs)) =>
        kvs
          .map {
            case (k, JSONString(s)) if s.nonEmpty =>
              k -> s
            case (k, _) =>
              // The protocol stores null, and an empty string means null too
              k -> null
          }
          .toMap
      case _ =>
        Map.empty

  private def string(o: JSONObject, key: String, source: String): String =
    o.get(key) match
      case Some(JSONString(s)) =>
        s
      case _ =>
        throw ParquetException(s"A Delta action in ${source} has no ${key}: ${o.toJSON}")

  private def long(o: JSONObject, key: String): Long =
    o.get(key) match
      case Some(JSONLong(v)) =>
        v
      case Some(JSONDouble(v)) =>
        v.toLong
      case Some(JSONString(s)) if s.toLongOption.isDefined =>
        s.toLong
      case _ =>
        -1L

  // ---- The Delta schema ----

  /** The Parquet type a partition column of this Delta type reads as, if one fits exactly */
  def partitionType(deltaType: JSONValue): Option[ParquetType.Primitive] =
    deltaType match
      case JSONString("string") =>
        Some(ParquetType.STRING)
      case JSONString("byte" | "short" | "integer") =>
        Some(ParquetType.INT32)
      case JSONString("long") =>
        Some(ParquetType.INT64)
      case JSONString("date") =>
        Some(ParquetType.DATE)
      case _ =>
        None

  /** The top-level fields of a Delta schema, as name, type, and nullability */
  def fields(schema: JSONObject): IndexedSeq[(String, JSONValue, Boolean)] =
    schema.get("fields") match
      case Some(JSONArray(fs)) =>
        fs.collect { case f: JSONObject =>
          (
            f.get("name")
              .collect { case JSONString(s) =>
                s
              }
              .getOrElse(""),
            f.get("type").getOrElse(JSONNull()),
            !f.get("nullable").contains(JSONBoolean(false))
          )
        }
      case _ =>
        IndexedSeq.empty

  /** Columns for a Delta schema's fields, for the schema of a table without files */
  def columns(fields: Seq[(String, JSONValue, Boolean)]): Seq[ParquetColumn] = fields.map {
    (name, tpe, nullable) =>
      ParquetColumn(name, typeOf(name, tpe), repetition(nullable))
  }

  private def repetition(nullable: Boolean): Repetition =
    if nullable then
      Repetition.OPTIONAL
    else
      Repetition.REQUIRED

  private val DecimalType = """decimal\(\s*(\d+)\s*,\s*(\d+)\s*\)""".r

  private def typeOf(name: String, t: JSONValue): ParquetType =
    t match
      case JSONString(s) =>
        s match
          case "boolean" =>
            ParquetType.BOOLEAN
          case "byte" =>
            ParquetType.INT8
          case "short" =>
            ParquetType.INT16
          case "integer" =>
            ParquetType.INT32
          case "long" =>
            ParquetType.INT64
          case "float" =>
            ParquetType.FLOAT
          case "double" =>
            ParquetType.DOUBLE
          case "string" =>
            ParquetType.STRING
          case "binary" =>
            ParquetType.BINARY
          case "date" =>
            ParquetType.DATE
          case "timestamp" =>
            ParquetType.TIMESTAMP_MICROS
          case "timestamp_ntz" =>
            ParquetType.Primitive(
              PhysicalType.INT64,
              Some(LogicalType.Timestamp(false, TimeUnit.MICROS))
            )
          case DecimalType(p, sc) =>
            ParquetType.decimal(p.toInt, sc.toInt)
          case other =>
            throw ParquetException(s"Column ${name} has the unsupported Delta type ${other}")
      case o: JSONObject =>
        o.get("type") match
          case Some(JSONString("struct")) =>
            ParquetType.Struct(columns(fields(o)))
          case Some(JSONString("array")) =>
            ParquetType.ListOf(
              typeOf(name, o.get("elementType").getOrElse(JSONNull())),
              !o.get("containsNull").contains(JSONBoolean(false))
            )
          case Some(JSONString("map")) =>
            ParquetType.MapOf(
              typeOf(name, o.get("keyType").getOrElse(JSONNull())),
              typeOf(name, o.get("valueType").getOrElse(JSONNull())),
              !o.get("valueContainsNull").contains(JSONBoolean(false))
            )
          case _ =>
            throw ParquetException(s"Column ${name} has the unsupported Delta type ${o.toJSON}")
      case other =>
        throw ParquetException(s"Column ${name} has the unsupported Delta type ${other.toJSON}")

end DeltaLog
