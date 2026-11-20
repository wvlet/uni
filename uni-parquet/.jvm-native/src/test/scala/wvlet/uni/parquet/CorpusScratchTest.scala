package wvlet.uni.parquet

import wvlet.uni.test.UniTest

class CorpusScratchTest extends UniTest:
  test("corpus") {
    val dir    = sys.env.getOrElse("PARQUET_CORPUS", "")
    val counts = sys.env.getOrElse("PARQUET_COUNTS", "")
    if dir.nonEmpty then
      val expected =
        scala
          .io
          .Source
          .fromFile(counts)
          .getLines()
          .map(_.split(",", -1))
          .map(a => a(0) -> a(1))
          .toMap
      val files = java
        .io
        .File(dir)
        .listFiles()
        .filter(_.getName.endsWith(".parquet"))
        .sortBy(_.getName)
      files.foreach { f =>
        val res =
          try
            val pf = Parquet.open(f.getPath)
            val n  = pf.readRows().size
            pf.close()
            val e = expected.getOrElse(f.getName, "?")
            if e == n.toString then
              s"OK ${n}"
            else
              s"COUNT ${n} expected ${e}"
          catch
            case e: Throwable =>
              s"ERR ${e.getClass.getSimpleName}: ${e.getMessage}".take(200)
        println(s"CORPUS ${f.getName}: ${res}")
      }
    end if
  }

end CorpusScratchTest
