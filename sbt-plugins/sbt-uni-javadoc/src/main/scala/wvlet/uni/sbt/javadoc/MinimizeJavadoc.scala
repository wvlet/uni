package wvlet.uni.sbt.javadoc

import sbt.*
import sbt.Keys.*
import UniJavadocPlugin.autoImport.*

/**
  * Replaces the Scaladoc of an artifact with index.html and README.md that link to where its API is
  * documented. The jar is still the regular packageDoc artifact, so publishSigned signs it as
  * before.
  */
private[javadoc] object MinimizeJavadoc:
  private def siteUrl = Def.setting(javadocSiteUrl.?.value.orElse(homepage.value.map(_.toString)))

  private def sourceUrl = Def.setting(
    javadocSourceUrl.?.value.orElse(scmInfo.value.map(_.browseUrl.toString))
  )

  val minimizeJavadoc: Seq[Setting[?]] = Seq(
    Compile / doc :=
      Def.uncached {
        val jvmModule  = s"${moduleName.value}_${scalaBinaryVersion.value}"
        val jvmDocsUrl =
          s"https://javadoc.io/doc/${organization.value}/${jvmModule}/${version.value}"
        writePage(
          (Compile / doc / target).value,
          title = s"${jvmModule} API documentation",
          intro =
            s"This artifact shares its API with the JVM artifact `${jvmModule}`. To keep releases small, the Scaladoc is published only with the JVM artifact:",
          links =
            Seq("" -> jvmDocsUrl) ++
              siteUrl.value.map("Guides, including Scala.js and Scala Native APIs: " -> _)
        )
      }
  )

  def minimizePluginJavadoc(guide: String, sourceDir: String): Seq[Setting[?]] = Seq(
    Compile / doc :=
      Def.uncached {
        val ref =
          if isSnapshot.value then
            "main"
          else
            s"v${version.value}"
        writePage(
          (Compile / doc / target).value,
          title = s"${moduleName.value} API documentation",
          intro = "To keep releases small, the Scaladoc of this artifact is not published. See:",
          links =
            siteUrl.value.map(url => "Guide: " -> join(url, guide)).toSeq ++
              sourceUrl
                .value
                .map { url =>
                  "Source code: " -> (
                    if sourceDir.isEmpty then
                      url
                    else
                      join(url, s"tree/${ref}/${sourceDir}")
                  )
                }
        )
      }
  )

  private def join(base: String, path: String): String =
    if path.isEmpty then
      base
    else
      s"${base.stripSuffix("/")}/${path.stripPrefix("/")}"

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
