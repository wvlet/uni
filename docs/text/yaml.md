# YAML

`wvlet.uni.text.yaml` reads YAML 1.2 into the same `JSONValue` tree that [JSON processing](../core/json) uses, so [Weaver](../core/weaver) can decode it into case classes. It works on JVM, Scala.js, and Scala Native.

## Decoding into Case Classes

```scala
import wvlet.uni.text.yaml.YAML
import wvlet.uni.weaver.Weaver

case class ServerConfig(name: String, port: Int = 8080, tags: Seq[String] = Nil) derives Weaver

val config = YAML.fromYaml[ServerConfig]("""
name: api
tags: [public, v2]
""")
// ServerConfig(api, 8080, List(public, v2))
```

Missing fields take their case-class defaults.

## Parsing to JSONValue

```scala
import wvlet.uni.json.JSON.JSONValue

val v: JSONValue = YAML.parse("""
server:
  host: localhost
  ports: [80, 443]
""")
v.toJSON // {"server":{"host":"localhost","ports":[80,443]}}
```

`YAML.parse` reads a single document, and returns `JSONNull` for an empty one. For a `---`-separated stream, use `YAML.parseAll`:

```scala
val docs: Seq[JSONValue] = YAML.parseAll("a: 1\n---\nb: 2")
```

## Frontmatter

`Frontmatter.parse` splits a YAML header delimited by `---` lines from the rest of a text, as used in Markdown files and agent skills:

```scala
import wvlet.uni.text.yaml.Frontmatter

case class SkillHeader(name: String, description: String = "") derives Weaver

val f = Frontmatter.parse("""---
name: review
description: Review a pull request
---
# Instructions
""")
f.as[SkillHeader] // SkillHeader(review, Review a pull request)
f.body            // "# Instructions\n"
```

When the text has no frontmatter, `data` is `None` and `body` is the whole text.

To change the header, pass the new value to `update` (or `copy(data = ...)`) and write the text back with `toText`. Only the parts of the header that changed are rewritten, so its comments and formatting stay, and the body is unchanged:

```scala
import wvlet.uni.json.JSON

val text = """---
# Owned by the docs team
name: review  # the skill name
---
# Instructions
"""
Frontmatter.parse(text)
  .update(JSON.parse("""{"name":"review-pr","tags":["git"]}"""))
  .toText
// ---
// # Owned by the docs team
// name: review-pr  # the skill name
// tags:
//   - git
// ---
// # Instructions
```

