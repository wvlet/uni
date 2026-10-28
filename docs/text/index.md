# Text Formats

`wvlet.uni.text` holds pure-Scala readers for text formats. They work on JVM, Scala.js, and Scala Native with no extra dependencies.

| Package | Description |
|---------|-------------|
| [`wvlet.uni.text.yaml`](./yaml) | YAML 1.2 parser that decodes into case classes with Weaver, plus Markdown frontmatter |

The parsers share `wvlet.uni.text.parser`. `LineReader` is a cursor for line- and indentation-oriented formats like YAML, and `ScannerBase` is a tokenizer for expression-like languages. Errors are thrown as `TextParseException`, which carries the offset of the error. Parsers built on `LineReader` also put the line, the column, and the source line with a caret in the message:

```text
Unclosed flow sequence (line 2, column 4)
b: [1, 2
   ^
```
