import sbt.*
import sbt.Keys.*

/**
  * Replaces the Scaladoc of a Scala.js or Scala Native artifact with a small page that links to the
  * Scaladoc of the JVM artifact.
  *
  * Why: Maven Central caps how much each organization can publish per month
  * (https://central.sonatype.org/publish/maven-central-publishing-limits/), and the `wvlet`
  * organization is shared by uni and airframe. About 58% of a uni release was -javadoc.jar, and the
  * Scala.js and Scala Native jars repeated nearly the same API as the JVM ones (about 26 MB per
  * release). Maven Central still requires a -javadoc.jar for every artifact, so these platforms
  * publish (and sign) a jar with only index.html and README.md. Skipping scaladoc also makes the
  * release build faster.
  *
  * Use it only for a platform of a cross-built module whose JVM artifact is published. Modules
  * without a JVM artifact (e.g. uni-ui, Scala.js only) keep their full Scaladoc. See
  * adr/2026-11-16-minimize-js-native-javadoc.md.
  */
object MinimizeJavadoc:
  val minimizeJavadoc: Seq[Setting[?]] = Seq(
    Compile / doc :=
      Def.uncached {
        val dir        = (Compile / doc / target).value
        val org        = organization.value
        val jvmModule  = s"${moduleName.value}_${scalaBinaryVersion.value}"
        val ver        = version.value
        val jvmDocsUrl = s"https://javadoc.io/doc/${org}/${jvmModule}/${ver}"
        val siteUrl    = homepage.value.map(_.toString).getOrElse("https://wvlet.org/uni/")
        // Remove full Scaladoc output left over from an earlier build
        IO.delete(dir)
        IO.write(dir / "index.html", indexHtml(jvmModule, jvmDocsUrl, siteUrl))
        IO.write(dir / "README.md", readme(jvmModule, jvmDocsUrl, siteUrl))
        dir
      }
  )

  private def indexHtml(jvmModule: String, jvmDocsUrl: String, siteUrl: String): String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<title>${jvmModule} API documentation</title>
       |</head>
       |<body>
       |<h1>API documentation</h1>
       |<p>This artifact shares its API with the JVM artifact <code>${jvmModule}</code>.
       |To keep releases small, the Scaladoc is published only with the JVM artifact:</p>
       |<ul>
       |<li><a href="${jvmDocsUrl}">${jvmDocsUrl}</a></li>
       |<li>Guides, including Scala.js and Scala Native APIs: <a href="${siteUrl}">${siteUrl}</a></li>
       |</ul>
       |</body>
       |</html>
       |""".stripMargin

  private def readme(jvmModule: String, jvmDocsUrl: String, siteUrl: String): String =
    s"""# API documentation
       |
       |This artifact shares its API with the JVM artifact `${jvmModule}`. To keep releases small,
       |the Scaladoc is published only with the JVM artifact:
       |
       |- ${jvmDocsUrl}
       |- Guides, including Scala.js and Scala Native APIs: ${siteUrl}
       |""".stripMargin

end MinimizeJavadoc
