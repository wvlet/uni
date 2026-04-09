# JSON Processing

uni includes a pure Scala JSON parser with a convenient DSL for querying and manipulating JSON data.

## Parsing JSON

```scala
import wvlet.uni.json.JSON

val jsonString = """
{
  "user": {
    "id": 123,
    "name": "Alice",
    "email": "alice@example.com"
  },
  "posts": [
    {"id": 1, "title": "Hello World"},
    {"id": 2, "title": "Scala Tips"}
  ]
}
"""

val json = JSON.parse(jsonString)
```

## Extracting Values

### Direct Access

```scala
// Access nested values
val userId = json("user")("id").toLongValue      // 123
val userName = json("user")("name").toStringValue // "Alice"

// Access array elements
val firstPost = json("posts")(0)("title").toStringValue // "Hello World"
```

### Path Navigation

Use `/` for path-based navigation:

```scala
val email = (json / "user" / "email").toStringValue
// "alice@example.com"

// Navigate into arrays
val titles = (json / "posts" / "title").values
// Seq("Hello World", "Scala Tips")
```

### Safe Access with Option

```scala
val email: Option[String] = for
  user <- json.get("user")
  email <- user.get("email")
yield email.toStringValue
```

## Value Types

JSON values have specific types:

```scala
json match
  case JSONObject(map) => // Object with fields
  case JSONArray(items) => // Array of values
  case JSONString(s) => // String value
  case JSONLong(n) => // Integer value
  case JSONDouble(d) => // Floating point
  case JSONBoolean(b) => // Boolean
  case _: JSONNull => // Null value
```

## Creating JSON

### Programmatic Construction

```scala
import wvlet.uni.json.*

val userJson = JSONObject(Seq(
  "id" -> JSONLong(456),
  "name" -> JSONString("Bob"),
  "active" -> JSONBoolean(true),
  "scores" -> JSONArray(IndexedSeq(
    JSONLong(95),
    JSONLong(87),
    JSONLong(92)
  ))
))
```

### Converting to String

```scala
val jsonStr = userJson.toJSON
// {"id":456,"name":"Bob","active":true,"scores":[95,87,92]}
```

## Working with Arrays

```scala
val posts = json("posts")

// Get all items
val allPosts = posts.values

// Map over items
val titles = posts.values.map(p => p("title").toStringValue)

// Filter items
val filteredPosts = posts.values.filter(p =>
  p("id").toLongValue > 1
)
```

## Error Handling

JSON parsing can fail:

```scala
try
  val json = JSON.parse(invalidJson)
catch
  case e: JSONParseException =>
    println(s"Parse error: ${e.getMessage}")
```

Safe parsing with Try:

```scala
import scala.util.Try

val result = Try(JSON.parse(maybeInvalidJson))
result match
  case scala.util.Success(json) => // Use json
  case scala.util.Failure(e) => // Handle parse failure
```

## JSONC Support

uni's JSON parser supports [JSONC](https://jsonc.org/) (JSON with Comments) out of the box. Line comments (`//`), block comments (`/* */`), and trailing commas are all accepted by `JSON.parse`:

```scala
val config = JSON.parse("""
{
  // Database configuration
  "host": "localhost", // server address
  "port": 5432,
  "options": {
    /* Connection pool settings */
    "minConnections": 5,
    "maxConnections": 20,
  }
}
""")
```

### Comment Round-Tripping

Comments are preserved as mutable metadata on value nodes for round-tripping:

```scala
val v = JSON.parse("""{
  "key": "value" // important
}""")

// Comments are attached to nearby value nodes
val keyVal = v("key")
keyVal.trailingComment // Some(JSONComment("// important"))

// toJSON outputs valid JSON (no comments)
v.toJSON // {"key":"value"}

// JSON.format pretty-prints with comments
JSON.format(v)
// {
//   "key": "value" // important
// }
```

### Comment Attachment Rules

- **New-line comments** are attached as `leadingComments` on the **next** value node
- **Inline comments** (same line) are attached as `trailingComment` on the **previous** value node

### JSONComment

```scala
val comment = JSONComment("// server host")
comment.commentBody    // "server host"
comment.isLineComment  // true
comment.isBlockComment // false
```

## YAML Output

Convert JSON to YAML format:

```scala
import wvlet.uni.json.YAMLFormatter

val yaml = YAMLFormatter.toYAML(json)
// user:
//   id: 123
//   name: Alice
//   email: alice@example.com
```

## Best Practices

1. **Use path navigation** for nested access
2. **Handle missing keys** with `get` or `Option`
3. **Validate structure** before deep traversal
4. **Use pattern matching** for type-safe extraction
