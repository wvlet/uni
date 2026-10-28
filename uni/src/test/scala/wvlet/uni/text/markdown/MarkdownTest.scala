package wvlet.uni.text.markdown

import wvlet.uni.test.UniTest
import wvlet.uni.text.yaml.Frontmatter

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
            Block.Heading(1, Seq(Inline.Text("Title")), _),
            Block.Paragraph(
              Seq(Inline.Text("Some "), Inline.Emphasis(Seq(Inline.Text("text")))),
              _
            ),
            Block.ListBlock(
              false,
              _,
              true,
              Seq(ListItem(Seq(Block.Paragraph(Seq(Inline.Text("item")), _)), Some(true), _)),
              _
            )
          ) =>
    }
    val spans = doc.blocks.map(b => text.substring(b.span.start, b.span.end))
    spans shouldBe Seq("# Title", "Some *text*", "- [x] item")
  }

  test("render a Markdown body after frontmatter") {
    val f = Frontmatter.parse("---\ntitle: Notes\n---\n## Today\n")
    Markdown.toHtml(f.body) shouldBe "<h2>Today</h2>\n"
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
      "**a*" * 3000
    )
    inputs.foreach { s =>
      Markdown.toHtml(s).nonEmpty shouldBe true
    }
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
