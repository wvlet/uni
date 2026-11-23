package wvlet.uni.arrow

import wvlet.uni.arrow.ArrowTestReader.*
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.codec.Inflate
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

object ArrowTestFiles:
  private val roots = Seq(
    "uni/src/test/resources/arrow",
    "src/test/resources/arrow",
    "../src/test/resources/arrow",
    "../../uni/src/test/resources/arrow"
  )

  def bytes(name: String): Array[Byte] =
    val path = roots
      .map(r => IOPath.parse(s"${r}/${name}"))
      .find(FileSystem.exists)
      .getOrElse(
        throw IllegalArgumentException(
          s"Test file ${name} not found from ${FileSystem.currentDirectory}"
        )
      )
    FileSystem.readBytes(path)

/**
  * Checks the test decoder against apache/arrow-testing integration files, written by Arrow C++,
  * and the JSON files that describe their contents. Once it reads those right, ArrowWriterTest can
  * trust it to read what uni writes.
  */
class ArrowGoldenTest extends UniTest:
  FileSystemInit.init()

  private def json(name: String): JSONObject =
    val gz = ArrowTestFiles.bytes(s"arrow-testing/${name}.json.gz")
    JSON.parse(Inflate.gunzip(gz, gz.length * 8)).asInstanceOf[JSONObject]

  private def obj(v: JSONValue): JSONObject = v.asInstanceOf[JSONObject]

  private def arr(v: JSONValue): IndexedSeq[JSONValue] =
    v match
      case a: JSONArray =>
        a.v
      case _ =>
        IndexedSeq.empty

  private def str(v: JSONValue): String =
    v match
      case JSONString(s) =>
        s
      case JSONLong(l) =>
        l.toString
      case JSONDouble(d) =>
        d.toString
      case other =>
        other.toJSON

  private def check(path: String, expected: JSONObject, actual: Col): Unit =
    val count = obj(expected).get("count").map(str).get.toInt
    if actual.length != count then
      fail(s"${path}: length ${actual.length}, expected ${count}")
    val validity = obj(expected).get("VALIDITY").map(arr(_).map(str(_) == "1"))
    validity.foreach { v =>
      (0 until count).foreach { i =>
        if actual.isNull(i) == v(i) then
          fail(s"${path}: row ${i} validity differs")
      }
    }
    obj(expected)
      .get("OFFSET")
      .foreach { o =>
        val offsets = arr(o).map(str(_).toInt)
        if offsets.nonEmpty && offsets != actual.offsets then
          fail(s"${path}: offsets ${actual.offsets}, expected ${offsets}")
      }
    obj(expected)
      .get("DATA")
      .foreach { d =>
        val data = arr(d)
        (0 until count).foreach { i =>
          if !actual.isNull(i) then
            val ok =
              (data(i), actual.values(i)) match
                case (e, a: BigInt) =>
                  BigInt(str(e)) == a
                case (e, a: Float) =>
                  close(str(e).toDouble, a.toDouble)
                case (e, a: Double) =>
                  close(str(e).toDouble, a)
                case (JSONBoolean(e), a: Boolean) =>
                  e == a
                case (e, Hex(a)) =>
                  str(e).toUpperCase == a
                case (e, a: String) =>
                  str(e) == a
                case (e: JSONObject, MonthDayNano(m, dd, ns)) =>
                  e.get("months").map(str).contains(m.toString) &&
                  e.get("days").map(str).contains(dd.toString) &&
                  e.get("nanoseconds").map(str).contains(ns.toString)
                case (e, a) =>
                  fail(s"${path}: cannot compare ${e} with ${a}")
            if !ok then
              fail(s"${path}: row ${i} is ${actual.values(i)}, expected ${data(i)}")
        }
      }
    val children = arr(obj(expected).get("children").getOrElse(JSONArray(IndexedSeq.empty)))
    if children.size != actual.children.size then
      fail(s"${path}: ${actual.children.size} children, expected ${children.size}")
    children
      .zip(actual.children)
      .foreach { (e, a) =>
        check(s"${path}.${a.field.name}", obj(e), a)
      }

  end check

  private def close(e: Double, a: Double): Boolean =
    e == a || math.abs(e - a) <= 1e-3 * math.max(1.0, math.abs(e))

  private def checkFile(name: String, result: Result): Unit =
    val expected = json(name)
    val fields   = arr(obj(expected.get("schema").get).get("fields").get)
    result.fields.map(_.name) shouldBe fields.map(f => str(obj(f).get("name").get))
    result
      .fields
      .zip(fields)
      .foreach { (a, e) =>
        a.nullable shouldBe obj(e).get("nullable").contains(JSONTrue)
      }
    val batches = arr(expected.get("batches").get)
    result.batches.size shouldBe batches.size
    result
      .batches
      .zip(batches)
      .foreach { (a, e) =>
        a.length shouldBe str(obj(e).get("count").get).toInt
        val cols = arr(obj(e).get("columns").get)
        cols
          .zip(a.columns)
          .foreach { (ec, ac) =>
            check(s"${name}.${ac.field.name}", obj(ec), ac)
          }
      }

  private val streams = Seq(
    "generated_primitive",
    "generated_datetime",
    "generated_nested",
    "generated_map",
    "generated_null",
    "generated_decimal",
    "generated_lz4",
    "generated_zstd",
    "generated_uncompressible_lz4",
    "generated_uncompressible_zstd"
  )

  streams.foreach { name =>
    test(s"read ${name}.stream") {
      val r = readStream(ArrowTestFiles.bytes(s"arrow-testing/${name}.stream"))
      r.sawEndOfStream shouldBe true
      checkFile(name, r)
    }
  }

  Seq("generated_primitive", "generated_datetime", "generated_nested", "generated_map")
    .appendedAll(Seq("generated_lz4", "generated_zstd"))
    .foreach { name =>
      test(s"read ${name}.arrow_file") {
        checkFile(name, readFile(ArrowTestFiles.bytes(s"arrow-testing/${name}.arrow_file")))
      }
    }

  test("read the schema of the datetime file") {
    val r = readStream(ArrowTestFiles.bytes("arrow-testing/generated_datetime.stream"))
    r.fields.map(_.typeName).distinct shouldBe Seq("Date", "Time", "Timestamp")
    r.fields.exists(_.params.get("timezone").contains(None)) shouldBe true
    r.fields.exists(_.params.get("timezone").exists(_ != None)) shouldBe true
  }

end ArrowGoldenTest
