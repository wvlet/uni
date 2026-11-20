/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import sbt.*

/**
  * A target platform a cross-project is built for. `sbtSuffix` is appended to the cross-project id
  * to form each sub-project's id (e.g. `coreJVM`), and `enable` turns a plain sbt `Project` into
  * one for that platform (e.g. by enabling `ScalaJSPlugin`).
  */
sealed trait Platform:
  def identifier: String
  def sbtSuffix: String
  def enable(project: Project): Project

case object JVMPlatform extends Platform:
  def identifier: String                = "jvm"
  def sbtSuffix: String                 = "JVM"
  def enable(project: Project): Project = project

case object JSPlatform extends Platform:
  def identifier: String = "js"
  def sbtSuffix: String  = "JS"

  def enable(project: Project): Project = project.enablePlugins(org.scalajs.sbtplugin.ScalaJSPlugin)

case object NativePlatform extends Platform:
  def identifier: String = "native"
  def sbtSuffix: String  = "Native"

  def enable(project: Project): Project = project.enablePlugins(
    scala.scalanative.sbtplugin.ScalaNativePlugin
  )
