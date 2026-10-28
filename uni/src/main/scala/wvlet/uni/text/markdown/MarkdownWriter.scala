/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.text.markdown

/**
  * Writes a [[MarkdownDocument]] back to Markdown. A node whose content is still what was parsed
  * prints its [[Source]] text; a changed container prints its children between its original opening
  * and trailing text; and a changed or new leaf prints in a canonical style, keeping the markers
  * recorded in its source (setext underlines, code fences, list markers).
  */
private[markdown] object MarkdownWriter:

  def write(doc: MarkdownDocument): String =
    val src = doc.source
    if !src.isEmpty && same(src.parsed, Tuple1(doc.blocks)) then
      src.raw
    else
      val w = Writer()
      if src.isEmpty then
        w.blocks(doc.blocks, "", "\n", "")
      else
        w.blocks(doc.blocks, src.opening, src.trailing, "")
      w.result()

  /**
    * The content fields of a node, which the converter records in [[Source.parsed]]
    */
  private def content(b: Block): Product =
    b match
      case Block.Paragraph(inlines, _) =>
        Tuple1(inlines)
      case Block.Heading(level, inlines, _) =>
        (level, inlines)
      case Block.BlockQuote(blocks, _) =>
        Tuple1(blocks)
      case Block.ListBlock(ordered, start, tight, items, _) =>
        (ordered, start, tight, items)
      case Block.CodeBlock(info, code, fenced, _) =>
        (info, code, fenced)
      case Block.HtmlBlock(html, _) =>
        Tuple1(html)
      case Block.ThematicBreak(_) =>
        Tuple1(())
      case Block.Table(header, alignments, rows, _) =>
        (header, alignments, rows)

  private def same(parsed: Product | Null, current: Product): Boolean =
    parsed match
      case null =>
        false
      case p: Product =>
        p.productArity == current.productArity &&
        p.productIterator
          .zip(current.productIterator)
          .forall { (a, b) =>
            a.asInstanceOf[AnyRef].eq(b.asInstanceOf[AnyRef]) || a == b
          }

  private def unchanged(b: Block): Boolean       = same(b.source.parsed, content(b))
  private def unchanged(item: ListItem): Boolean = same(
    item.source.parsed,
    (item.blocks, item.checked)
  )

  /**
    * The start of the last line, after a `\n` or `\r` line break
    */
  private def lastLineStart(s: String): Int = s.lastIndexWhere(c => c == '\n' || c == '\r') + 1

  /**
    * The separator before a new block: a blank line, keeping the container prefixes
    */
  private def blankLine(prefix: String): String = s"\n${prefix.stripTrailing()}\n${prefix}"

  private class Writer:
    private val sb = StringBuilder()

    def result(): String = sb.result()

    /**
      * Write sibling blocks. `prefix` is what starts each new line inside the enclosing containers.
      */
    def blocks(bs: Seq[Block], opening: String, trailing: String, prefix: String): Unit =
      sb.append(opening)
      bs.zipWithIndex
        .foreach { (b, i) =>
          if i > 0 then
            sb.append(separator(b.source, blankLine(prefix)))
          block(b, prefix)
        }
      sb.append(trailing)

    private def separator(src: Source, default: String): String =
      if src.leading.isEmpty then
        default
      else
        src.leading

    /**
      * Append text, starting each of its later lines with the container prefix
      */
    private def lines(text: String, prefix: String): Unit = sb.append(
      text.replace("\n", s"\n${prefix}")
    )

    def block(b: Block, prefix: String): Unit =
      if unchanged(b) then
        sb.append(b.source.raw)
      else
        b match
          case Block.Paragraph(inlines, _) =>
            lines(InlineWriter.write(inlines), prefix)
          case Block.Heading(level, inlines, src) =>
            val text = InlineWriter.write(inlines)
            setextUnderline(src.raw) match
              case Some(underline) =>
                lines(s"${text}\n${underline}", prefix)
              case None =>
                // Escape a trailing '#' so that it is not read as a closing sequence
                val line = text.replace('\n', ' ')
                val body =
                  if line.endsWith("#") then
                    s"${line.dropRight(1)}\\#"
                  else
                    line
                sb.append(s"${"#" * level} ${body}")
          case Block.BlockQuote(children, src) =>
            val opening =
              if src.isEmpty then
                "> "
              else
                src.opening
            blocks(children, opening, src.trailing, s"${prefix}> ")
          case l: Block.ListBlock =>
            list(l, prefix)
          case Block.CodeBlock(info, code, fenced, src) =>
            codeBlock(info, code, fenced, src.raw, prefix)
          case Block.HtmlBlock(html, _) =>
            lines(html, prefix)
          case Block.ThematicBreak(src) =>
            // "---" after a paragraph would make a setext heading
            sb.append(
              if src.raw.isEmpty then
                "***"
              else
                src.raw
            )
          case Block.Table(header, alignments, rows, _) =>
            table(header, alignments, rows, prefix)

    private def setextUnderline(raw: String): Option[String] =
      val last = raw.substring(lastLineStart(raw)).trim
      if lastLineStart(raw) > 0 && last.nonEmpty && (last.forall(_ == '=') || last.forall(_ == '-'))
      then
        Some(last)
      else
        None

    private def codeBlock(
        info: String,
        code: String,
        fenced: Boolean,
        raw: String,
        prefix: String
    ): Unit =
      val body = code.stripSuffix("\n")
      if fenced || info.nonEmpty || body.isEmpty then
        val recorded  = raw.dropWhile(_ == ' ').takeWhile(c => c == '`' || c == '~')
        val fenceChar =
          if recorded.nonEmpty then
            recorded.head
          else if info.contains('`') then
            '~'
          else
            '`'
        // The fence must be longer than any fence-like run at the start of a code line
        val longest = body
          .split("\n", -1)
          .map(_.dropWhile(_ == ' ').takeWhile(_ == fenceChar).length)
          .maxOption
          .getOrElse(0)
        val fence = fenceChar.toString * recorded.length.max(3).max(longest + 1)
        val text  =
          if code.isEmpty then
            s"${fence}${info}\n${fence}"
          else
            s"${fence}${info}\n${body}\n${fence}"
        lines(text, prefix)
      else
        lines(
          body
            .split("\n", -1)
            .map(l =>
              if l.isEmpty then
                l
              else
                s"    ${l}"
            )
            .mkString("\n"),
          prefix
        )
      end if

    end codeBlock

    private def list(l: Block.ListBlock, prefix: String): Unit =
      val src = l.source
      // Reuse the recorded bullet character or ordered-list delimiter
      val marker = src.raw.dropWhile(c => c == ' ' || c.isDigit).headOption
      sb.append(src.opening)
      l.items
        .zipWithIndex
        .foreach { (item, i) =>
          if i > 0 then
            val default =
              if l.tight then
                s"\n${prefix}"
              else
                blankLine(prefix)
            sb.append(separator(item.source, default))
          val opening =
            if !item.source.isEmpty then
              item.source.opening
            else if l.ordered then
              s"${l.start + i}${marker.filter(c => c == '.' || c == ')').getOrElse('.')} "
            else
              s"${marker.filter(c => c == '-' || c == '*' || c == '+').getOrElse('-')} "
          listItem(item, opening, prefix)
        }
      sb.append(src.trailing)

    private def listItem(item: ListItem, opening: String, prefix: String): Unit =
      if unchanged(item) then
        sb.append(item.source.raw)
      else
        val inner = s"${prefix}${" " * opening.substring(lastLineStart(opening)).length}"
        item.checked match
          case Some(checked) =>
            // The task marker lives in the first paragraph's text
            val rest = Writer()
            rest.blocks(item.blocks, "", item.source.trailing, inner)
            val text = rest.result()
            val mark =
              if checked then
                "[x]"
              else
                "[ ]"
            sb.append(opening)
            if text.length > 3 && text.charAt(0) == '[' && text.charAt(2) == ']' &&
              " xX".indexOf(text.charAt(1)) >= 0
            then
              sb.append(mark).append(text.substring(3))
            else
              sb.append(s"${mark} ").append(text)
          case None =>
            blocks(item.blocks, opening, item.source.trailing, inner)

    private def table(
        header: Seq[Seq[Inline]],
        alignments: Seq[TableAlignment],
        rows: Seq[Seq[Seq[Inline]]],
        prefix: String
    ): Unit =
      // Escaping `|` covers code spans too, since GFM unescapes `\|` before parsing a cell
      def row(cells: Seq[Seq[Inline]]): String = cells
        .map(c => InlineWriter.write(c).replace("|", "\\|").replace('\n', ' '))
        .mkString("| ", " | ", " |")
      val delimiter = alignments
        .map {
          case TableAlignment.Left =>
            ":--"
          case TableAlignment.Center =>
            ":-:"
          case TableAlignment.Right =>
            "--:"
          case TableAlignment.Default =>
            "---"
        }
        .mkString("| ", " | ", " |")
      lines((Seq(row(header), delimiter) ++ rows.map(row)).mkString("\n"), prefix)

  end Writer

end MarkdownWriter

/**
  * Writes inline nodes in the canonical style: `*` emphasis, `**` strong, `~~` strikethrough, and
  * inline links, escaping the characters that would otherwise start markup
  */
private[markdown] object InlineWriter:

  /**
    * Write inlines with `*` delimiters. When nested emphasis would then read differently (`*_a_*`
    * written as `**a**`), switch the nested delimiters next to an outer one to `_`.
    */
  def write(inlines: Seq[Inline]): String =
    val stars = render(inlines, underscores = false)
    if parsesTo(stars, inlines) then
      stars
    else
      val alternative = render(inlines, underscores = true)
      if parsesTo(alternative, inlines) then
        alternative
      else
        stars

  private def parsesTo(text: String, inlines: Seq[Inline]): Boolean =
    InlineParser(text, scala.collection.mutable.Map.empty, gfm = true).parse() == inlines

  private def render(inlines: Seq[Inline], underscores: Boolean): String =
    val sb = StringBuilder()
    Renderer(sb, underscores).append(inlines)
    sb.result()

  private class Renderer(sb: StringBuilder, underscores: Boolean):
    def append(inlines: Seq[Inline]): Unit = inlines.foreach {
      case Inline.Text(t) =>
        sb.append(escape(t, sb.isEmpty || sb.last == '\n'))
      case Inline.Code(c) =>
        sb.append(codeSpan(c))
      case Inline.Emphasis(children) =>
        delimited("*", children)
      case Inline.Strong(children) =>
        delimited("**", children)
      case Inline.Strikethrough(children) =>
        sb.append("~~")
        append(children)
        sb.append("~~")
      case Inline.Link(destination, title, children) =>
        sb.append('[')
        append(children)
        sb.append(']').append(target(destination, title))
      case Inline.Image(destination, title, alt) =>
        sb.append("![")
        append(alt)
        sb.append(']').append(target(destination, title))
      case Inline.HtmlInline(html) =>
        sb.append(html)
      case Inline.SoftBreak =>
        sb.append('\n')
      case Inline.HardBreak =>
        sb.append("\\\n")
    }

    /**
      * Write emphasis or strong. With `underscores`, a nested one right after the opener or before
      * the closer uses `_`, since `***` would be read as a single run (`*_a_*` is not `**a**`).
      */
    private def delimited(delimiter: String, children: Seq[Inline]): Unit =
      sb.append(delimiter)
      children
        .zipWithIndex
        .foreach { (child, i) =>
          val edge = underscores && (i == 0 || i == children.length - 1)
          child match
            case Inline.Emphasis(c) if edge =>
              sb.append('_')
              append(c)
              sb.append('_')
            case Inline.Strong(c) if edge =>
              sb.append("__")
              append(c)
              sb.append("__")
            case other =>
              append(Seq(other))
        }
      sb.append(delimiter)

  end Renderer

  private val special = "\\`*_[]<>&~!"

  private def escape(text: String, lineStart: Boolean): String =
    val sb = StringBuilder()
    text
      .zipWithIndex
      .foreach { (c, i) =>
        if c == '\n' || c == '\r' then
          // A line break inside text came from a character reference
          sb.append(s"&#${c.toInt};")
        else if i == 0 && lineStart && (c == ' ' || c == '\t') then
          // Leading whitespace on a line would be stripped
          sb.append(s"&#${c.toInt};")
        else
          if special.indexOf(c) >= 0 then
            sb.append('\\')
          else if i == 0 && lineStart && "#-+=>".indexOf(c) >= 0 then
            sb.append('\\')
          sb.append(c)
      }
    // "1." or "1)" at the start of a line would begin an ordered list
    val s      = sb.result()
    val digits = s.takeWhile(_.isDigit).length
    if lineStart && digits > 0 && digits < s.length && ".)".indexOf(s.charAt(digits)) >= 0 then
      s"${s.substring(0, digits)}\\${s.substring(digits)}"
    else
      s

  private def codeSpan(code: String): String =
    val longest = "`+".r.findAllIn(code).map(_.length).maxOption.getOrElse(0)
    val ticks   = "`" * (longest + 1)
    val pad     =
      code.startsWith("`") || code.endsWith("`") ||
        (code.length > 1 && code.startsWith(" ") && code.endsWith(" ") && code.exists(_ != ' '))
    if pad then
      s"${ticks} ${code} ${ticks}"
    else
      s"${ticks}${code}${ticks}"

  private def target(destination: String, title: Option[String]): String =
    val dest =
      if destination.isEmpty || destination.exists(c => c == ' ' || c == '<' || c == '>') then
        s"<${destination.replace("<", "\\<").replace(">", "\\>")}>"
      else
        destination.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    val t = title.map(t => s" \"${t.replace("\\", "\\\\").replace("\"", "\\\"")}\"").getOrElse("")
    s"(${dest}${t})"

end InlineWriter
