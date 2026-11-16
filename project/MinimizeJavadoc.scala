import sbt.*
import sbt.Keys.*

/**
  * Replaces the Scaladoc of an artifact with a small page that links to where its API is documented.
  *
  * Why: Maven Central caps how much each organization can publish per month
  * (https://central.sonatype.org/publish/maven-central-publishing-limits/), and the `wvlet`
  * organization is shared by uni and airframe. About 58% of a uni release was -javadoc.jar. Maven
  * Central still requires a -javadoc.jar for every artifact, so these artifacts publish (and sign) a
  * jar with only index.html and README.md. Skipping scaladoc also makes the release build faster.
  *
  *   - `minimizeJavadoc`: for a Scala.js or Scala Native platform of a cross-built module whose JVM
  *     artifact is published. The Scala.js and Scala Native jars repeated nearly the same API as the
  *     JVM ones (about 25 MB per release). Modules without a JVM artifact (e.g. uni-ui, Scala.js
  *     only) keep their full Scaladoc.
  *   - `minimizePluginJavadoc`: for the sbt plugins and their libraries, which users configure
  *     through settings described in their guides rather than through the Scaladoc.
  *
  * The sbt plugin sub-builds compile this file into their metabuild (see their project/plugin.sbt).
  * See adr/2026-11-16-minimize-js-native-javadoc.md.
  */
object MinimizeJavadoc:
  private val siteUrl = "https://wvlet.org/uni/"

  val minimizeJavadoc: Seq[Setting[?]] = Seq(
    Compile / doc :=
      Def.uncached {
        val jvmModule  = s"${moduleName.value}_${scalaBinaryVersion.value}"
        val jvmDocsUrl = s"https://javadoc.io/doc/${organization.value}/${jvmModule}/${version.value}"
        writePage(
          (Compile / doc / target).value,
          title = s"${jvmModule} API documentation",
          intro =
            s"This artifact shares its API with the JVM artifact `${jvmModule}`. To keep releases small, the Scaladoc is published only with the JVM artifact:",
          links = Seq(
            ""                                                -> jvmDocsUrl,
            "Guides, including Scala.js and Scala Native APIs: " -> siteUrl
          )
        )
      }
  )

  /**
    * @param guide
    *   the guide page under https://wvlet.org/uni/, e.g. "build/sbt-uni"
    * @param sourceDir
    *   the source directory in the uni repository, e.g. "sbt-uni/src/main/scala"
    */
  def minimizePluginJavadoc(guide: String, sourceDir: String): Seq[Setting[?]] = Seq(
    Compile / doc :=
      Def.uncached {
        val ref       = if isSnapshot.value then "main" else s"v${version.value}"
        val sourceUrl = s"https://github.com/wvlet/uni/tree/${ref}/${sourceDir}"
        writePage(
          (Compile / doc / target).value,
          title = s"${moduleName.value} API documentation",
          intro = "To keep releases small, the Scaladoc of this artifact is not published. See:",
          links = Seq("Guide: " -> s"${siteUrl}${guide}", "Source code: " -> sourceUrl)
        )
      }
  )

  private def writePage(
      dir: File,
      title: String,
      intro: String,
      links: Seq[(String, String)]
  ): File =
    // Remove full Scaladoc output left over from an earlier build
    IO.delete(dir)
    IO.write(dir / "index.html", indexHtml(title, intro, links))
    IO.write(dir / "README.md", readme(intro, links))
    dir

  private def indexHtml(title: String, intro: String, links: Seq[(String, String)]): String =
    val introHtml = intro.replaceAll("`([^`]+)`", "<code>$1</code>")
    val items     = links
      .map((label, url) => s"""<li>${label}<a href="${url}">${url}</a></li>""")
      .mkString("\n")
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<title>${title}</title>
       |</head>
       |<body>
       |<h1>API documentation</h1>
       |<p>${introHtml}</p>
       |<ul>
       |${items}
       |</ul>
       |</body>
       |</html>
       |""".stripMargin

  private def readme(intro: String, links: Seq[(String, String)]): String =
    s"""# API documentation
       |
       |${intro}
       |
       |${links.map((label, url) => s"- ${label}${url}").mkString("\n")}
       |""".stripMargin

end MinimizeJavadoc
