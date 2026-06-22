# 6. Data In, Data Out — JSON & MessagePack

Every service eats and produces data. A request arrives as a JSON body;
a cache stores a MessagePack frame; a config file is a blob of text. This
chapter is about the boundary where bytes become Scala values and back —
and how Uni crosses it without per-type boilerplate.

There are two situations, and Uni has a tool for each:

- You **don't** know (or control) the shape — a third-party response, a
  config blob. Parse it into a JSON tree and navigate.
- You **do** own the shape — it's your case class. Derive a codec once
  and round-trip it to JSON *or* MessagePack.

## When you don't own the shape: parse and navigate

`JSON.parse` turns text into a navigable tree:

```scala
import wvlet.uni.json.*

val doc = JSON.parse("""
  { "user": { "id": 123, "name": "Alice" },
    "posts": [ { "title": "Hello" }, { "title": "World" } ] }
""")

val name  = doc("user")("name").toStringValue   // "Alice"
val id     = doc("user")("id").toLongValue        // 123
val first = doc("posts")(0)("title").toStringValue // "Hello"
```

You index into objects with `(key)` and arrays with `(index)`, then read
a leaf with a typed accessor like `toStringValue` or `toLongValue`. When
a path might be missing, navigate with `/` and pattern-match or use
`Option` instead of asserting it exists. The
[JSON reference](/core/json) covers the full DSL, including building and
mutating trees.

This is the right tool for *loosely* structured data. But when the shape
is yours, threading `("user")("name")` through your code throws away the
types you already have.

## When you own the shape: derive a codec

Define the data as case classes and add `derives Weaver`:

```scala
import wvlet.uni.weaver.Weaver

case class User(id: Long, name: String) derives Weaver

val alice = User(123, "Alice")

val json = Weaver.toJson(alice)        // {"id":123,"name":"Alice"}
val back = Weaver.fromJson[User](json) // User(123, Alice)
```

```bash
$ sbt run
{"id":123,"name":"Alice"}
User(123,Alice)
```

`derives Weaver` asks the compiler to build a serializer for `User` from
its structure. No annotations on each field, no hand-written
`toJson`/`fromJson`, no companion-object ceremony. Nested case classes,
collections, `Option`, enums, and sealed traits all derive the same way —
see the [Weaver reference](/core/weaver) for the full type list.

## The same type, as binary

Here is the part that pays off: the *same* `Weaver[User]` also speaks
MessagePack, a compact binary encoding. You don't define a second codec —
you call a different method:

```scala
// Continues the User from above.
val bytes: Array[Byte] = Weaver.weave(alice)     // MessagePack
val user                = Weaver.unweave[User](bytes)
```

Reach for MessagePack when the bytes matter: a cache entry, a queue
message, an internal RPC payload. It is smaller and faster to parse than
JSON, and it is binary-safe. Reach for JSON when a human or a browser is
on the other end. Either way, your *type* is the same; only the format at
the edge changes.

## Why one codec for two formats

It would be easy to ship a JSON library and a separate MessagePack
library, each with its own derivation. Uni deliberately does not. The
insight is that **the wire format is a boundary decision, not a modeling
decision.** `User` is `User` whether it travels as text or as bytes — so
you describe it once, and choose the encoding where the data actually
leaves your program.

That keeps the choice cheap. Storing sessions in Redis and decide
MessagePack is leaner? Change `toJson` to `weave` at that one boundary;
`User` does not move. A reader does not learn two derivation systems, and
the two formats cannot drift apart, because they are the same `Weaver`.

::: tip Cross-platform, by construction
`derives Weaver` resolves at **compile time** — no runtime reflection.
That is what lets the identical codec run on the JVM, in the browser
(Scala.js), and as a native binary. A reflection-based serializer would
not survive the trip to Scala.js or Native; a derived one does. We return
to this theme in [Chapter 11](./ch11-00-cross-platform).
:::

## What you have, what comes next

You can now move data across your program's edges:

- **`JSON.parse`** for data whose shape you don't own — navigate a tree
  with `(key)` / `(index)` and typed accessors.
- **`derives Weaver`** for data you do own — one derivation, no
  per-field boilerplate.
- **`Weaver.toJson` / `fromJson`** for text, **`Weaver.weave` / `unweave`**
  for MessagePack — same codec, format chosen at the boundary.
- Derivation is compile-time, so the codec is cross-platform.

That closes Part III: you can wire a program ([Design](./ch03-00-design)),
see what it does ([Logging](./ch05-00-logging)), and move data through it.
Next, [Part IV](./ch07-00-rx) makes those services *react* — `Rx`, Uni's
composable stream, for values that change over time.

[← 5. Logging That Finds You](./ch05-00-logging) | [Next → 7. Rx, the Composable Stream](./ch07-00-rx)
