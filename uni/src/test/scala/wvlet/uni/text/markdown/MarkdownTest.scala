package wvlet.uni.text.markdown

import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

class MarkdownTest extends UniTest:

  test("render CommonMark to HTML") {
    Markdown.toHtml("# Title\n\nHello *world* and **bold** `code`.\n") shouldBe
      "<h1>Title</h1>\n<p>Hello <em>world</em> and <strong>bold</strong> <code>code</code>.</p>\n"
    Markdown.toHtml("- a\n- b\n\n1. x\n2. y\n") shouldBe
      "<ul>\n<li>a</li>\n<li>b</li>\n</ul>\n<ol>\n<li>x</li>\n<li>y</li>\n</ol>\n"
    Markdown.toHtml("```scala\nval x = 1 < 2\n```\n") shouldBe
      "<pre><code class=\"language-scala\">val x = 1 &lt; 2\n</code></pre>\n"
    Markdown.toHtml("> quote\n\n---\n[uni][1]\n\n[1]: https://wvlet.org/uni \"Uni\"\n") shouldBe
      "<blockquote>\n<p>quote</p>\n</blockquote>\n<hr />\n<p><a href=\"https://wvlet.org/uni\" title=\"Uni\">uni</a></p>\n"
  }

  test("decode entities and Unicode punctuation on every platform") {
    Markdown.toHtml("&copy; &#x1F600; &ngE; &bogus;") shouldBe "<p>© 😀 ≧̸ &amp;bogus;</p>\n"
    // A Unicode punctuation character before `*` lets the emphasis open
    Markdown.toHtml("«*a*»") shouldBe "<p>«<em>a</em>»</p>\n"
    Markdown.toHtml("[ẞ]\n\n[SS]: /url") shouldBe "<p><a href=\"/url\">ẞ</a></p>\n"
    Markdown.toHtml("[link](/ä b)") shouldBe "<p>[link](/ä b)</p>\n"
    Markdown.toHtml("[link](</ä b>)") shouldBe "<p><a href=\"/%C3%A4%20b\">link</a></p>\n"
  }

  test("render GitHub Flavored Markdown extensions") {
    Markdown.toHtml("| a | b |\n|:--|--:|\n| 1 | 2 |\n") shouldBe
      "<table>\n<thead>\n<tr>\n<th align=\"left\">a</th>\n<th align=\"right\">b</th>\n</tr>\n</thead>\n<tbody>\n<tr>\n<td align=\"left\">1</td>\n<td align=\"right\">2</td>\n</tr>\n</tbody>\n</table>\n"
    Markdown.toHtml("- [x] done\n- [ ] todo\n") shouldBe
      "<ul>\n<li><input checked=\"\" disabled=\"\" type=\"checkbox\"> done</li>\n<li><input disabled=\"\" type=\"checkbox\"> todo</li>\n</ul>\n"
    Markdown.toHtml("~~old~~ new") shouldBe "<p><del>old</del> new</p>\n"
    Markdown.toHtml("See www.example.com/a.") shouldBe
      "<p>See <a href=\"http://www.example.com/a\">www.example.com/a</a>.</p>\n"
    Markdown.toHtml("mail me@example.com") shouldBe
      "<p>mail <a href=\"mailto:me@example.com\">me@example.com</a></p>\n"
  }

  test("follow CommonMark only when GFM is disabled") {
    val strict = MarkdownConfig().withGfm(false)
    Markdown.toHtml("~~a~~ www.example.com", strict) shouldBe "<p>~~a~~ www.example.com</p>\n"
    Markdown.toHtml("| a |\n|---|\n", strict) shouldBe "<p>| a |\n|---|</p>\n"
  }

  test("escape raw HTML and drop unsafe links by default") {
    Markdown.toHtml("<script>alert(1)</script>\n\nhi <b>x</b>") shouldBe
      "&lt;script&gt;alert(1)&lt;/script&gt;\n<p>hi &lt;b&gt;x&lt;/b&gt;</p>\n"
    Markdown.toHtml(
      "[x](javascript:alert(1)) ![i](data:image/png;base64,AA) [d](data:text/html,x)"
    ) shouldBe
      "<p><a href=\"\">x</a> <img src=\"data:image/png;base64,AA\" alt=\"i\" /> <a href=\"\">d</a></p>\n"

    // Scheme checks do not depend on case or the default locale
    Markdown.toHtml("[x](JAVASCRIPT:alert(1)) [y](VBScript:x)") shouldBe
      "<p><a href=\"\">x</a> <a href=\"\">y</a></p>\n"

    val trusted = MarkdownConfig().withRawHtml(true)
    Markdown.toHtml("hi <b>x</b>", trusted) shouldBe "<p>hi <b>x</b></p>\n"
    Markdown.toHtml("[x](javascript:go)", trusted) shouldBe
      "<p><a href=\"javascript:go\">x</a></p>\n"
  }

  test("build an AST with block spans") {
    val text = "# Title\n\nSome *text*\n\n- [x] item\n"
    val doc  = Markdown.parse(text)
    doc.blocks shouldMatch {
      case Seq(
            Block.Heading(1, Seq(Inline.Text("Title", _)), _),
            Block.Paragraph(
              Seq(Inline.Text("Some ", _), Inline.Emphasis(Seq(Inline.Text("text", _)), _)),
              _
            ),
            Block.ListBlock(
              false,
              _,
              true,
              Seq(ListItem(Seq(Block.Paragraph(Seq(Inline.Text("item", _)), _)), Some(true), _)),
              _
            )
          ) =>
    }
    val spans = doc.blocks.map(b => text.substring(b.span.start, b.span.end))
    spans shouldBe Seq("# Title", "Some *text*", "- [x] item")
  }

  test("handle pathological inputs in linear-ish time") {
    val inputs = Seq(
      "*a " * 5000,
      "[" * 5000 + "a" + "]" * 5000,
      "_" * 10000,
      "> " * 1000 + "a",
      "- " * 1000 + "a",
      "<" * 5000,
      "`" * 5000 + "a",
      "**a*" * 3000,
      // Deep inline nesting, unterminated HTML, and long autolink candidates
      "**" * 3000 + "a" + "**" * 3000,
      "a " + "<!--" * 5000,
      "(www." * 5000,
      "www.a.com" + ")" * 5000,
      "x\n" +
        Seq
          .tabulate(5000)(i =>
            if i % 2 == 0 then
              "-:|-:"
            else
              "-:"
          )
          .mkString("\n")
    )
    inputs.foreach { s =>
      Markdown.toHtml(s).nonEmpty shouldBe true
    }
    // Reference definitions alone render nothing
    Markdown.toHtml((0 until 3000).map(i => s"[r${i}]: /u").mkString("\n")) shouldBe ""
  }

  test("read frontmatter in the same pass") {
    case class Post(title: String, tags: Seq[String] = Nil) derives Weaver

    val text = "---\ntitle: Hello\ntags: [a]\n---\n# Body\n"
    val doc  = Markdown.parse(text)
    doc.frontmatterAs[Post] shouldBe Post("Hello", Seq("a"))
    doc.toHtml() shouldBe "<h1>Body</h1>\n"
    // Spans point into the original text, header included
    doc.blocks.head.span.start shouldBe text.indexOf("# Body")

    // \r\n line breaks, an empty header, and a header with no body
    Markdown.parse("---\r\ntitle: A\r\n---\r\ntext").frontmatterAs[Post] shouldBe Post("A")
    val empty = Markdown.parse("---\n---\ntext")
    empty.frontmatter.isDefined shouldBe true
    empty.toHtml() shouldBe "<p>text</p>\n"
    Markdown.parse("---\ntitle: A\n---").blocks shouldBe Nil
  }

  test("keep Markdown when the header is not a YAML mapping or frontmatter is off") {
    // A plain line between rules is a setext heading, not a scalar header
    Markdown.toHtml("---\nSome text\n---\n") shouldBe "<hr />\n<h2>Some text</h2>\n"
    Markdown.parse("---\nSome text\n---\n").frontmatter shouldBe None
    // Invalid YAML falls back to Markdown instead of failing
    Markdown.parse("---\na: [\n---\n").frontmatter shouldBe None

    val strict = MarkdownConfig().withFrontmatter(false)
    Markdown.toHtml("---\ntitle: A\n---\n", strict) shouldBe "<hr />\n<h2>title: A</h2>\n"
  }

  test("write an unchanged document back byte for byte") {
    val text = "---\ntitle: A\n---\n\n#  Title  \r\n\n\n* one\n*   two\n\n> _quote_\n\n    code\n"
    Markdown.parse(text).toMarkdown shouldBe text
  }

  test("change one paragraph and keep the rest as written") {
    val text =
      "# Title\n\nKeep  _this_   as is.\n\nChange *this* [link][r] word.\n\n[r]: https://wvlet.org\n"
    val doc    = Markdown.parse(text)
    val edited = doc.copy(blocks =
      doc
        .blocks
        .map {
          case p @ Block.Paragraph(in @ Seq(Inline.Text("Change ", _), _*), _) =>
            p.copy(inlines = Inline.Text("Changed ") +: in.tail)
          case b =>
            b
        }
    )
    edited.toMarkdown shouldBe
      "# Title\n\nKeep  _this_   as is.\n\nChanged *this* [link][r] word.\n\n[r]: https://wvlet.org\n"
  }

  test("toggle tasks and add list items") {
    def editList(text: String)(f: Block.ListBlock => Block.ListBlock): String =
      val doc = Markdown.parse(text)
      doc
        .copy(blocks =
          doc
            .blocks
            .map {
              case l: Block.ListBlock =>
                f(l)
              case b =>
                b
            }
        )
        .toMarkdown

    editList("- [ ] write docs\n- [x] add tests\n") { l =>
      l.copy(items = l.items.map(_.copy(checked = Some(true))))
    } shouldBe "- [x] write docs\n- [x] add tests\n"

    // The example in docs/text/markdown.md
    val notes =
      "# Notes\n\n- [ ] write _docs_\n- [x] add tests\n\nSee [uni][1].\n\n[1]: https://wvlet.org/uni\n"
    editList(notes) { l =>
      l.copy(items = l.items.map(_.copy(checked = Some(true))))
    } shouldBe notes.replace("[ ]", "[x]")

    editList("1) one\n2) two\n") { l =>
      l.copy(items = l.items :+ ListItem(Seq(Block.Paragraph(Seq(Inline.Text("three")))), None))
    } shouldBe "1) one\n2) two\n3) three\n"
  }

  test("insert and remove blocks inside containers") {
    val quote = Markdown.parse("> a\n")
    quote
      .copy(blocks =
        quote
          .blocks
          .map {
            case q: Block.BlockQuote =>
              q.copy(blocks = q.blocks :+ Block.Paragraph(Seq(Inline.Text("b"))))
            case b =>
              b
          }
      )
      .toMarkdown shouldBe "> a\n>\n> b\n"

    val doc = Markdown.parse("a\n\nb\n\nc\n")
    doc.copy(blocks = doc.blocks.patch(1, Nil, 1)).toMarkdown shouldBe "a\n\nc\n"
  }

  test("keep CRLF line breaks and the frontmatter when editing") {
    val doc = Markdown.parse("---\r\ntitle: A\r\n---\r\n\r\n# A\r\n\r\nold\r\n")
    doc
      .copy(blocks = doc.blocks.updated(1, Block.Paragraph(Seq(Inline.Text("new")))))
      .toMarkdown shouldBe "---\r\ntitle: A\r\n---\r\n\r\n# A\r\n\r\nnew\r\n"
  }

  test("write a document built in code") {
    MarkdownDocument(
      Seq(
        Block.Heading(1, Seq(Inline.Text("Hi"))),
        Block.Paragraph(Seq(Inline.Text("a "), Inline.Strong(Seq(Inline.Text("b*"))))),
        Block.CodeBlock("scala", "val x = 1\n", fenced = true)
      )
    ).toMarkdown shouldBe "# Hi\n\na **b\\***\n\n```scala\nval x = 1\n```\n"
  }

  test("limit the nesting of block quotes and lists") {
    def quoteDepth(blocks: Seq[Block]): Int = blocks
      .collect { case Block.BlockQuote(children, _) =>
        1 + quoteDepth(children)
      }
      .maxOption
      .getOrElse(0)
    quoteDepth(Markdown.parse("> " * 150 + "a").blocks) shouldBe 100
  }

end MarkdownTest
