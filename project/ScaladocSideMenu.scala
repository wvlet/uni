import sbt.*
import sbt.Keys.*

/**
  * Scaladoc settings for a small -javadoc.jar, which Maven Central's publishing limits require
  * (https://central.sonatype.org/publish/maven-central-publishing-limits/).
  *
  * By default Scaladoc embeds the whole navigation side menu in every HTML page (~100 KB per page).
  * `-dynamic-side-menu` writes the menu once to dynamicSideMenu.json and renders it with JS.
  *
  * Scaladoc up to 3.10 builds broken menu links unless the docs are served from the root of a host,
  * so the links fail on javadoc.io (/doc/<org>/<artifact>/<version>/...). The bug, and the fix
  * patched in below, is scala/scala3#27403. Remove the patch once uni builds with a Scala release
  * that includes that fix.
  */
object ScaladocSideMenu:
  // In scripts/ux.js, baseUrl is an array of path segments, so `${baseUrl}` joins them with commas
  private val brokenLink = "`${baseUrl}/${loc.join(\"/\")}.html`"
  private val fixedLink  = "`${baseUrl.join(\"/\")}/${loc.join(\"/\")}.html`"

  def patchUxJs(js: String): String = js.replace(brokenLink, fixedLink)

  val settings: Seq[Setting[?]] = Seq(
    Compile / doc / scalacOptions += "-dynamic-side-menu",
    // Def.uncached: the task writes the patched ux.js, which a cached result would not restore
    Compile / packageDoc / mappings :=
      Def.uncached {
        val converter = fileConverter.value
        val patched   = (Compile / target).value / "scaladoc-patch" / "ux.js"
        (Compile / packageDoc / mappings)
          .value
          .map {
            case (ref, path @ "scripts/ux.js") =>
              IO.write(patched, patchUxJs(IO.read(converter.toPath(ref).toFile)))
              converter.toVirtualFile(patched.toPath) -> path
            case m =>
              m
          }
      }
  )
