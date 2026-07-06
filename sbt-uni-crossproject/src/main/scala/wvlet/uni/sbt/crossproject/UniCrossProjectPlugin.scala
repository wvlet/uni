/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import sbt.*

/**
  * The sbt 2.x AutoPlugin entry point. Triggered on all projects so its `autoImport` (the
  * `crossProject` macro, the platforms, `CrossType`, and the builder/dependency conversions) is
  * available in every build without an explicit `enablePlugins`.
  */
object UniCrossProjectPlugin extends AutoPlugin:

  override def trigger = allRequirements

  object autoImport:
    type CrossProject = wvlet.uni.sbt.crossproject.CrossProject

    type Platform = wvlet.uni.sbt.crossproject.Platform

    type CrossType = wvlet.uni.sbt.crossproject.CrossType
    val CrossType: wvlet.uni.sbt.crossproject.CrossType.type = wvlet.uni.sbt.crossproject.CrossType

    type CrossClasspathDependency = wvlet.uni.sbt.crossproject.CrossClasspathDependency

    val JVMPlatform: wvlet.uni.sbt.crossproject.JVMPlatform.type =
      wvlet.uni.sbt.crossproject.JVMPlatform

    val JSPlatform: wvlet.uni.sbt.crossproject.JSPlatform.type =
      wvlet.uni.sbt.crossproject.JSPlatform

    val NativePlatform: wvlet.uni.sbt.crossproject.NativePlatform.type =
      wvlet.uni.sbt.crossproject.NativePlatform

    /**
      * Creates a cross-project for the given platforms. Must be assigned directly to a `val`; the
      * val name becomes the project id (e.g. `val core = crossProject(...)` => `coreJVM`, `coreJS`,
      * ...).
      */
    inline def crossProject(inline platforms: Platform*): CrossProject.Builder =
      ${
        CrossProjectMacro.crossProjectImpl('platforms)
      }

    /**
      * Materialise a builder into a cross-project when a [[CrossProject]] method is called on it.
      */
    given Conversion[CrossProject.Builder, CrossProject] = _.build()

    /** Allow a bare cross-project to be used as an (unscoped) classpath dependency. */
    given Conversion[CrossProject, CrossClasspathDependency] =
      cp => CrossClasspathDependency(cp, None)

  end autoImport

end UniCrossProjectPlugin
