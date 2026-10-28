# Markdown

`wvlet.uni.text.markdown` parses [CommonMark](https://spec.commonmark.org/0.31.2/) with the [GitHub Flavored Markdown](https://github.github.com/gfm/) extensions, and renders it to HTML. It works on JVM, Scala.js, and Scala Native. It passes all 652 examples of the CommonMark 0.31.2 spec, plus the GFM table, task list, strikethrough, and autolink examples.

## Rendering HTML

```scala
import wvlet.uni.text.markdown.Markdown

Markdown.toHtml("""# Release notes

- [x] Add a **YAML** parser
- [ ] Add a cron parser
""")
// <h1>Release notes</h1>
// <ul>
// <li><input checked="" disabled="" type="checkbox"> Add a <strong>YAML</strong> parser</li>
// <li><input disabled="" type="checkbox"> Add a cron parser</li>
// </ul>
```

The output follows the CommonMark reference renderer, so it matches other CommonMark implementations line for line.

## Safe by Default

By default, `Markdown.toHtml` is safe for user-written text:

- Raw HTML (`<script>`, `<b>`, HTML blocks) is escaped and shown as text.
- Links and images with `javascript:`, `vbscript:`, `file:`, or `data:` URLs get an empty URL. Images keep `data:image/png`, `gif`, `jpeg`, and `webp` URLs.

For trusted input, such as your own docs, pass raw HTML and every URL through:

```scala
import wvlet.uni.text.markdown.MarkdownConfig

Markdown.toHtml("Press <kbd>Ctrl</kbd>", MarkdownConfig().withRawHtml(true))
// <p>Press <kbd>Ctrl</kbd></p>
```

Nesting is capped so that malicious input cannot overflow the stack: block containers nest at most 100 levels (a nested list uses two, for the list and its item), and deeper `>` and `-` markers are read as text. Emphasis, strikethrough, and links nest at most 100 levels, and deeper ones become plain text.

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `withRawHtml(Boolean)` | `false` | Pass raw HTML and all link URLs through (trusted input only) |
| `withGfm(Boolean)` | `true` | Enable tables, task lists, strikethrough (`~~x~~`), and extended autolinks (`www.example.com`, `https://...`, emails). With `false`, the parser follows CommonMark only |

## Working with the AST

`Markdown.parse` returns a `MarkdownDocument` of `Block` nodes with `Inline` content, which you can inspect or transform before rendering:

```scala
import wvlet.uni.text.markdown.{Block, Inline}

val doc = Markdown.parse("# Title\n\nSee [Uni](https://wvlet.org/uni).")

// Collect heading texts for a table of contents
val headings = doc.blocks.collect { case Block.Heading(level, inlines, _) =>
  (level, Inline.plainText(inlines))
}
// Seq((1, "Title"))

doc.toHtml() // render the document
```

| Block | Fields |
|-------|--------|
| `Heading` | `level`, `inlines` |
| `Paragraph` | `inlines` |
| `BlockQuote` | `blocks` |
| `ListBlock` | `ordered`, `start`, `tight`, `items` (`ListItem(blocks, checked, source)`) |
| `CodeBlock` | `info` (the language is its first word), `code`, `fenced` |
| `HtmlBlock` | `html` |
| `ThematicBreak` | |
| `Table` | `header`, `alignments`, `rows` |

Inline nodes are `Text`, `Code`, `Emphasis`, `Strong`, `Strikethrough`, `Link`, `Image`, `HtmlInline`, `SoftBreak`, and `HardBreak`.

Every node also has a last field, `source`, which records the text it was parsed from (see [Editing and Writing Back](#editing-and-writing-back)). It has a default, so you can build nodes without it, and it doesn't take part in equality: two nodes with the same content are equal however they were written. A block's `span` gives the start and end offsets of its text in the input.

## Editing and Writing Back

`doc.toMarkdown` writes a document back to Markdown. Without changes, the output is the input byte for byte: blank lines, indentation, trailing spaces, `\r\n` line breaks, the frontmatter, and link reference definitions all stay as written. Edit the AST with `copy`, and only the nodes you changed are rewritten:

```scala
val notes = Markdown.parse("# Notes\n\n- [ ] write _docs_\n- [x] add tests\n\nSee [uni][1].\n\n[1]: https://wvlet.org/uni\n")
val done = notes.copy(blocks = notes.blocks.map {
  case l: Block.ListBlock => l.copy(items = l.items.map(_.copy(checked = Some(true))))
  case b                  => b
})
done.toMarkdown
// # Notes
//
// - [x] write _docs_
// - [x] add tests
//
// See [uni][1].
//
// [1]: https://wvlet.org/uni
```

This works like comments and whitespace in Wvlet's parser: each node carries the text around it as an attachment. A block keeps its exact source, the blank lines and container prefixes (`> `, list indentation) before it, and for a quote, list, or item, the text before its first child and after its last. An inline node keeps its source within the paragraph.

When you change a node, it's written in a canonical style that keeps the markers it was written with: `_` or `*` emphasis, `-` or `1)` list markers, setext underlines, fences, backtick runs, `<autolinks>`, and `[text][label]` references. Unchanged siblings, including the other inlines of an edited paragraph, keep their source text. New nodes use `*` emphasis, `-` bullets, fenced code, and inline links, followed by the document's line break. Rewritten text is escaped only where needed, and it's checked to parse back to the same inlines.

`MarkdownRoundTripTest` checks this on every CommonMark and GFM spec example, with `\n`, `\r\n`, and `\r` line breaks and with no final line break. Without edits the output matches the input exactly. When every node is rewritten, the output renders to the same HTML. The exceptions are three examples where one tab is shared between a container prefix and an indented code block.

Changes to `frontmatter` are written back too. Only the changed parts of the header are rewritten, so its comments stay (see [Frontmatter](./yaml#frontmatter)); `frontmatter = None` removes the header, and a document without one gets a new `---` header.

## Frontmatter

`Markdown.parse` reads a leading YAML header between `---` lines in the same pass, as GitHub does for `.md` files. The header goes to `frontmatter`, is not rendered, and block spans still count from the start of the whole text:

```scala
import wvlet.uni.weaver.Weaver

case class Post(title: String, tags: Seq[String] = Nil) derives Weaver

val doc  = Markdown.parse("---\ntitle: Hello\n---\nThe *body*\n")
val post = doc.frontmatterAs[Post]   // Post(Hello, List())
val html = doc.toHtml()              // <p>The <em>body</em></p>
```

The header must be a YAML mapping (or empty). Otherwise, as with `---\nSome text\n---`, or when the YAML is invalid, the lines stay Markdown, so parsing never fails. `MarkdownConfig().withFrontmatter(false)` turns the header off and gives strict CommonMark, where a leading `---` is a thematic break. To read only the header, use [`Frontmatter`](./yaml#frontmatter).

## Not Supported

- The GFM tag filter, which escapes only some raw HTML tags. Raw HTML is either all escaped (the default) or all trusted.
- Smart punctuation (curly quotes, dashes) and footnotes.
