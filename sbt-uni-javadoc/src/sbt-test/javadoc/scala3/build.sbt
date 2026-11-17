import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

// Scaladoc 3.8 still has the side menu link bug (scala/scala3#27403) that minimizeScaladoc patches
scalaVersion := "3.8.2"
minimizeScaladoc

TaskKey[Unit]("check") := {
  val opts = (Compile / doc / scalacOptions).value
  assert(opts.contains("-dynamic-side-menu"), s"-dynamic-side-menu is missing: ${opts}")

  val jar     = fileConverter.value.toPath((Compile / packageDoc).value).toFile
  val zip     = ZipFile(jar)
  val entries = zip.entries().asScala.map(_.getName).toSet
  def read(name: String) = scala.io.Source.fromInputStream(zip.getInputStream(zip.getEntry(name))).mkString
  try {
    assert(entries.contains("dynamicSideMenu.json"), s"dynamicSideMenu.json is missing: ${entries}")
    val ux = read("scripts/ux.js")
    assert(ux.contains("${baseUrl.join(\"/\")}/${loc.join(\"/\")}.html"), "ux.js is not patched")
    // sbt-salad-days comes with this plugin and strips inkuire.js and the fonts
    assert(!entries.contains("scripts/inkuire.js"), "inkuire.js is not stripped")
    assert(!entries.exists(_.startsWith("fonts/")), "fonts are not stripped")
  } finally zip.close()
}
