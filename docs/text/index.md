# Text Formats

`wvlet.uni.text` holds pure-Scala readers for text formats. They work on JVM, Scala.js, and Scala Native with no extra dependencies.

| Package | Description |
|---------|-------------|
| [`wvlet.uni.text.yaml`](./yaml) | YAML 1.2 parser that decodes into case classes with Weaver, plus Markdown frontmatter |

The parsers share `wvlet.uni.text.parser`. `LineReader` is a cursor for line- and indentation-oriented formats like YAML, and `ScannerBase` is a tokenizer for expression-like languages. Both report errors as `TextParseException`, with the line, the column, and the source line marked with a caret:

```text
Unclosed flow sequence (line 2, column 4)
b: [1, 2
   ^
```
