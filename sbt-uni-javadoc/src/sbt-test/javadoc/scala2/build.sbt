import java.util.zip.ZipFile

scalaVersion := "2.13.16"
// Scala 2 Scaladoc rejects -dynamic-side-menu, so minimizeScaladoc must leave it out
minimizeScaladoc

TaskKey[Unit]("check") := {
  val opts = (Compile / doc / scalacOptions).value
  assert(!opts.contains("-dynamic-side-menu"), s"-dynamic-side-menu must not be set: ${opts}")
  val jar = fileConverter.value.toPath((Compile / packageDoc).value).toFile
  val zip = new ZipFile(jar)
  try assert(zip.getEntry("index.html") != null, "The full Scaladoc is missing")
  finally zip.close()
}