A text without frontmatter gets a new `---` header, and `copy(data = None)` removes it. [`Markdown.parse`](./markdown#frontmatter) reads the header the same way, and `toMarkdown` writes changes to `frontmatter` back.

## Supported YAML

- Block mappings and sequences, including a sequence at its key's column
- Flow collections: `[a, b]`, `{a: 1}`, and single-pair entries like `[a: 1]`
- Plain, single-quoted, and double-quoted scalars (all YAML escapes), with multi-line folding
- Literal (`|`) and folded (`>`) block scalars, with chomping (`-`, `+`) and indentation indicators
- Comments, multiple documents (`---`, `...`), and `%YAML` directives
- Anchors (`&name`), aliases (`*name`), and merge keys (`<<: *base`)
- Tags `!!str`, `!!int`, `!!float`, `!!bool`, and `!!null`. Other tags are ignored, and the value resolves as if untagged

Plain scalars resolve with the YAML 1.2 core schema:

| YAML | Value |
|------|-------|
| `~`, `null`, empty | `JSONNull` |
| `true`, `False` | `JSONBoolean` |
| `42`, `0x1F`, `0o17` | `JSONLong` |
| `1.5`, `1e3`, `.inf`, `.nan` | `JSONDouble` |
| `yes`, `on`, anything else | `JSONString` |

YAML 1.1 booleans like `yes` and `on` stay strings, as YAML 1.2 specifies.

Not supported:

- Explicit keys (`? key`) and complex (non-scalar) mapping keys
- `%TAG` handles. Tags are ignored, apart from the ones listed above

Duplicate keys are an error. Mapping keys keep their source text, so `1: a` has the key `"1"`.

## Safety

Aliases share the anchored value instead of copying it. To stop "billion laughs" inputs, where nested aliases expand exponentially, parsing fails once aliases expand to more than `YAML.defaultMaxAliasNodes` (1,000,000) nodes. To change the limit, pass `maxAliasNodes`:

```scala
YAML.parseAll(untrustedYaml, maxAliasNodes = 10_000)
```

## Editing YAML

`YAML.parseDocument` reads a document into a `YAMLDocument` that keeps its source text. `toYaml` writes it back: without changes, the output is the input byte for byte, including comments, blank lines, indentation, `\r\n` line breaks, and quoting. Edit it by path, and only the changed values are rewritten:

```scala
val doc = YAML.parseDocument("""# Server settings
server:
  host: localhost
  port: 8080  # default port

  # Set in production
  tls: false
""")

doc
  .set(Seq("server", "port"), 9090)            // keeps "# default port"
  .set(Seq("server", "tags"), Seq("a", "b"))   // adds an entry at the end of server
  .remove(Seq("server", "tls"))                // removes tls and the comment above it
  .toYaml
// # Server settings
// server:
//   host: localhost
//   port: 9090  # default port
//   tags:
//     - a
//     - b
```

- A path is a list of mapping keys (`String`) and sequence indexes (`Int`). `set` adds missing keys, and missing parent mappings, at the end of their mapping; the index equal to a sequence's size appends to it. `remove` ignores a missing path.
- `set` takes a `JSONValue` or any value with a `Weaver`.
- `update(value)` changes the document to resolve to a new `JSONValue`, editing only the entries that differ.
- `toJSON` resolves the document, as `YAML.parse` does.

A replaced scalar keeps its quoting style (`'x'`, `"x"`, `|`) and the comment after it. An edited flow collection (`[a, b]`, `{k: v}`) keeps its comments and line breaks, and a new item follows the layout of the items before it. New values are written in block style with two-space indentation, or in flow style inside a flow collection. A new string is quoted when it would otherwise read as another type, so `"123"` stays a string. An anchor on a replaced value stays, so aliases to it still resolve.

### Multiple documents

`YAML.parseDocument` reads a single document. For a `---`-separated stream, such as Kubernetes manifests, `YAML.parseDocuments` returns one `YAMLDocument` per document. Edit any of them, and `YAML.writeStream` writes them all back. The documents you didn't change are written byte for byte:

```scala
val docs = YAML.parseDocuments("""# Deployment
kind: Deployment
replicas: 1
---
# Service
kind: Service
""")

YAML.writeStream(docs.updated(0, docs(0).set(Seq("replicas"), 3)))
// # Deployment
// kind: Deployment
// replicas: 3
// ---
// # Service
// kind: Service
```

Each document holds its part of the stream. A comment on its own line before a `---` marker belongs to the document below it, like a comment above a key. A comment after a document's content on the same line, and a `...` end marker, belong to the document they end. So removing a document from the `Seq` also removes its comments. `writeStream` adds a `---` before a document that doesn't start with one, unless the previous document ends with `...`. A stream with no documents gives one empty document that keeps its text, such as comments.

The tree is a `YAMLNode` (`Scalar`, `Mapping`, `Sequence`, `Alias`), and you can also change it with `copy`. As with the [Markdown AST](./markdown), each node's source text is an attachment that does not take part in equality, and a node whose content differs from what was parsed is written in the canonical style.

## Writing YAML

To edit an existing YAML text, use `YAMLDocument` (above). To write a value as YAML, use `YAMLFormatter.toYaml` from `wvlet.uni.json`. It converts a JSON string to YAML:

```scala
import wvlet.uni.json.YAMLFormatter

YAMLFormatter.toYaml("""{"name":"api","port":8080}""")
// name: api
// port: 8080
```
