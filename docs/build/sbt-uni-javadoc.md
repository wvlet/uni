# sbt-uni-javadoc Plugin

`sbt-uni-javadoc` shrinks the `-javadoc.jar` your build publishes, so a release
stays within [Maven Central's publishing limits](https://central.sonatype.org/publish/maven-central-publishing-limits/).
It works for any sbt 2.x project, with or without the uni library.

## When to use it

Maven Central caps how much each organization can publish per month, and Scaladoc
output is often the largest part of a Scala release
([scala/scala3#26387](https://github.com/scala/scala3/issues/26387)). Maven Central
still requires a `-javadoc.jar` for every artifact, so the jar cannot simply be
dropped. Reach for this plugin when:

- your organization publishes many artifacts or frequent releases and is close to
  the limit, or
- you cross-build for Scala.js or Scala Native, whose Scaladoc repeats the JVM
  artifact's API, or
- you publish sbt plugins, whose users read guides rather than Scaladoc.

Uni uses it for its own releases. About 58% of a uni release was `-javadoc.jar`
before.

::: warning sbt 2.x only
The plugin targets **sbt 2.x** (its metabuild runs on Scala 3).
:::

## Installation

In `project/plugins.sbt`:

```scala
addSbtPlugin("org.wvlet.uni" % "sbt-uni-javadoc" % "__UNI_VERSION__")
```

This also brings in [sbt-salad-days](https://github.com/sbt/sbt-salad-days),
which strips fonts and `inkuire.js` from Scaladoc output automatically. sbt-salad-days is a great choice on its own if that is all you
need; this plugin adds the reductions below on top of it.

Nothing else changes until you add a setting: choosing which artifacts get which
javadoc is your build's decision. Each setting below is a `Seq[Setting[?]]` you add
to a project's settings.

## Settings

### `minimizeScaladoc`: keep the full Scaladoc, smaller

```scala
lazy val core = project
  .settings(minimizeScaladoc)
```

By default Scaladoc embeds the whole navigation side menu in every HTML page
(about 100 KB per page). `minimizeScaladoc` adds `-dynamic-side-menu`, which writes
the menu once to `dynamicSideMenu.json` and renders it with JavaScript.

Scaladoc up to 3.10 builds broken side-menu links when the docs are not served
from the root of a host, as on javadoc.io
([scala/scala3#27403](https://github.com/scala/scala3/pull/27403)). The setting
patches `scripts/ux.js` in the jar with that fix, and warns once a Scaladoc version
no longer needs the patch.

On Scala 2 the setting does nothing, because Scala 2 Scaladoc has no
`-dynamic-side-menu` option. It is safe to add to settings shared by Scala 2 and
Scala 3 builds.

### `minimizeJavadoc`: link Scala.js and Native artifacts to the JVM Scaladoc

```scala
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .settings(minimizeScaladoc)
  .jsSettings(minimizeJavadoc)
  .nativeSettings(minimizeJavadoc)
```

For a Scala.js or Scala Native artifact whose JVM artifact is also published, the
jar holds only `index.html` and `README.md`. They link to the JVM artifact's
Scaladoc on javadoc.io (`https://javadoc.io/doc/<organization>/<name>_<scala
binary version>/<version>`) and to `javadocSiteUrl`. Scaladoc does not run, so the
release build gets faster too.

Do not add it to a module that is Scala.js or Native only: its Scaladoc is the only
place its API is published.

### `minimizePluginJavadoc`: link to the guide and the source

```scala
lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .settings(minimizePluginJavadoc)
```

For sbt plugins and other artifacts that users learn from guides, the jar holds
only `index.html` and `README.md` linking to `javadocSiteUrl` and
`javadocSourceUrl`.

To link to one guide page and one source directory, pass their paths:

```scala
lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .settings(
    minimizePluginJavadoc(guide = "build/my-plugin", sourceDir = "my-plugin/src/main/scala")
  )
```

This links to `<javadocSiteUrl>/build/my-plugin` and
`<javadocSourceUrl>/tree/<ref>/my-plugin/src/main/scala`, the GitHub URL layout,
where `<ref>` is `v<version>` for a release and `main` for a snapshot.

## Link settings

| Setting            | Default               | Used for                       |
| ------------------ | --------------------- | ------------------------------ |
| `javadocSiteUrl`   | `homepage`            | The guides (website) link      |
| `javadocSourceUrl` | `scmInfo` `browseUrl` | The source code link           |

Set them when the defaults point elsewhere, for example when `homepage` is the
repository rather than the website:

```scala
ThisBuild / javadocSiteUrl := "https://example.org/my-library/"
```

A link whose setting and default are both unset is left out of the page.
