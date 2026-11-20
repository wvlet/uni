package wvlet.uni.sbt.javadoc

import sbt.*
import sbt.Keys.*

/**
  * Shrinks the -javadoc.jar of published artifacts.
  *
  * Why: Maven Central caps how much each organization can publish per month
  * (https://central.sonatype.org/publish/maven-central-publishing-limits/), and Scaladoc output is
  * often the largest part of a Scala release (scala/scala3#26387). Maven Central still requires a
  * -javadoc.jar for every artifact, so the jar cannot simply be dropped.
  *
  * The plugin adds no settings by itself. A build adds, per project:
  *
  *   - `minimizeScaladoc`: keeps the full Scaladoc, but renders its side menu dynamically (Scala 3
  *     only).
  *   - `minimizeJavadoc`: for a Scala.js or Scala Native artifact whose JVM artifact is published,
  *     replaces the Scaladoc with a page that links to the JVM artifact's Scaladoc on javadoc.io.
  *   - `minimizePluginJavadoc`: for sbt plugins and other artifacts documented by guides, replaces
  *     the Scaladoc with a page that links to the guide and the source code.
  *
  * This plugin depends on sbt-salad-days, which also strips fonts and inkuire.js from the Scaladoc.
  */
object UniJavadocPlugin extends AutoPlugin:
  override def trigger = allRequirements

  object autoImport:
    val javadocSiteUrl = settingKey[String](
      "The website the minimized javadoc page links to for guides. Defaults to homepage"
    )

    val javadocSourceUrl = settingKey[String](
      "The repository the minimized javadoc page links to for source code. Defaults to scmInfo.browseUrl"
    )

    /**
      * Renders the Scaladoc side menu dynamically. By default Scaladoc embeds the whole navigation
      * side menu in every HTML page (~100 KB per page); `-dynamic-side-menu` writes the menu once
      * to dynamicSideMenu.json and renders it with JS. Scala 2 Scaladoc has no such option, so this
      * does nothing on Scala 2.
      */
    val minimizeScaladoc: Seq[Setting[?]] = MinimizeScaladoc.settings

    /**
      * For the Scala.js or Scala Native platform of a cross-built module whose JVM artifact is
      * published: replaces the Scaladoc with index.html and README.md linking to the JVM artifact's
      * Scaladoc on javadoc.io and to javadocSiteUrl. Scaladoc does not run.
      */
    val minimizeJavadoc: Seq[Setting[?]] = MinimizeJavadoc.minimizeJavadoc

    /**
      * Replaces the Scaladoc with index.html and README.md linking to javadocSiteUrl (the guide)
      * and javadocSourceUrl (the source code). Scaladoc does not run.
      */
    def minimizePluginJavadoc: Seq[Setting[?]] = MinimizeJavadoc.minimizePluginJavadoc("", "")

    /**
      * Like `minimizePluginJavadoc`, linking to a guide page under javadocSiteUrl and a source
      * directory under javadocSourceUrl.
      *
      * @param guide
      *   the guide path under javadocSiteUrl, e.g. "build/sbt-uni"
      * @param sourceDir
      *   the source directory in the repository, e.g. "sbt-uni/src/main/scala". The link is
      *   `<javadocSourceUrl>/tree/<ref>/<sourceDir>` (GitHub style), where ref is `v<version>` for
      *   a release and `main` for a snapshot.
      */
    def minimizePluginJavadoc(guide: String, sourceDir: String): Seq[Setting[?]] = MinimizeJavadoc
      .minimizePluginJavadoc(guide, sourceDir)

  end autoImport

end UniJavadocPlugin
