/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import sbt.*
import sbt.Keys.*

/**
 * A group of sbt projects, one per target platform, that share sources. Created with the
 * `crossProject(...)` macro and materialised through the `Builder => CrossProject` conversion.
 *
 * Only the surface used by uni is provided; in particular the layout is fixed to [[CrossType.Pure]]
 * by default and per-platform source layouts other than Pure/Full are not supported.
 */
final class CrossProject private[crossproject] (
    private[crossproject] val id: String,
    private[crossproject] val crossType: CrossType,
    val projects: Map[Platform, Project]
) extends CompositeProject:

  override def componentProjects: Seq[Project] = projects.valuesIterator.toSeq

  def jvm: Project    = projects(JVMPlatform)
  def js: Project     = projects(JSPlatform)
  def native: Project = projects(NativePlatform)

  def settings(ss: Def.SettingsDefinition*): CrossProject = transform(_.settings(ss*))

  def jvmSettings(ss: Def.SettingsDefinition*): CrossProject = jvmConfigure(_.settings(ss*))

  def jsSettings(ss: Def.SettingsDefinition*): CrossProject = jsConfigure(_.settings(ss*))

  def nativeSettings(ss: Def.SettingsDefinition*): CrossProject = nativeConfigure(_.settings(ss*))

  def configure(transforms: (Project => Project)*): CrossProject =
    transform(_.configure(transforms*))

  def jvmConfigure(transformer: Project => Project): CrossProject =
    configurePlatforms(JVMPlatform)(transformer)

  def jsConfigure(transformer: Project => Project): CrossProject =
    configurePlatforms(JSPlatform)(transformer)

  def nativeConfigure(transformer: Project => Project): CrossProject =
    configurePlatforms(NativePlatform)(transformer)

  def enablePlugins(ns: Plugins*): CrossProject = transform(_.enablePlugins(ns*))

  def disablePlugins(ps: AutoPlugin*): CrossProject = transform(_.disablePlugins(ps*))

  def configs(cs: Configuration*): CrossProject = transform(_.configs(cs*))

  def configurePlatforms(platforms: Platform*)(transformer: Project => Project): CrossProject =
    val updated = platforms.foldLeft(projects): (acc, platform) =>
      acc.get(platform).fold(acc)(p => acc.updated(platform, transformer(p)))
    new CrossProject(id, crossType, updated)

  def dependsOn(deps: CrossClasspathDependency*): CrossProject =
    requireDependencies(deps.toList.map(_.project))
    val byPlatform: Map[Platform, Seq[ClasspathDep[ProjectReference]]] = deps
      .toSeq
      .flatMap: dep =>
        dep.project.projects.map: (platform, project) =>
          platform ->
            (ClasspathDep
              .ClasspathDependency(LocalProject(project.id), dep.configuration): ClasspathDep[
              ProjectReference
            ])
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap
    mapProjectsByPlatform: (platform, project) =>
      project.dependsOn(byPlatform.getOrElse(platform, Nil)*)

  def aggregate(refs: CrossProject*): CrossProject =
    val byPlatform = refs
      .toSeq
      .flatMap(_.projects)
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap
    mapProjectsByPlatform: (platform, project) =>
      project.aggregate(
        byPlatform.getOrElse(platform, Nil).map(p => (LocalProject(p.id): ProjectReference))*
      )

  /** Re-base every platform project under `dir`, following the cross-type's platform layout. */
  def in(dir: File): CrossProject = mapProjectsByPlatform: (platform, project) =>
    project.in(crossType.platformDir(dir, platform))

  /** Builds a dependency on this cross-project, scoped to the given configuration. */
  def %(conf: Configuration): CrossClasspathDependency = %(conf.name)

  def %(conf: String): CrossClasspathDependency = CrossClasspathDependency(this, Some(conf))

  override def toString: String = projects
    .map((platform, project) => s"${platform.identifier} = $project")
    .mkString("CrossProject(", ", ", ")")

  private def platformSet: Set[Platform] = projects.keySet

  private def mapProjectsByPlatform(f: (Platform, Project) => Project): CrossProject =
    new CrossProject(id, crossType, projects.map((platform, project) => platform -> f(platform, project)))

  private def transform(f: Project => Project): CrossProject =
    mapProjectsByPlatform((_, project) => f(project))

  private def requireDependencies(refs: List[CrossProject]): Unit =
    for ref <- refs do
      val missing = platformSet -- ref.platformSet
      if missing.nonEmpty then
        throw IllegalArgumentException(
          s"The cross-project $id cannot depend on ${ref.id} because the latter lacks platforms: " +
            missing.map(_.identifier).mkString(", ")
        )

