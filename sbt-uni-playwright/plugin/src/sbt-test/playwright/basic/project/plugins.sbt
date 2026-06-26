addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

sys.props.get("plugin.version") match {
  case Some(v) =>
    addSbtPlugin("org.wvlet.uni" % "sbt-uni-playwright" % v)
  case _ =>
    sys.error("""|The system property 'plugin.version' is not defined.
         |Specify this property using the scriptedLaunchOpts -D.""".stripMargin)
}
