import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

ThisBuild / organization := "org.example"
ThisBuild / version      := "1.2.3"
ThisBuild / scalaVersion := "3.8.2"
ThisBuild / homepage     := Some(uri("https://example.org/docs/"))
ThisBuild / scmInfo      :=
  Some(ScmInfo(uri("https://github.com/example/repo"), "scm:git:git@github.com:example/repo.git"))

def readJar(jar: File): Map[String, String] = {
  val zip = new ZipFile(jar)
  try
    zip
      .entries()
      .asScala
      .map(e => e.getName -> scala.io.Source.fromInputStream(zip.getInputStream(e)).mkString)
      .toMap
  finally zip.close()
}

def checkPage(files: Map[String, String], urls: Seq[String]): Unit = {
  val pages = Set("index.html", "README.md")
  assert(files.keySet == pages + "META-INF/MANIFEST.MF", s"Unexpected files: ${files.keySet}")
  for (file <- pages; url <- urls)
    assert(files(file).contains(url), s"${file} does not link to ${url}:\n${files(file)}")
}

// Stands in for the Scala.js or Scala Native platform of a cross-built module
lazy val core = project
  .settings(minimizeJavadoc)
  .settings(
    TaskKey[Unit]("check") := {
      val jar = fileConverter.value.toPath((Compile / packageDoc).value).toFile
      checkPage(
        readJar(jar),
        Seq("https://javadoc.io/doc/org.example/core_3/1.2.3", "https://example.org/docs/")
      )
    }
  )

lazy val plugin = project
  .settings(
    javadocSiteUrl := "https://example.org/site/",
    minimizePluginJavadoc(guide = "build/plugin", sourceDir = "plugin/src/main/scala"),
    TaskKey[Unit]("check") := {
      val jar = fileConverter.value.toPath((Compile / packageDoc).value).toFile
      checkPage(
        readJar(jar),
        Seq(
          "https://example.org/site/build/plugin",
          "https://github.com/example/repo/tree/v1.2.3/plugin/src/main/scala"
        )
      )
    }
  )

// With no arguments, the page links to homepage and scmInfo.browseUrl
lazy val defaults = project
  .settings(
    minimizePluginJavadoc,
    TaskKey[Unit]("check") := {
      val jar = fileConverter.value.toPath((Compile / packageDoc).value).toFile
      checkPage(readJar(jar), Seq("https://example.org/docs/", "https://github.com/example/repo"))
    }
  )
