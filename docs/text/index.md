# Data & Serialization

Uni reads and writes common data formats in pure Scala. They work on JVM, Scala.js, and Scala Native with no extra dependencies. [Weaver](../core/weaver) turns case classes into JSON, MessagePack, or a `Map` from one `derives Weaver`. JSON and MessagePack are part of the core library, and the text formats are in `wvlet.uni.text`.

| Package | Description |
|---------|-------------|
| [`wvlet.uni.weaver`](../core/weaver) | Codecs derived from case classes, for JSON, MessagePack, and `Map` |
| [`wvlet.uni.msgpack.spi`](../core/msgpack) | Compact binary serialization |
| [`wvlet.uni.json`](../core/json) | JSON parser, `JSONValue` tree, and query DSL. Weaver encodes and decodes case classes to JSON |
| [`wvlet.uni.text.yaml`](./yaml) | YAML 1.2 parser that reads into the same `JSONValue` tree and decodes into case classes with Weaver, plus Markdown frontmatter |
| [`wvlet.uni.text.markdown`](./markdown) | CommonMark + GitHub Flavored Markdown parser and HTML renderer |
| [`wvlet.uni.text.cron`](./cron) | Cron expression parser with next-run times and English descriptions |
| [`wvlet.uni.text.csv`](./csv) | CSV and TSV reader and writer, with rows decoded into case classes by Weaver and streamed from large files |

The `wvlet.uni.text` parsers share `wvlet.uni.text.parser`. `LineReader` is a cursor for line- and indentation-oriented formats like YAML, and `ScannerBase` is a tokenizer for expression-like languages such as cron. Errors are thrown as `TextParseException`, which carries the offset of the error. The YAML, Markdown, and cron parsers also put the line, the column, and the source line with a caret in the message:

```text
Unclosed flow sequence (line 2, column 4)
b: [1, 2
   ^
```
