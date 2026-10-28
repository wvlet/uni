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

import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets

/**
  * Round-trips every CommonMark and GFM spec example through `toMarkdown`
  */
class MarkdownRoundTripTest extends UniTest:

  private val examples: Seq[SpecExample] = Seq("commonmark-spec-0.31.2.json", "gfm-extensions.json")
    .flatMap { name =>
      val in = getClass.getResourceAsStream(s"/wvlet/uni/text/markdown/${name}")
      try Weaver.fromJson[Seq[SpecExample]](String(in.readAllBytes(), StandardCharsets.UTF_8))
      finally in.close()
    }

  private val config = MarkdownConfig().withRawHtml(true).withFrontmatter(false)

  /**
    * Examples whose canonical rewrite changes the output, with the reason
    */
  private val knownRewriteDifferences: Map[Int, String] = Map(
    5 -> "A tab shared by the list item indentation and an indented code block",
    6 -> "A tab shared by the block quote marker and an indented code block",
    7 -> "A tab shared by the list marker and an indented code block"
  )

  /**
    * Whitespace variants of an input: CRLF and CR line breaks, and no final line break
    */
  private def variants(s: String): Seq[String] =
    Seq(s, s.replace("\n", "\r\n"), s.replace("\n", "\r"), s.stripSuffix("\n")).distinct

  /**
    * A copy of the node whose source no longer matches its content, so the writer treats it as
    * edited but keeps its trivia
    */
  private def dirty(s: Source): Source =
    if s.isEmpty then
      s
    else
      Source(s.raw, s.leading, s.opening, s.trailing, s.span, Tuple1("edited"))

  private def touch(b: Block, containers: Boolean): Block =
    def c(s: Source): Source =
      if containers then
        dirty(s)
      else
        s
    b match
      case Block.Paragraph(in, s) =>
        Block.Paragraph(in, dirty(s))
      case Block.Heading(l, in, s) =>
        Block.Heading(l, in, dirty(s))
      case Block.CodeBlock(i, code, f, s) =>
        Block.CodeBlock(i, code, f, dirty(s))
      case Block.HtmlBlock(h, s) =>
        Block.HtmlBlock(h, dirty(s))
      case Block.ThematicBreak(s) =>
        Block.ThematicBreak(dirty(s))
      case Block.Table(h, a, r, s) =>
        Block.Table(h, a, r, dirty(s))
      case Block.BlockQuote(bs, s) =>
        Block.BlockQuote(bs.map(touch(_, containers)), c(s))
      case Block.ListBlock(o, st, t, items, s) =>
        val newItems = items.map { i =>
          ListItem(i.blocks.map(touch(_, containers)), i.checked, c(i.source))
        }
        Block.ListBlock(o, st, t, newItems, c(s))

  private def show(s: String): String = s
    .replace("\t", "→")
    .replace("\r\n", "␍⏎")
    .replace("\r", "␍")
    .replace("\n", "⏎")

  private def report(failures: Seq[(SpecExample, String)]): Unit =
    if failures.nonEmpty then
      val details = failures
        .distinctBy(_._1.example)
        .take(40)
        .map { (e, out) =>
          s"#${e.example} (${e.section}) ${show(e.markdown)} ==> ${show(out)}"
        }
        .mkString("\n")
      fail(
        s"${failures.size} examples differ: ${failures
            .map(_._1.example)
            .distinct
            .mkString(", ")}\n${details}"
      )

  test("write unchanged documents back byte for byte") {
    val failures =
      for
        e  <- examples
        in <- variants(e.markdown)
        out = Markdown.parse(in, config).toMarkdown
        if out != in
      yield (e, out)
    report(failures)
  }

  test("rebuild every container from its children and trivia") {
    val failures =
      for
        e  <- examples
        in <- variants(e.markdown)
        doc = Markdown.parse(in, config)
        out =
          MarkdownDocument(doc.blocks.map(touch(_, containers = true)), None, dirty(doc.source))
            .toMarkdown
        if Markdown.toHtml(out, config) != Markdown.toHtml(in, config)
        if !knownRewriteDifferences.contains(e.example)
      yield (e, out)
    report(failures)
  }

end MarkdownRoundTripTest
