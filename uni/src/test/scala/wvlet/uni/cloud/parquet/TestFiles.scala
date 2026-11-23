package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath

/** Finds test resource files; tests run from the repository root or the module directory */
object TestFiles:
  private val roots = Seq(
    "uni/src/test/resources/parquet",
    "src/test/resources/parquet",
    "../src/test/resources/parquet",
    "../../uni/src/test/resources/parquet"
  )

  def path(name: String): IOPath = roots
    .map(r => IOPath.parse(s"${r}/${name}"))
    .find(FileSystem.exists)
    .getOrElse(
      throw IllegalArgumentException(
        s"Test file ${name} not found from ${FileSystem.currentDirectory}"
      )
    )

  def bytes(name: String): Array[Byte] = FileSystem.readBytes(path(name))
