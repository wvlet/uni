# Markdown parser (wvlet.uni.text.markdown)

## Context

Uni apps render Markdown from users (chat, notes) and from their own docs and skills. Treasure Work does this with `marked`, `react-markdown`, and `remark-gfm`, plus `dompurify`/`rehype-sanitize` for safety. The design note `plans/2026-10-27-text-parsers.md` asks for a dependency-free CommonMark + GFM parser that runs on JVM, Scala.js, and Native.

## Decisions

The parser ports the CommonMark reference algorithm (commonmark.js) instead of inventing a grammar. Markdown has no context-free grammar, and the reference algorithm is what makes the 652 spec examples pass. Block parsing (`BlockParser`) keeps the open containers as the last-child chain of a mutable `BlockNode` tree: each line first continues open containers, then tries block starts, then becomes text or a lazy paragraph continuation. Inline parsing (`InlineParser`) uses the delimiter stack and bracket stack, including `openers_bottom` keyed by `(char, canOpen, length % 3)`, which keeps emphasis linear on pathological input. Inline content is parsed after all blocks, once every link reference definition is known.

The output is an immutable `enum` AST (`Block`, `Inline`), converted from the mutable trees. Only blocks carry a `Span` (their source lines). Paragraph text is assembled from lines with container prefixes removed, so inline offsets would need a per-line offset map; nothing needs inline spans yet.

The code uses hand-written scanners instead of the regexes in commonmark.js. Scala Native's `java.util.regex` is RE2-based, so lookaheads like the reference's code-fence regex (`(?!.*`)`) do not compile there.

`HtmlRenderer` reproduces the reference renderer's layout (the `cr()` newline rule, tight-list paragraphs without `<p>`), so output matches other CommonMark implementations and the spec HTML byte for byte.

Safety is one switch. `MarkdownConfig.rawHtml = false` (the default) escapes raw HTML and blanks `javascript:`, `vbscript:`, `file:`, and non-image `data:` URLs. `withRawHtml(true)` trusts both, because trusting raw HTML already allows script. The GFM tag filter, which escapes only some tags, is not implemented: half-trusted HTML is the case that `dompurify` exists for, and the escape-all default covers untrusted input.

The GFM extensions follow cmark-gfm:
- A table starts when the line after a paragraph line is a delimiter row with the same number of cells. The paragraph's last line becomes the header, and earlier lines stay a paragraph. The delimiter row must contain `|` or `:`, so that `text` followed by `---` stays a setext heading.
- Task-list markers are detected when converting a list item whose first block is a paragraph.
- Strikethrough uses one or two tildes, and the opener and closer must have the same length.
- Extended autolinks are found in a pass over the text nodes after emphasis processing. The checkbox markup copies the GFM spec output (`<input disabled="" type="checkbox">`).
- `withGfm(false)` gives plain CommonMark, which is how the CommonMark spec examples run.

Frontmatter is read in the same pass, on by default (`MarkdownConfig.frontmatter`). `BlockParser` finds the header with `Frontmatter.split` on the normalized text and starts at the first body line, so block spans stay offsets into the whole text and callers need one call instead of `Frontmatter.parse` plus `Markdown.parse(body)`. It is on by default because a file that starts with `---`, YAML, and `---` is almost always frontmatter (skills, blog posts), and strict CommonMark turns it into a rule and a setext heading. A header counts only when it parses as a YAML mapping or is empty; scalar or invalid YAML leaves the lines as Markdown, so `Markdown.parse` never throws. The spec tests run with `withFrontmatter(false)`.

`toMarkdown` writes a document back losslessly, following Wvlet's parser, which attaches whitespace and comment tokens to nodes. Every block and inline node has a `source: Source` field (last, with a default) holding its exact raw text; blocks also hold the leading trivia since the previous sibling, and containers the text before their first child (`opening`) and after their last (`trailing`). The attachments are immutable fields rather than Wvlet's mutable comment lists, to fit the `enum` AST, and `Source.equals` is always true, so structural equality ignores formatting. Spans are widened to cover children and mapped back to raw input offsets, so CRLF text is exact; reference definitions and blank lines fall into the trivia. Code blocks start before their indentation so that a rewritten block does not indent twice.

Edits are detected without a dirty flag: `Source.parsed` keeps the content fields as parsed, and the writer prints `raw` while the node's content still equals them (`eq` first, then `==`). A `copy` that changes content therefore rewrites just that node; a changed container keeps its opening, trailing, and its unchanged children's sources. Rewritten nodes reuse recorded markers (emphasis delimiter, list marker, setext underline, fence, backtick run, autolink and full-reference forms), use the document's line break, and add the container prefix to each new line. Inline rewriting is checked by parsing the output back with the document's link definitions: the lightest escaping and `*` delimiters are tried first, then `_` for nested emphasis at the edges and full escaping. Text after a line break is always escaped for a line start, because its raw text comes from the paragraph with line indentation stripped and could start a block. `MarkdownRoundTripTest` checks exact output for every spec example (with `\n`, `\r\n`, `\r`, and no final break) and equal HTML when every node is rewritten. The known exceptions are three examples where one tab is shared between a container prefix and an indented code block. Changes to `frontmatter` are written back through the YAML writer (see `2026-10-28-yaml-lossless-tree.md`).

Named entities come from the WHATWG `entities.json`: the 2,125 names that end in `;`. They are generated into `HtmlEntities.scala` as compact strings and parsed lazily into a `Map` on first use.

Block containers nest at most 100 levels (`BlockParser.MaxNesting`; a nested list uses two, for the list and its item), and deeper markers are read as text. Inline emphasis and links nest at most 100 levels (`InlineParser.MaxInlineNesting`), and deeper subtrees become plain text, collected without recursion. Conversion and rendering recurse over the tree, and 1,000 nested `>` overflowed the Scala.js stack.

Scans that could restart at every character are bounded so long inputs stay linear: an autolink stops at the first `<` or whitespace, missing HTML terminators (`-->`, `?>`) are remembered per paragraph, GFM autolinks find the token end once and reject an invalid domain before reading the rest of the token, a table check reads only the paragraph's last line, and adjacent text nodes merge through a buffer.

The spec examples are vendored under `uni/.jvm/src/test/resources/wvlet/uni/text/markdown/` (CC BY-SA 4.0, with attribution in the README there) and run by the JVM-only `MarkdownSpecTest`. Its known-difference list must be exact: an example that starts passing fails the test until it is removed from the list. Shared `MarkdownTest` covers entities, Unicode punctuation, case folding, and the regex-free paths on Scala.js and Native.
