# YAML, Markdown, and Cron Parsers for Uni

## Goals

- Fill the three text-format gaps found by comparing Treasure Work's npm dependencies with Uni: `yaml`, `marked`/`remark-gfm`, and `cronstrue` (plus the cron schedulers apps pair with it).
- Read YAML, Markdown, and cron expressions with no extra dependencies on JVM, Scala.js, and Native, so apps like Treasure Work can keep skill frontmatter, `agent.yml`, docs rendering, and schedules in Scala.
- Build all three on `wvlet.uni.text`, the scanner framework ported from Wvlet, so they share spans, error reporting, and tokenizer code instead of each carrying its own.

## Background

- `wvlet.uni.text.parser` (in `uni-core`) holds `ScannerBase[Token]`, `TokenTypeInfo`, `TokenBuffer`, `TextParseException`, and `Span`. These came from Wvlet's SQL/flow-language scanner. Today only `CodeFormatter` and the RPC client generator use them.
- `ScannerBase` is a programming-language tokenizer. It handles identifiers, numbers, operators, quoted strings, and comments, and it skips whitespace. That fits cron directly. YAML and Markdown are line- and indentation-oriented: block structure depends on column positions, blank lines, and line prefixes (`- `, `> `, `|`), and inline Markdown is character-level. Forcing those formats through `ScannerBase` tokens would fight the abstraction.
- Uni can write YAML (`json.YAMLFormatter`) but cannot read it. `Weaver.fromJSONValue` already decodes a `JSONValue` into case classes, so a YAML reader that produces `JSONValue` gets case-class decoding for free.
- On Scala.js, `scalajs-java-time` 1.0.0 provides `Instant` and `Duration` but no `ZoneId` or `ZonedDateTime`. Cron's `next()` therefore needs its own civil-calendar arithmetic to stay cross-platform.

## Design

**Lane: Standard.** This adds new public APIs (three subpackages of `wvlet.uni.text`), and the scope needs your agreement.

### Shared layer (`wvlet.uni.text.parser`)

- Add a small `LineReader` (character cursor over `IArray[Char]`). It tracks offset, line, column, and indentation, and offers `peek`, `lookAhead(n)`, `restOfLine`, `skipBlankLines`, and `spanFrom(start)`. YAML and Markdown both build on it.
- All three parsers report errors as `TextParseException` with a `Span`. `LineReader.error` (and `LineReader.formatError` for other scanners) renders the message with `line`/`column` and a caret under the source line.
- `ScannerBase` stays unchanged. Cron uses it directly.

### YAML (`wvlet.uni.text.yaml`, in `uni`)

- `YAML.parse(text): JSONValue`, `YAML.parseAll(text): Seq[JSONValue]` (multi-document `---` / `...`), and `YAML.fromYaml[A: Weaver](text): A`.
- Scope: YAML 1.2 core schema. Covers block mappings and sequences, flow `{}`/`[]`, plain, single-, and double-quoted scalars (with escapes), literal `|` and folded `>` blocks with chomping and indentation indicators, comments, anchors and aliases (`&a`/`*a`), and the `<<` merge key. Plain scalars resolve to null, bool, int (including hex and octal), float (including `.inf`/`.nan`), and string, following the core schema.
- Out of scope: custom tags beyond `!!str`/`!!int`/`!!float`/`!!bool`/`!!null` (others parse as their untagged value), complex (non-scalar) keys, and directives other than `%YAML`.
- A recursive-descent parser over `LineReader` that builds `JSONValue` directly (no separate event stage). Aliases share the anchored value, with a cap on alias expansion to block "billion laughs" inputs.
- `Frontmatter.parse(text): Frontmatter(data: Option[JSONValue], body: String)` with `.as[A]` for `---`-delimited headers. Markdown and skill files use it.

### Markdown (`wvlet.uni.text.markdown`, in `uni`)

