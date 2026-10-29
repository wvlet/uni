# YAML follow-ups: comments in flow collections, and editing multi-document streams

## Goals

- Close the two known gaps in `adr/2026-10-28-yaml-lossless-tree.md` ("Not supported"), so tools that edit YAML in place lose nothing they didn't touch:
  1. An edit inside a flow collection (`[a, b]`, `{k: v}`) drops the comments and line breaks in that collection, and a multi-line flow collection collapses to one line.
  2. Multi-document (`---`-separated) files such as Kubernetes manifests can't be edited: `YAML.parseDocument` rejects them, like `YAML.parse`.

## Background

- The writer already keeps text around changed block children: each key or item has a `leading` attachment (comments, line breaks, indentation), and a changed block collection still prints its unchanged children from source.
- Flow children are parsed the same way. Their `leading` already holds everything since the previous sibling: `, `, line breaks, and comments (the flow scanner skips comments, so they land in the next child's leading). But `YAMLWriter.inlineText` ignores it and joins the children with `", "`. The text between the last child and `]`/`}` isn't recorded anywhere outside `raw`.
- `YAMLParser.parseRoots` already parses every document of a stream. `parseStream` builds the roots without the trivia around them, and `parseDocument` builds one `YAMLDocument` whose `source.raw` is the whole input.
- `YAMLResolver.resolve(doc)` re-normalizes `doc.source.raw` and uses node offsets into that text for error messages, so offsets must match whatever text a document's `raw` holds.
- Users: `Frontmatter` and `MarkdownDocument` (single documents, unaffected), plus anyone editing `agent.yml`, workflow files, or manifests.

## Design

Lane: **Standard**. It adds public APIs (`YAML.parseDocuments` and `YAML.writeStream`) and changes writer output for edited flow collections.

### 1. Flow collections keep their text

- Record the closing trivia of a flow collection (the text after its last child, up to `]` or `}`) as a new `private[yaml]` field on `YAMLSource`. It's computed in `YAMLParser.build`, like `opening`.
- When a flow collection with source changes, the writer prints `[`/`{`, then each child with its recorded leading, then the closing trivia and the bracket. That keeps comments, line breaks, and indentation. Unchanged children still print `raw`. A changed child prints canonically after its own leading, as block children do.
- Separators follow the block rules:
  - A parsed child that was first and is now later gets `,` plus the layout of the other separators.
  - A child moved to the front loses the text up to and including its comma, but keeps its comments.
  - Removing a child removes its leading text, so the comments above it go with it, as with block keys.
  - New children copy the separator layout of the collection's last parsed non-first child, without comments. If there is none, they use `, `. So a new item in a one-per-line flow list goes on its own line.
- Single-pair mappings inside a flow sequence (`[a: 1]`) have no braces, so when changed they keep the current canonical output.
- New collections built from JSON are unchanged: flow style inside flow, block style elsewhere.

### 2. Multi-document streams: `YAML.parseDocuments` and `YAML.writeStream`

```scala
val docs = YAML.parseDocuments(text)           // Seq[YAMLDocument]
YAML.writeStream(docs.updated(1, docs(1).set(Seq("spec", "replicas"), 3)))
```

- `YAML.parseDocuments(yaml, maxAliasNodes)` checks every document as `parseAll` does. `YAML.writeStream(docs)` concatenates the documents' text. There's no new type, so callers edit the `Seq` with ordinary collection methods.
- **Each document owns a slice of the stream**, and the slices add up to the input, so `writeStream(parseDocuments(s)) == s` holds byte for byte.
- **Comments follow Wvlet's attachment pattern.** A comment on its own line belongs to the node after it, and a same-line comment belongs to the node before it. So a slice ends at the line break that ends the document's last token: its root with any same-line comment, its `...` marker, or the `---` of an empty document. Comment lines and blank lines before the next `---` start the next document.
- Each `YAMLDocument` is an ordinary document over its slice: its `leading` includes the `---`. `set`, `remove`, `update`, and `toYaml` work on it unchanged.
- Node offsets in a stream document are relative to its slice, so `doc.toJSON` reports errors at the right place within the document. At parse time, errors report the line in the whole stream, as `parseAll` does.
- `writeStream` adds a line break after a document that doesn't end with one. It adds `---` before a document whose leading text has no marker, unless the previous document ends with `...`. So new or moved documents stay separate.
- A stream with no documents (an empty input, or only comments) gives one empty document that keeps the text, as `parseDocument` does.
- `YAML.parseDocument` still rejects multiple documents, and its error now points to `YAML.parseDocuments`.

### 1b. Flow children follow the same pattern

- The comment after a flow child's comma, on the same line, becomes that child's `comment`. So removing or moving a child takes its comment with it, rather than leaving the comment for the next child.

### Tests and docs

- Exact round-trip tests: the `YAMLTest` round-trip check (`\n`, `\r\n`, no final line break, BOM, sources removed) also runs through `parseDocuments` and `writeStream`, and also marks every collection as changed.
- Flow edit cases: comments after and before commas, multi-line layouts, moves, removes, and appends.
- Stream edits: edit one document, add, remove, and an empty or comment-only stream.
- Update `docs/text/yaml.md`, `docs/guide/for-ai-agents.md`, and `plugins/uni/skills/uni/` (with `docSnippets`), and bump `plugin.json` to 0.3.16. Update the ADR's "Not supported" section, plus a paragraph on streams and an `adr/README.md` entry if needed.

## Alternatives and Why Not?

- **A `YAMLStream` type.** It would keep the byte-for-byte guarantee in one place, but `writeStream` does the same with a plain `Seq`, which callers already know how to edit.
- **Let `parseDocument` accept streams, with `YAMLDocument` holding several roots.** That would break every caller that assumes one `root`, including `Frontmatter`.
- **Give the comments between documents to the earlier document.** That differs from how comments attach to keys. Following Wvlet's pattern, own-line comments lead the node after them.
- **Flow edits by splicing source text by span.** That's exact for untouched text, but it can't place separators for moved or new children. The leading attachments already hold the text we need.
