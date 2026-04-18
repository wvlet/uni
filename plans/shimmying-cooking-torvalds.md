# JSONC Support for uni JSON Parser

## Context

Add JSONC (JSON with Comments) support per [jsonc.org](https://jsonc.org/). JSONC extends JSON with `//` line comments, `/* */` block comments, and trailing commas. Comments are attached as mutable metadata on nearby `JSONValue` nodes for round-tripping. Following the pattern of Jackson and Microsoft's jsonc-parser, comments are enabled directly in the existing `JSON.parse` — no separate entry point.

## Design

### Comment attachment rules

1. **New-line comments** → `leadingComments` on the **next** value node
2. **Inline (same-line) comments** → `trailingComment` on the **previous** value node
3. **End-of-container with no next sibling** → trailing on the last element

### JSONComment

```scala
case class JSONComment(text: String):
  def commentBody: String      // Strip delimiters: "// x" → "x", "/* x */" → "x"
  def isLineComment: Boolean   // starts with //
  def isBlockComment: Boolean  // starts with /*
```

### Mutable comment fields on JSONValue

```scala
sealed trait JSONValue:
  def toJSON: String           // Valid JSON (no comments)
  def toJSONC: String          // JSONC with comments from mutable fields
  var leadingComments: Seq[JSONComment] = Nil
  var trailingComment: Option[JSONComment] = None
```

**JSONNull**: Change from `case object` to `case class JSONNull()`.

### Scanner changes

- Scanner always handles `//` and `/* */` comments (no mode flag)
- Reports comments to handler via callback
- Supports trailing commas in objects and arrays

### API (no new entry point)

- `JSON.parse(s)` → accepts JSONC, preserves comments on nodes
- `v.toJSON` → compact valid JSON (no comments)
- `v.toJSONC` → compact JSONC with comments
- `JSON.format(v)` → pretty-printed JSONC (includes comments when present)

### Files

| File | Change |
|---|---|
| `uni/.../json/JSON.scala` | Add `JSONComment`, mutable comment vars, `JSONNull` → case class, update `format` to output comments |
| `uni/.../json/JSONScanner.scala` | Comment scanning, trailing commas, comment callback |
| `uni/.../json/JSONValueBuilder.scala` | Capture comments, attach to nodes, use `JSONNull()` |
| `uni/.../json/JSONEventHandler.scala` | Add comment callback to handler trait |
| `uni/.../json/JSONTraverser.scala` | Update `case JSONNull` pattern |
| `uni/.../json/package.scala` | Update `JSONNull` usage |
| `uni/.../json/YAMLFormatter.scala` | Use `JSONNull()` |
| `uni/.../weaver/codec/JSONWeaver.scala` | Use `JSONNull()` |
| `uni/src/test/.../json/JSONCTest.scala` (new) | 18 comprehensive tests |

### Design Learnings

1. **Scala `self =>` in anonymous inner classes**: `private` methods resolve to the outer instance, not inner. Use `protected` for methods shared with inner builders.
2. **`markValueLine()` placement**: Moved into scan methods (not `scanString` since it's used for keys and values).
3. **Orphaned comments**: Merge multiple end-of-container comments and merge with existing trailing comments.