- `Markdown.parse(text): MarkdownDocument`, which returns an AST of `enum` block and inline nodes. Blocks carry a `Span` of their source lines. Inlines don't, because paragraph text is assembled from lines with container prefixes (`>`, list indentation) removed. `Markdown.toHtml(doc | text, MarkdownConfig)` renders HTML.
- Block phase (`LineReader`): ATX and setext headings, paragraphs, block quotes, ordered and bullet lists (tight/loose), fenced and indented code, thematic breaks, HTML blocks, and link reference definitions.
- Inline phase: code spans, emphasis and strong (CommonMark delimiter-run algorithm), links and images (inline and reference), autolinks, hard and soft breaks, entity and backslash escapes.
- GFM extensions: tables, task-list items, strikethrough, and extended autolinks.
- Safety: `MarkdownConfig.withRawHtml(false)` is the default. Raw HTML is escaped, and `javascript:` URLs are dropped (the job `dompurify`/`rehype-sanitize` does in Treasure Work). `withRawHtml(true)` passes raw HTML through.
- Conformance: vendor the CommonMark and GFM spec example JSON as test resources and assert the categories above. Any skipped examples are listed in the test, so the gap is visible.

### Cron (`wvlet.uni.text.cron`, in `uni`)

- `Cron.parse(expr): Cron`, `cron.next(after: Instant, offset: ZoneOffsetSpec = UTC): Instant`, `cron.nextN(...)`, and `cron.describe: String` (cronstrue-style: "At 09:30, Monday through Friday").
- Syntax: 5-field Unix (`min hour dom month dow`), with an optional leading seconds field (6 fields). Supports `*`, lists, ranges, steps (`*/15`, `1-30/5`), names (`JAN`-`DEC`, `SUN`-`SAT`), `?`, and the macros `@yearly`/`@annually`/`@monthly`/`@weekly`/`@daily`/`@midnight`/`@hourly`. Follows Vixie semantics: when both day-of-month and day-of-week are restricted, either one matches.
- A `CronScanner extends ScannerBase[CronToken]` tokenizes, and a small recursive-descent parser builds the field bit sets.
- Time zones: UTC and fixed offsets work on every platform, using Uni's own civil-date math. Named zones (`Asia/Tokyo`) would need `ZoneId`, which is JVM-only, so `next` accepts only UTC or a fixed offset; callers resolve named zones themselves.

### Delivery

Three PRs in order, each with docs (a new `docs/text/` section: `yaml.md`, `markdown.md`, `cron.md`), skill updates in `plugins/uni/skills/uni/`, and `for-ai-agents.md` entries:

1. `LineReader` + YAML + `Frontmatter`
2. Markdown (reuses `LineReader` and `Frontmatter`)
3. Cron

## Revision (2026-10-28): Lossless Round-Trip for Markdown and YAML

The reviewer asked for round-trip editing, following Wvlet's parser: whitespace, newlines, and comments are kept as attachments on nodes, so a tool can parse a file, change one part, and write it back with everything else byte for byte.

### What Wvlet does

The Wvlet scanner keeps whitespace, newline, and comment tokens. After parsing, the parser attaches each one to the nearest node, as a leading comment (`withComment`) or a same-line trailing comment (`withPostComment`). Nodes also keep their `raw` source text, and `CodeFormatter` prints the attachments back.

### Guarantee

- **Exact round trip:** `Markdown.parse(s).toMarkdown == s` and `YAML.parseDocument(s).toYaml == s` for every input. That includes CRLF, tabs, trailing spaces, a BOM, blank lines, comments, and text with no final newline. A property test checks this over every CommonMark and GFM spec example, every YAML test input, and generated whitespace variants.
- **Local edits:** after a change, every node that wasn't changed prints its original bytes. A changed node prints in a canonical style that reuses the markers it recorded (`*` vs `_`, `-` vs `1.`, the fence characters, the quoting style), so the diff stays inside the edited node.

