/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import sbt.*

/**
  * A dependency of one cross-project on another, optionally scoped to a configuration (e.g.
  * `Test`). Built via `someCrossProject % "test"`; a bare cross-project converts to an unscoped
  * dependency.
  */
final class CrossClasspathDependency(val project: CrossProject, val configuration: Option[String])
