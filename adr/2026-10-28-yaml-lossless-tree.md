# A lossless YAML tree for editing YAML and frontmatter in place

## Context

`YAML.parse` built `JSONValue`s directly (see `2026-10-27-text-yaml-parser.md`), which drops comments, quoting, and layout. Tools that edit skill frontmatter, `agent.yml`, or workflow files need to change one value and write the file back with everything else as it was. Markdown already does this with `Source` attachments (`2026-10-27-text-markdown-parser.md`), and its frontmatter edits were waiting for a YAML writer.

## Decisions

The parser now builds a `YAMLNode` tree (`Scalar` with its text and `ScalarStyle`, `Mapping` of `YAMLEntry`, `Sequence`, `Alias`, plus anchors and tags), and `YAMLResolver` turns it into `JSONValue`. `YAML.parse`, `parseAll`, and `fromYaml` keep their results and error messages; they parse the tree and resolve it. The scanning code is unchanged. Checks that need values (duplicate keys, unknown aliases, merge-key values, tagged scalar values, the `maxAliasNodes` cap) moved to the resolver and report the same line and column, from the node's offset. `YAML.parseDocument` resolves once as well, so it rejects the same inputs as `parse`.

The parser first builds internal `PNode`s with offsets into the normalized text; when a document is complete, `build` slices each node's source from the original input (mapping offsets back through `\r\n` and the BOM, as `BlockParser.rawOffset` does). A two-step build keeps offset bookkeeping out of the public tree, and lets the trivia between siblings be assigned once every child's extent is known.

Each node has a `YAMLSource` attachment, like Markdown's `Source` and Wvlet's `withComment`/`withPostComment`: `raw` (the node with its anchor and tag), `leading` (the text since the previous token: line breaks, indentation, and comment lines before a key; `: ` before a value; `- ` before an item), `comment` (a same-line comment after a scalar, alias, or flow collection), `opening` (a block collection's text before its first child, such as `&a\n  `), and `indent`. Leading comments belong to the key below them, so removing an entry removes its comments. The `: ` separator belongs to the value, so a replaced scalar keeps the spacing and the comment after it. The document keeps the text before and after the root, so the pieces always add up to the input.

Edits are detected as in Markdown: `YAMLSource` does not take part in equality, and it records the node's content as parsed. The writer prints `raw` while the content is the same (`eq`, then `==`), and the canonical style otherwise. A changed collection still prints its unchanged children from source, and an entry moved to the front loses only the line break before it. Canonical output is block style with two-space indentation, or flow style inside flow collections. Scalars keep their recorded style when it can hold the value; plain text is checked conservatively and falls back to double quotes, and multi-line strings become literal blocks.

`set`, `remove`, and `update` live on `YAMLDocument` and take paths of `String` keys and `Int` indexes. `update` diffs the resolved JSON against the new value and edits only differing entries, which is what `Frontmatter` and `MarkdownDocument` need: they hold `JSONValue`s, not trees. A replaced value keeps its anchor so aliases elsewhere still resolve. Because editing an anchored node also changes its aliases, `update` resolves its result and, when it differs from the requested value, edits again with the aliases in changed regions replaced by their values. Mappings with `<<` merge keys are replaced as a whole by `update`, because their resolved entries do not map one-to-one to source entries.

`Frontmatter` gets a `FrontmatterSource` attachment (the header `YAMLDocument`, the closing line, and the data as parsed). `toText` prints the header as read while `data` is unchanged, and patches it with `update` otherwise. `MarkdownDocument`'s `Source` records the frontmatter too, so `toMarkdown` rewrites only the header when `frontmatter` changes. The Markdown parser reads the header from the original input, not the normalized text, so a CRLF header writes back exactly.

`YAMLTest` runs every successful input through a round-trip check: `\n`, `\r\n`, no final line break, and a BOM must write back exactly, and a copy with every source removed must read back to the same value.

## Not supported

Comments inside a changed flow collection are dropped when it is rewritten, and a new value inside a flow collection is always flow style. Keep-chomping (`|+`) block scalars at the very end of a document without a final line break may lose a trailing line break when rewritten. Multi-document streams have no `YAMLDocument` API (`parseDocument` rejects them, like `parse`).
