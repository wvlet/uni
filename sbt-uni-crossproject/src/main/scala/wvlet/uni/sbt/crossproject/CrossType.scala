/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import sbt.*

import java.io.File

/**
  * Describes how a cross-project's directories are laid out: where each platform's sbt project
  * lives and where the sources shared across platforms live.
  */
abstract class CrossType:

  /** The base directory of the sbt project for `platform`. */
  def projectDir(crossBase: File, platform: Platform): File

  /**
    * The shared source directory for a configuration ("main" or "test"), relative to a platform
    * project's base directory, if any.
    */
  def sharedSrcDir(projectBase: File, conf: String): Option[File]

  /** The shared resources directory for a configuration, if any. */
  def sharedResourcesDir(projectBase: File, conf: String): Option[File] = None

  final def platformDir(crossBase: File, platform: Platform): File = projectDir(crossBase, platform)

end CrossType

object CrossType:

  /**
    * {{{
    * .
    * ├── .js
    * ├── .jvm
    * ├── .native
    * └── src        // shared
    * }}}
    */
  object Pure extends CrossType:
    def projectDir(crossBase: File, platform: Platform): File =
      crossBase / ("." + platform.identifier)

    def sharedSrcDir(projectBase: File, conf: String): Option[File] = Some(
      projectBase.getParentFile / "src" / conf / "scala"
    )

    override def sharedResourcesDir(projectBase: File, conf: String): Option[File] = Some(
      projectBase.getParentFile / "src" / conf / "resources"
    )

  /**
    * {{{
    * .
    * ├── js
    * ├── jvm
    * ├── native
    * └── shared
    * }}}
    */
  object Full extends CrossType:
    def projectDir(crossBase: File, platform: Platform): File = crossBase / platform.identifier

    def sharedSrcDir(projectBase: File, conf: String): Option[File] = Some(
      projectBase.getParentFile / "shared" / "src" / conf / "scala"
    )

    override def sharedResourcesDir(projectBase: File, conf: String): Option[File] = Some(
      projectBase.getParentFile / "shared" / "src" / conf / "resources"
    )

end CrossType
