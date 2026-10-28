# Text formats under wvlet.uni.text, and the YAML parser

## Context

Uni could write YAML (`json.YAMLFormatter`) but not read it, and Treasure Work-style apps read YAML everywhere: skill frontmatter, agent configs, and workflow files. Markdown and cron expressions are planned next (`plans/2026-10-27-text-parsers.md`). `wvlet.uni.text.parser.ScannerBase`, ported from Wvlet, is a token scanner for expression languages: it skips whitespace and reads identifiers, numbers, operators, and quoted strings.

## Decisions

Text-format readers live in subpackages of `wvlet.uni.text` (`text.yaml`, then `text.markdown` and `text.cron`), not in new top-level packages. Top-level packages are reserved for core formats such as `json` and `msgpack`. The parser code is in the `uni` module; the shared `text.parser` pieces stay in `uni-core`.

Line- and indentation-oriented formats use `LineReader`, a character cursor with line and column tracking, marks, and `error()` messages that show `line`/`column` and a caret. They do not use `ScannerBase`. YAML and Markdown block structure depends on columns, blank lines, and line prefixes, which a whitespace-skipping token scanner would hide. Expression-like formats (cron) keep using `ScannerBase`. Both throw `TextParseException`, but only `LineReader` formats the message with the line and column.

`LineReader` normalizes `\r\n` and `\r` to `\n` up front, so parsers check only `\n`. Offsets refer to the normalized text, while line and column numbers stay correct.

YAML parses into `JSON.JSONValue` instead of a YAML-specific tree, so `Weaver.fromJSONValue` decodes it into case classes and existing JSON tooling works on it. The parser is a single recursive-descent pass with no event stage: nothing needs YAML events, and the extra layer roughly doubled the code. Each block collection tracks its own column, and a nested node must start at a deeper column than its parent (`parentIndent`, `-1` at the top level). A sequence may sit at its key's column (`compactSequence`).

Plain scalars resolve with the YAML 1.2 core schema, so YAML 1.1 values such as `yes`/`on` stay strings, matching the npm `yaml` package that Treasure Work uses. Mapping keys keep their source text (`1: a` has the key `"1"`). Duplicate keys are an error. Tabs in indentation are an error.

Aliases share the anchored `JSONValue` instead of copying it, so parsing stays linear. Consumers that walk the tree (`toJSON`, Weaver) still expand it, so the parser counts the nodes each anchor expands to and fails once aliases expand past `maxAliasNodes` (default 1,000,000). That stops "billion laughs" inputs.

An anchor or tag on the same line as an implicit key (`&a key: v`) names the key, as the YAML spec says. Properties on their own line name the node below them. Because of this, a block mapping's column is the column before its properties, which `parseBlockNode` passes to `parseBlockContent`.

A leading byte order mark is removed before the `LineReader` is created, so a `---` document marker stays at column 0.

`Frontmatter.parse` passes the header to `YAML.parse` with its opening `---` included, which is a YAML document start. That way, error lines match the original file. It returns the body as a substring of the original text, so line breaks are preserved. It also accepts `---`/`...` lines with trailing spaces.

## Not supported

Explicit keys (`? key`), complex (non-scalar) keys, and `%TAG` handles are not supported. Tags other than `!!str`, `!!int`, `!!float`, `!!bool`, and `!!null` are ignored. Add these only with a real use case: none of the 54 real-world files compared during development used them.
