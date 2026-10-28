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

## Writing YAML

To write YAML, use `YAMLFormatter.toYaml` from `wvlet.uni.json`. It converts a JSON string to YAML:

```scala
import wvlet.uni.json.YAMLFormatter

YAMLFormatter.toYaml("""{"name":"api","port":8080}""")
// name: api
// port: 8080
```
