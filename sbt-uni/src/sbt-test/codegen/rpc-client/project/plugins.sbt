sys.props.get("plugin.version") match {
  case Some(v) =>
    addSbtPlugin("org.wvlet.uni" % "sbt-uni" % v)
  case _ =>
    sys.error(
      """|The system property 'plugin.version' is not defined.
         |Specify this property using the scriptedLaunchOpts -D.""".stripMargin
    )
}

val uniVersion = sys.props.getOrElse("uni.version", "0.0.1-SNAPSHOT")
// uni runtime dependency for generated client code
libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion
