import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
  * Turns the `scala` code fences of Markdown files into Scala sources, so that a compile error in a
  * snippet fails the build (see the docSnippets project in build.sbt).
  *
  * The fences of one Markdown file form a chain: each fence sees what earlier fences defined. A
  * chain is emitted as an object (never run), and the info string after `scala` adjusts a fence:
  *   - `sbt`: a build definition, not compiled
  *   - `skip`: not compiled (placeholders such as `...`, Scala.js or Native-only code)
  *   - `test`: the body goes into a UniTest class that imports the chain, and its tests run
  *   - `reset`: start a new chain from this fence
  *
  * A fence starting with a `package` clause becomes its own source file, and the chains of that
  * Markdown file import the package. Names a snippet uses without defining go in hand-written stubs
  * in the generated package, `<packagePrefix>.<path segments of the Markdown file>`.
  */
object MarkdownSnippets:
  private val modifiers = Set("sbt", "skip", "test", "reset")
  private val fenceOpen = """^(\s*)(`{3,}|~{3,})\s*(.*)$""".r

  case class Fence(line: Int, modifiers: Set[String], code: Seq[String]):
    def packageName: Option[String] = code
      .map(_.trim)
      .find(l => l.nonEmpty && !l.startsWith("//"))
      .collect { case s"package ${name}" =>
        name.trim
      }

  /** Extract the `scala` fences of a Markdown file. */
  def parse(file: File): Seq[Fence] =
    val lines  = Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toIndexedSeq
    val fences = Seq.newBuilder[Fence]
    var i      = 0
    while i < lines.size do
      lines(i) match
        case fenceOpen(indent, marker, info) =>
          val start = i
          i += 1
          val body = Seq.newBuilder[String]
          while i < lines.size && !isFenceClose(lines(i), marker) do
            body += lines(i).stripPrefix(indent)
            i += 1
          if i >= lines.size then
            sys.error(s"${file}:${start + 1}: unclosed code fence")
          // A code-group title such as `[Scala CLI]` may contain spaces, so drop it before splitting
          info.replaceAll("""\[[^\]]*\]""", " ").split("\\s+").filter(_.nonEmpty).toList match
            case "scala" :: rest =>
              // `{1,3}` and `:line-numbers` are VitePress highlighting options, not modifiers
              val mods    = rest.filterNot(m => m.startsWith("{") || m.startsWith(":")).toSet
              val unknown = mods -- modifiers
              if unknown.nonEmpty then
                sys.error(
                  s"${file}:${start + 1}: unknown snippet modifier(s) ${unknown.mkString(", ")}; " +
                    s"use ${modifiers.toSeq.sorted.mkString(", ")}"
                )
              fences += Fence(start + 1, mods, body.result())
            case _ =>
        case _ =>
      i += 1
    fences.result()

  end parse

  private def isFenceClose(line: String, marker: String): Boolean =
    val t = line.trim
    t.startsWith(marker) && t.forall(_ == marker.head)

  /**
    * Generate sources for every Markdown file under each root into outDir, and delete stale files
    * there. A root is a directory or a single Markdown file, and comes with the package prefix of
    * its generated code.
    */
  def generate(roots: Seq[(File, String)], baseDir: File, outDir: File): Seq[File] =
    val generated =
      for
        (root, prefix) <- roots
        md             <- markdownFiles(root)
        out            <- generateFile(md, root, prefix, baseDir, outDir)
      yield out
    generated
      .groupBy(identity)
      .collect {
        case (f, fs) if fs.size > 1 =>
          f
      }
      .foreach(f =>
        sys.error(s"${f}: generated from more than one Markdown file; rename one of them")
      )
    if outDir.exists() then
      val keep = generated.toSet
      listFiles(outDir).filterNot(keep).foreach(_.delete())
    generated

  private def listFiles(dir: File): Seq[File] =
    Using.resource(Files.walk(dir.toPath))(
      _.iterator().asScala.map(_.toFile).filter(_.isFile).toSeq
    )

  private def markdownFiles(root: File): Seq[File] = listFiles(root)
    .filter(_.getName.endsWith(".md"))
    .sortBy(_.getPath)

  // A package segment must be an identifier: `01-intro` becomes `_01_intro`
  private def segment(s: String): String =
    val id = s.toLowerCase.replaceAll("[^a-z0-9]", "_")
    if id.head.isDigit then
      s"_${id}"
    else
      id

  private def generateFile(
      md: File,
      root: File,
      prefix: String,
      baseDir: File,
      outDir: File
  ): Seq[File] =
    // A single-file root contributes its file name as the last package segment
    val base =
      if root.isFile then
        root.getParentFile
      else
        root
    val rel      = base.toPath.relativize(md.toPath).toString.stripSuffix(".md")
    val segments = rel.split("[/\\\\]").toSeq.map(segment)
    val pkgPath  = prefix.split('.').toSeq ++ segments
    val pkg      = pkgPath.mkString(".")
    val dir      = new File(outDir, pkgPath.mkString("/"))
    val source   = baseDir.toPath.relativize(md.toPath).toString.replace('\\', '/')
    val fences   = parse(md).filterNot(f => f.modifiers("sbt") || f.modifiers("skip"))
    val (packageFences, snippets) = fences.partition(_.packageName.isDefined)
    packageFences
      .find(_.modifiers.nonEmpty)
      .foreach(f => sys.error(s"${md}:${f.line}: a package fence takes no modifiers"))

    val packageFiles = packageFences.map { f =>
      val text = s"// Generated from ${source}:${f.line}. Do not edit.\n" + f.code.mkString("\n")
      write(new File(dir, s"Line${f.line}.scala"), text)
    }

    val sb = StringBuilder()
    sb ++= s"// Generated from ${source}. Do not edit; change the Markdown instead.\n"
    sb ++= s"package ${pkg}\n\n"
    packageFences.flatMap(_.packageName).foreach(p => sb ++= s"import ${p}.*\n")
    var chain              = 0
    var opened             = false
    def closeChain(): Unit =
      if opened then
        sb ++= s"end Chain${chain}\n"
        opened = false
    // Test classes go after the chains, so that a test fence does not split its chain
    val tests                                        = StringBuilder()
    def indented(out: StringBuilder, f: Fence): Unit =
      out ++= s"  // ${source}:${f.line}\n"
      f.code
        .foreach(l =>
          out ++= (
            if l.isBlank then
              "\n"
            else
              s"  ${l}\n"
          )
        )

    snippets.foreach { f =>
      if f.modifiers("reset") then
        closeChain()
      if f.modifiers("test") then
        tests ++= s"\nclass Line${f.line}Test extends wvlet.uni.test.UniTest:\n"
        if opened then
          tests ++= s"  import Chain${chain}.*\n"
        indented(tests, f)
      else
        if !opened then
          chain += 1
          opened = true
          sb ++= s"\nobject Chain${chain}\n"
          sb ++= "    extends wvlet.uni.test.Assertions, wvlet.uni.test.TestControl, " +
            "wvlet.uni.test.PropertyCheck:\n"
        indented(sb, f)
    }
    closeChain()
    sb ++= tests.result()
    val main =
      if snippets.isEmpty then
        Nil
      else
        Seq(write(new File(dir, "Snippets.scala"), sb.result()))
    packageFiles ++ main

  end generateFile

  // Leave unchanged files alone so that incremental compilation skips them
  private def write(file: File, text: String): File =
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    if !file.exists() || !java.util.Arrays.equals(Files.readAllBytes(file.toPath), bytes) then
      file.getParentFile.mkdirs()
      Files.write(file.toPath, bytes)
    file

end MarkdownSnippets
