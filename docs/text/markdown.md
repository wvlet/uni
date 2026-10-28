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
| `ListBlock` | `ordered`, `start`, `tight`, `items` (`ListItem(blocks, checked, span)`) |
| `CodeBlock` | `info` (the language is its first word), `code`, `fenced` |
| `HtmlBlock` | `html` |
| `ThematicBreak` | |
| `Table` | `header`, `alignments`, `rows` |

Inline nodes are `Text`, `Code`, `Emphasis`, `Strong`, `Strikethrough`, `Link`, `Image`, `HtmlInline`, `SoftBreak`, and `HardBreak`.

Each block has a `span` with the start and end offsets of its source lines in the input (after `\r\n` is normalized to `\n`). Inline nodes have no spans.

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
