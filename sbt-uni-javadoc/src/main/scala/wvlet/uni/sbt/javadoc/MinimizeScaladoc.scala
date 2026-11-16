package wvlet.uni.sbt.javadoc

import sbt.*
import sbt.Keys.*

/**
  * Scaladoc up to 3.10 builds broken side menu links with `-dynamic-side-menu` unless the docs are
  * served from the root of a host, so the links fail on javadoc.io
  * (/doc/<org>/<artifact>/<version>/ ...). The bug, and the fix patched in below, is
  * scala/scala3#27403. Remove the patch once the Scala releases this plugin supports include that
  * fix.
  */
private[javadoc] object MinimizeScaladoc:
  // In scripts/ux.js, baseUrl is an array of path segments, so `${baseUrl}` joins them with commas
  private val brokenLink = "`${baseUrl}/${loc.join(\"/\")}.html`"
  private val fixedLink  = "`${baseUrl.join(\"/\")}/${loc.join(\"/\")}.html`"

  private def isScala3 = Def.setting(scalaBinaryVersion.value == "3")

  val settings: Seq[Setting[?]] = Seq(
    // Scala 2 Scaladoc rejects this option
    Compile / doc / scalacOptions ++= {
      if isScala3.value then
        Seq("-dynamic-side-menu")
      else
        Nil
    },
    // Def.uncached: the task writes the patched ux.js, which a cached result would not restore
    Compile / packageDoc / mappings :=
      Def.uncached {
        val converter = fileConverter.value
        val log       = streams.value.log
        val patched   = (Compile / target).value / "scaladoc-patch" / "ux.js"
        val scala3    = isScala3.value
        (Compile / packageDoc / mappings)
          .value
          .map {
            case (ref, path @ "scripts/ux.js") if scala3 =>
              val js = IO.read(converter.toPath(ref).toFile)
              if !js.contains(brokenLink) then
                log.warn(
                  "Scaladoc's ux.js no longer has the side menu link bug (scala/scala3#27403); " +
                    "remove the ux.js patch in sbt-uni-javadoc (MinimizeScaladoc.scala)"
                )
              IO.write(patched, js.replace(brokenLink, fixedLink))
              converter.toVirtualFile(patched.toPath) -> path
            case m =>
              m
          }
      }
  )

end MinimizeScaladoc
