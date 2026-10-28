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

import scala.collection.mutable

/**
  * Writes a [[MarkdownDocument]] back to Markdown. A node whose content is still what was parsed
  * prints its [[Source]] text; a changed container prints its children between its original opening
  * and trailing text; and a changed or new node prints in a canonical style, keeping the markers
  * recorded in its source (setext underlines, code fences, list markers, emphasis delimiters,
  * reference links).
  */
private[markdown] object MarkdownWriter:

  def write(doc: MarkdownDocument): String =
    val src = doc.source
    if Source.same(src.parsed, (doc.blocks, doc.frontmatter)) then
      src.raw
    else
      val lb     = lineBreak(src.raw)
      val w      = Writer(src.definitions, lb)
      val fm     = src.frontmatter
      val header = fm.headerText(doc.frontmatter, lb)
      if src.isEmpty then
        w.blocks(doc.blocks, header, "\n", "")
      else
        // Replace the header at the start of the opening text. Without a header, the blank lines
        // that followed it go too.
        val rest    = src.opening.substring(fm.length)
        val opening =
          if header.isEmpty && fm.length > 0 then
            rest.dropWhile(c => c == '\n' || c == '\r')
          else
            header + rest
        w.blocks(doc.blocks, opening, src.trailing, "", originalBlocks(src))
      w.result()

  private def unchanged(b: Block): Boolean       = Source.same(b.source.parsed, Source.content(b))
  private def unchanged(item: ListItem): Boolean = Source.same(
    item.source.parsed,
    (item.blocks, item.checked)
  )

  /**
    * The child blocks recorded when a container was parsed
    */
  private def originalBlocks(src: Source): Seq[Block] =
    src.parsed match
      case p: Product if p.productArity > 0 =>
        p.productElement(0) match
          case s: Seq[?] =>
            s.collect { case b: Block =>
              b
            }
          case _ =>
            Nil
      case _ =>
        Nil

  /**
    * Was the list item a task when it was parsed?
    */
  private def originalChecked(src: Source): Boolean =
    src.parsed match
      case p: Product if p.productArity > 1 =>
        p.productElement(1) != None
      case _ =>
        false

  /**
    * The start of the last line, after a `\n` or `\r` line break
    */
  private def lastLineStart(s: String): Int = s.lastIndexWhere(c => c == '\n' || c == '\r') + 1

  /**
    * The document's line break, used for new and rewritten text
    */
  private def lineBreak(raw: String): String =
    val i = raw.indexWhere(c => c == '\n' || c == '\r')
    if i < 0 || raw.charAt(i) == '\n' then
      "\n"
    else if i + 1 < raw.length && raw.charAt(i + 1) == '\n' then
      "\r\n"
    else
      "\r"

  private class Writer(definitions: Map[String, (String, Option[String])], nl: String):
    private val sb = StringBuilder()

    def result(): String = sb.result()

    /**
      * The separator before a new block: a blank line, keeping the container prefixes
      */
    private def blankLine(prefix: String): String = s"${nl}${prefix.stripTrailing()}${nl}${prefix}"

    private def inlines(in: Seq[Inline]): String = InlineWriter.write(in, definitions)

    /**
      * Write sibling blocks. `prefix` is what starts each new line inside the enclosing containers.
      */
    def blocks(
        bs: Seq[Block],
        opening: String,
        trailing: String,
        prefix: String,
        original: Seq[Block] = Nil
    ): Unit =
      // Link reference definitions sit in the trivia before the next block. Keep the ones in
      // front of removed blocks, so that deleting a paragraph does not break links elsewhere.
      val kept    = bs.map(_.source).filterNot(_.isEmpty).toSet
      val removed = original
        .zipWithIndex
        .filter((b, _) => !kept.exists(_ eq b.source) && b.source.leading.contains('['))
      val position                             = original.map(_.source).zipWithIndex
      var flushed                              = 0
      def flushRemovedBefore(index: Int): Unit =
        removed
          .filter((_, i) => i >= flushed && i < index)
          .foreach((b, _) => sb.append(b.source.leading.stripTrailing()))
        flushed = flushed.max(index)
      sb.append(opening)
      bs.zipWithIndex
        .foreach { (b, i) =>
          position.find(_._1 eq b.source).foreach((_, index) => flushRemovedBefore(index))
          if i > 0 then
            sb.append(separator(b.source, blankLine(prefix)))
          block(b, prefix)
        }
      flushRemovedBefore(original.length)
      sb.append(trailing)

    end blocks

    private def separator(src: Source, default: String): String =
      if src.leading.isEmpty then
        default
      else
        src.leading

    /**
      * Append text, starting each of its later lines with the container prefix
      */
    private def lines(text: String, prefix: String): Unit = sb.append(
      text.replace("\n", s"${nl}${prefix}")
    )

    def block(b: Block, prefix: String): Unit =
      if unchanged(b) then
        sb.append(b.source.raw)
      else
        b match
          case Block.Paragraph(in, _) =>
            lines(inlines(in), prefix)
          case Block.Heading(level, in, src) =>
            val text = inlines(in)
            setextUnderline(src.raw) match
              case Some(underline) if level <= 2 =>
                // Keep the underline length; its character follows the current level
                val c =
                  if level == 1 then
                    "="
                  else
                    "-"
                lines(s"${text}\n${c * underline.length}", prefix)
              case _ =>
                // Escape a trailing '#' so that it is not read as a closing sequence
                val line = text.replace('\n', ' ')
                val body =
                  if line.endsWith("#") && !line.endsWith("\\#") then
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
            blocks(children, opening, src.trailing, s"${prefix}> ", originalBlocks(src))
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
                s"${nl}${prefix}"
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
            val rest = Writer(definitions, nl)
            rest.blocks(item.blocks, "", item.source.trailing, inner, originalBlocks(item.source))
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
          case None if item.source.parsed != null && originalChecked(item.source) =>
            // The item is no longer a task: drop the marker from the first paragraph's text
            val rest = Writer(definitions, nl)
            rest.blocks(item.blocks, "", item.source.trailing, inner, originalBlocks(item.source))
            val text = rest.result()
            sb.append(opening)
            if text.length > 3 && text.charAt(0) == '[' && text.charAt(2) == ']' &&
              " xX".indexOf(text.charAt(1)) >= 0
            then
              sb.append(text.substring(3).dropWhile(c => c == ' ' || c == '\t'))
            else
              sb.append(text)
          case None =>
            blocks(item.blocks, opening, item.source.trailing, inner, originalBlocks(item.source))
        end match

    private def table(
        header: Seq[Seq[Inline]],
        alignments: Seq[TableAlignment],
        rows: Seq[Seq[Seq[Inline]]],
        prefix: String
    ): Unit =
      // Escaping `|` covers code spans too, since GFM unescapes `\|` before parsing a cell
      def row(cells: Seq[Seq[Inline]]): String = cells
        .map(c => inlines(c).replace("|", "\\|").replace('\n', ' '))
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
  * Writes inline nodes. Unchanged nodes print their source text. Changed or new ones print in a
  * canonical style that keeps their recorded markers: `*` or `_` emphasis, backtick runs,
  * autolinks, and reference links.
  */