### Markdown (in #815)

- **Attachments.** Each block keeps a `Source`: its raw text (an exact slice of the input, with container prefixes and original line endings) and `leading` trivia (the blank lines and whitespace between it and the previous sibling). The document keeps the trailing trivia. Unlike Wvlet, the attachments are immutable fields, which fits Uni's `enum` AST. The `span` moves into `Source`.
- **Edit detection without a dirty flag.** `Source` also holds a reference to the content parsed for that node. The writer prints `raw` when the node's content is still the parsed value (an `eq` check, then `==`), and the canonical form otherwise. A `copy(...)` that changes content is therefore treated as an edit automatically.
- **Containers.** When a quote or list child changes, unchanged siblings still print from `raw`. The changed child is rendered and then gets the container's recorded prefix (`> `, the list indent) on each line.
- **Inlines record their syntax.** `Text` keeps its raw form (escapes, entities). `Emphasis` and `Strong` keep the delimiter. `Code` keeps the backtick run. `Link` and `Image` keep their style (inline, reference, or autolink). Breaks keep their raw form (two spaces or a backslash). So editing one word in a paragraph re-renders only that paragraph, in its original style.
- **Frontmatter** becomes a `FrontmatterBlock(raw, data)` that prints its header verbatim. Editing the header data needs the YAML writer from PR 2b.
- **CRLF:** the parser still works on normalized text internally, but spans map back to raw-input offsets, so the slices are exact.
- **API:** `doc.toMarkdown`. The HTML output and spec conformance don't change.

### YAML (new PR 2b, after #815)

- A lossless node tree: `YAMLDocument` with `Mapping`, `Sequence`, `Scalar(value, style: Plain | SingleQuoted | DoubleQuoted | Literal | Folded, raw)`, and `Alias`, plus anchors and tags. Each node gets leading trivia (comments, blank lines, indentation) and a trailing same-line comment, the same two attachments Wvlet uses.
- `YAML.parseDocument(text)`, `.toYaml` (exact), `.toJSON`, and edits that keep comments: `set(path, value)` and `remove(path)`. New values use canonical block style with the surrounding indentation. `YAML.parse(text): JSONValue` and `fromYaml[A]` stay the same, built on the new tree.
- `Frontmatter` round-trips too: `Frontmatter.parse(md).update(data).toText`, and `MarkdownDocument` can edit its header the same way.

### Delivery (revised)

1. #815: Markdown with lossless round trip (this revision), merged after approval.
2. PR 2b: a lossless YAML tree, a writer, and frontmatter editing.
3. Cron. Its expressions are a single line, so `Cron.parse(s).toString` simply returns the source text.

### Alternatives considered

- **A full concrete syntax tree** (every token is a tree node, Roslyn/rowan style): the most general, but every AST user would have to wade through tokens. Attachments keep the simple AST and add only what printing needs, which is how Wvlet does it.
- **Splicing source text by span only** (the current state): exact for untouched text, but it can't write back a changed node in its original style, and the header and inline nodes have no spans.

## Alternatives and Why Not?

- **Run YAML and Markdown through `ScannerBase` too.** This shares the most code, but indentation and line-prefix rules would end up as special-case regions in a token scanner built for expression languages, which makes both parsers harder to follow. `LineReader` keeps the shared parts (spans, errors, buffers) without that cost.
- **Wrap the JS libraries (`yaml`, `marked`, `cronstrue`) as npm facades.** That works on Scala.js only, and JVM and Native apps would still lack parsers. Facades remain a good option for JS-only apps that already ship those libraries.
- **Depend on SnakeYAML or flexmark on the JVM.** That's JVM-only and breaks Uni's minimal-dependency rule. Those libraries remain good choices for JVM apps that need full YAML 1.1 or every Markdown extension.
- **One PR for all three.** Several thousand lines would be hard to review. Splitting also lets YAML land first, since Markdown frontmatter needs it.
