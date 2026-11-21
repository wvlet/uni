import sbt.*

/**
  * Writes uni-ui.css and the `--uni-*` variables it declares into a generated Scala object, so the
  * ui-gallery's tests can load the real styles (the browser cannot read the CSS from a jar) and
  * check that the Colors page shows every color variable the CSS defines.
  */
object CssTokens:
  private val Decl = """--uni-([a-z0-9-]+)\s*:""".r

  /** The variable names declared in `css`, in order of first appearance, without `--uni-`. */
  def parse(css: String): Seq[String] = Decl.findAllMatchIn(css).map(_.group(1)).toSeq.distinct

  /** Write `<pkg>/UiCssTokens.scala` under `outDir` from `cssFile`. */
  def generate(cssFile: File, outDir: File, pkg: String): Seq[File] =
    val css = IO.read(cssFile)
    val q   = "\"\"\""
    require(!css.contains(q), s"${cssFile} must not contain triple quotes")
    val names = parse(css)
    val out   = outDir / pkg.replace('.', '/') / "UiCssTokens.scala"
    IO.write(
      out,
      s"""package ${pkg}
         |
         |/** uni-ui.css and the `--uni-*` variables it declares, without the prefix (generated). */
         |object UiCssTokens:
         |  val declared: Seq[String] = Seq(${names.map(n => s"\"${n}\"").mkString(", ")})
         |""".stripMargin + s"  val text: String = ${q}${css}${q}\n"
    )
    Seq(out)

end CssTokens