private[markdown] object InlineWriter:

  /**
    * Write inlines, choosing the lightest escaping and delimiters that parse back to the same
    * inlines. For example, `*_a_*` written as `**a**` would read as strong emphasis.
    */
  def write(inlines: Seq[Inline], definitions: Map[String, (String, Option[String])]): String =
    val refs                            = mutable.Map.from(definitions)
    def parsesTo(text: String): Boolean = InlineParser(text, refs, gfm = true).parse() == inlines
    val first                           = render(inlines, underscores = false, fullEscape = false)
    if parsesTo(first) then
      first
    else
      val others =
        for (underscores, fullEscape) <- Iterator((true, false), (false, true), (true, true))
        yield render(inlines, underscores, fullEscape)
      others.find(parsesTo).getOrElse(first)

  private def render(inlines: Seq[Inline], underscores: Boolean, fullEscape: Boolean): String =
    val sb = StringBuilder()
    Renderer(sb, underscores, fullEscape).append(inlines)
    sb.result()

  private class Renderer(sb: StringBuilder, underscores: Boolean, fullEscape: Boolean):
    def append(inlines: Seq[Inline]): Unit = inlines
      .zipWithIndex
      .foreach { (i, idx) =>
        one(i, inlines.lift(idx + 1))
      }

    private def one(i: Inline, next: Option[Inline]): Unit =
      // Text after a line break may have been indented or a lazy continuation line, where it
      // could not start a block; escape it for the start of a line
      val afterBreak = sb.nonEmpty && sb.last == '\n' && i.isInstanceOf[Inline.Text]
      if !afterBreak && Source.same(i.source.parsed, Source.content(i)) then
        sb.append(i.source.raw)
      else
        i match
          case Inline.Text(t, _) =>
            sb.append(escape(t, sb.isEmpty || sb.last == '\n', next, fullEscape))
          case Inline.Code(c, src) =>
            sb.append(codeSpan(c, src.raw))
          case Inline.Emphasis(children, src) =>
            delimited(delimiterOf(src, "*", 1), children)
          case Inline.Strong(children, src) =>
            delimited(delimiterOf(src, "**", 2), children)
          case Inline.Strikethrough(children, src) =>
            val tildes = Some(src.raw.takeWhile(_ == '~'))
              .filter(t => t.length == 1 || t.length == 2)
              .getOrElse("~~")
            sb.append(tildes)
            append(children)
            sb.append(tildes)
          case l: Inline.Link =>
            link(l)
          case Inline.Image(destination, title, alt, _) =>
            sb.append("![")
            append(alt)
            sb.append(']').append(target(destination, title))
          case Inline.HtmlInline(html, _) =>
            sb.append(html)
          case Inline.SoftBreak(_) =>
            sb.append('\n')
          case Inline.HardBreak(src) =>
            sb.append(
              if src.raw.isEmpty then
                "\\\n"
              else
                src.raw
            )
      end if

    end one

    private def delimiterOf(src: Source, default: String, n: Int): String =
      src.raw.headOption match
        case Some(c) if c == '*' || c == '_' =>
          c.toString * n
        case _ =>
          default

    /**
      * Write emphasis or strong. With `underscores`, a nested one right after the opener or before
      * the closer switches to the other delimiter, since `***` would be read as a single run.
      */
    private def delimited(delimiter: String, children: Seq[Inline]): Unit =
      val other =
        if delimiter.head == '*' then
          "_"
        else
          "*"
      sb.append(delimiter)
      children
        .zipWithIndex
        .foreach { (child, i) =>
          val edge = underscores && (i == 0 || i == children.length - 1)
          child match
            case Inline.Emphasis(c, _) if edge =>
              sb.append(other)
              append(c)
              sb.append(other)
            case Inline.Strong(c, _) if edge =>
              sb.append(other * 2)
              append(c)
              sb.append(other * 2)
            case _ =>
              one(child, children.lift(i + 1))
        }
      sb.append(delimiter)

    /**
      * Keep an autolink or a full reference link when its target is unchanged; otherwise write an
      * inline link
      */
    private def link(l: Inline.Link): Unit =
      val raw        = l.source.raw
      val sameTarget = Option(l.source.parsed).exists(p =>
        p.productElement(0) == l.destination && p.productElement(1) == l.title
      )
      val autolinkText =
        l.children match
          case Seq(Inline.Text(t, _)) if t == l.destination || l.destination == s"mailto:${t}" =>
            Some(t)
          case _ =>
            None
      val label = raw.lastIndexOf("][")
      if raw.startsWith("<") && raw.endsWith(">") && autolinkText.nonEmpty then
        sb.append(s"<${autolinkText.get}>")
      else if sameTarget && label > 0 && raw.endsWith("]") && raw.length > label + 3 then
        sb.append('[')
        append(l.children)
        sb.append(raw.substring(label + 1))
      else
        sb.append('[')
        append(l.children)
        sb.append(']').append(target(l.destination, l.title))

  end Renderer

  private val special = "\\`*_[]<>&~!"

  /**
    * Escape the characters that would start markup. Without `fullEscape`, `<`, `&`, and `!` are
    * escaped only where they would.
    */
  private def escape(
      text: String,
      lineStart: Boolean,
      next: Option[Inline],
      fullEscape: Boolean
  ): String =
    val sb                 = StringBuilder()
    def after(i: Int): Int =
      if i + 1 < text.length then
        text.charAt(i + 1)
      else
        -1
    def needed(c: Char, i: Int): Boolean =
      if fullEscape then
        special.indexOf(c) >= 0
      else
        c match
          case '\\' | '`' | '*' | '_' | '[' | ']' | '~' =>
            true
          case '<' =>
            val a = after(i)
            a == -1 || a.toChar.isLetter || a == '/' || a == '!' || a == '?'
          case '&' =>
            val a = after(i)
            a == '#' || a != -1 && a.toChar.isLetterOrDigit
          case '!' =>
            after(i) == '[' || after(i) == -1 && next.exists(_.isInstanceOf[Inline.Link])
          case _ =>
            false
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
          if needed(c, i) || i == 0 && lineStart && "#-+=>".indexOf(c) >= 0 then
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

  end escape

  /**
    * A code span, reusing the recorded backtick run when no run of that length is in the code
    */
  private def codeSpan(code: String, raw: String): String =
    val runs     = "`+".r.findAllIn(code).map(_.length).toSet
    val recorded = raw.takeWhile(_ == '`').length
    val n        =
      if recorded > 0 && !runs.contains(recorded) then
        recorded
      else
        runs.maxOption.getOrElse(0) + 1
    val ticks = "`" * n
    val pad   =
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