end CrossProject

object CrossProject:

  /**
   * Accumulates the configuration needed before the platform projects can be created (the id, base
   * directory, platforms and cross-type). Methods of [[CrossProject]] called on a `Builder` trigger
   * `build()` through the `Builder => CrossProject` conversion in the plugin's autoImport.
   */
  final class Builder private[crossproject] (
      id: String,
      base: File,
      platforms: Seq[Platform],
      _crossType: CrossType
  ):

    def crossType(crossType: CrossType): Builder = Builder(id, base, platforms, crossType)

    def build(): CrossProject =
      // The shared-source settings are identical for every platform: each reads its own
      // baseDirectory at load time, so e.g. coreJVM (base uni-core/.jvm) picks up uni-core/src.
      val shared = sharedSrcSettings(_crossType) ++ sharedResourcesSettings(_crossType)

      val projects = platforms.map: platform =>
        val projectID = id + platform.sbtSuffix
        val project = platform.enable(
          Project(projectID, _crossType.platformDir(base, platform))
            .settings((name := id) +: shared*)
        )
        platform -> project
      .toMap

      new CrossProject(id, _crossType, projects)

    private def sharedSrcSettings(crossType: CrossType): Seq[Setting[?]] = Seq(
      Compile / unmanagedSourceDirectories ++=
        makeCrossSources(
          crossType.sharedSrcDir(baseDirectory.value, "main"),
          scalaBinaryVersion.value,
          crossPaths.value
        ),
      Test / unmanagedSourceDirectories ++=
        makeCrossSources(
          crossType.sharedSrcDir(baseDirectory.value, "test"),
          scalaBinaryVersion.value,
          crossPaths.value
        )
    )

    private def sharedResourcesSettings(crossType: CrossType): Seq[Setting[?]] = Seq(
      Compile / unmanagedResourceDirectories ++=
        crossType.sharedResourcesDir(baseDirectory.value, "main").toSeq,
      Test / unmanagedResourceDirectories ++=
        crossType.sharedResourcesDir(baseDirectory.value, "test").toSeq
    )

  end Builder

  /**
   * Expands a shared source directory into the directory plus its Scala cross-version variants
   * (e.g. `src/main/scala-3`, `src/main/scala-2.13`) when `crossPaths` is enabled, matching the
   * convention sbt uses for version-specific shared sources.
   */
  private def makeCrossSources(
      sharedSrcDir: Option[File],
      scalaBinaryVersion: String,
      crossPaths: Boolean
  ): Seq[File] =
    sharedSrcDir match
      case Some(dir) =>
        if crossPaths then
          val scalaEpochVersion = scalaBinaryVersion.takeWhile(_ != '.')
          Seq(
            Some(dir.getParentFile / s"${dir.getName}-$scalaBinaryVersion"),
            if scalaEpochVersion != scalaBinaryVersion then
              Some(dir.getParentFile / s"${dir.getName}-$scalaEpochVersion")
            else
              None,
            Some(dir)
          ).flatten
        else
          Seq(dir)
      case None =>
        Seq()

  def apply(id: String, base: File)(platforms: Platform*): Builder =
    Builder(id, base, platforms, CrossType.Pure)

end CrossProject
