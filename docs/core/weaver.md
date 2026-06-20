# Weaver

`Weaver[A]` is uni's derivation-based serialization framework. A single `Weaver`
instance can read and write a value of type `A` as **MessagePack**, **JSON**, or
a loosely-typed `Map[String, Any]` — so you derive the codec once and pick the
wire format at the call site.

Weaver is the engine behind JSON/MessagePack serialization in the
[HTTP client](../http/client) and [RPC](../http/rpc) layers, and it builds on
[Surface](./surface) for compile-time type information.

```scala
import wvlet.uni.weaver.Weaver

case class Person(name: String, age: Int) derives Weaver

val json = Weaver.toJson(Person("Alice", 30))   // {"name":"Alice","age":30}
val back = Weaver.fromJson[Person](json)         // Person(Alice,30)
```

## Deriving a Weaver

A `Weaver[A]` is derived at compile time for case classes, enums, and sealed
traits. There are three equivalent ways to obtain one:

```scala
// 1. derives clause (recommended for types you own)
case class Person(name: String, age: Int) derives Weaver

// 2. Weaver.of for an explicit given
given Weaver[Person] = Weaver.of[Person]

// 3. Inline at the call site
val w: Weaver[Person] = Weaver.of[Person]
```

Most API methods take the `Weaver[A]` as a `using` parameter, so a `derives`
clause or a `given` in scope is all you need.

## Serialization Formats

The same derived `Weaver` exposes every format. The companion-object methods
resolve the `Weaver[A]` implicitly:

```scala
import wvlet.uni.weaver.Weaver

case class User(name: String, age: Int) derives Weaver
val user = User("Alice", 30)

// JSON
val json: String = Weaver.toJson(user)
val u1: User     = Weaver.fromJson[User](json)

// MessagePack (compact binary)
val bytes = Weaver.weave(user)            // Array[Byte]
val u2    = Weaver.unweave[User](bytes)

// Map[String, Any] — handy with YAML/HOCON-style parsers
val map: Map[String, Any] = Weaver.toMap(user)
val u3                     = Weaver.fromMap[User](map)
```

The instance methods on `Weaver[A]` mirror these and add a couple of
lower-overhead entry points when you already hold a `Weaver`:

| Method | Direction | Format |
|--------|-----------|--------|
| `toJson(v)` / `fromJson(s)` | encode / decode | JSON string |
| `weave(v)` / `unweave(b)` | encode / decode | MessagePack `Array[Byte]` |
| `toMap(v)` / `fromMap(m)` | encode / decode | `Map[String, Any]` |
| `toMsgPack(v)` | encode | MessagePack `Array[Byte]` |
| `fromJSONValue(v)` | decode | parsed [`JSONValue`](./json) (no string roundtrip) |
| `pack(p, v, c)` / `unpack(u, c)` | encode / decode | low-level [MessagePack](./msgpack) packer/unpacker |

## Supported Types

Weaver resolves codecs for these out of the box (cross-platform):

- **Primitives**: `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`, `Boolean`,
  `Char`, `String`, `BigInt`, `BigDecimal`
- **Common value types**: `UUID`, `java.time.Instant`,
  [`ULID`](./utilities#ulid), [`ElapsedTime`](./utilities#elapsedtime),
  `scala.concurrent.duration.Duration`, `java.net.URI`
- **Collections**: `Option`, `Seq`/`List`/`Vector`/`IndexedSeq`, `Set`, `Map`,
  `ListMap`, `Array`, `Either`, tuples
- **Java collections**: `java.util.List`, `java.util.Set`, `java.util.Map`
- **Case classes** with any combination of the above
- **Enums and sealed traits** (see [ADTs](#enums-and-sealed-traits))

### JVM-only types

On the JVM, additional `given` weavers are available by importing them
explicitly (they live in `JvmWeaver` because they depend on JDK types not
available on Scala.js / Native):

```scala
import wvlet.uni.weaver.codec.JvmWeaver.given
// adds: ZonedDateTime, OffsetDateTime, LocalDate, LocalDateTime,
//       java.time.Duration, java.io.File, java.net.URL,
//       java.nio.file.Path, java.util.Optional[A]
```

## Enums and Sealed Traits

Scala 3 enums encode as their **case name** string:

```scala
enum Color derives Weaver:
  case Red, Green, Blue

Weaver.toJson(Color.Green)            // "Green"
Weaver.fromJson[Color](""""Blue"""")  // Color.Blue
```

Sealed traits (ADTs) encode as a **flat object with a discriminator field**
added alongside the case's own fields. The default discriminator is `@type`:

```scala
sealed trait Shape derives Weaver
case class Circle(radius: Double)            extends Shape
case class Rectangle(w: Double, h: Double)   extends Shape
case object Unknown                          extends Shape

Weaver.toJson(Circle(1.0))   // {"@type":"Circle","radius":1.0}
Weaver.toJson(Unknown)       // {"@type":"Unknown"}
```

Change the discriminator via [`WeaverConfig`](#configuration):

```scala
import wvlet.uni.weaver.WeaverConfig

val cfg = WeaverConfig().withDiscriminatorFieldName("kind")
Weaver.toJson(Circle(1.0), cfg)   // {"kind":"Circle","radius":1.0}
```

## Configuration

`WeaverConfig` is an optional last argument on every method (it defaults to
`WeaverConfig()`):

| Field | Default | Effect |
|-------|---------|--------|
| `discriminatorFieldName` | `"@type"` | Field name used to tag sealed-trait cases |

```scala
import wvlet.uni.weaver.WeaverConfig

val config = WeaverConfig().withDiscriminatorFieldName("type")
val json   = Weaver.toJson(shape, config)
```

## Runtime Weavers from Surface

When you only have a [`Surface`](./surface) — for example deriving a codec for a
method's parameter types in the RPC layer — build a `Weaver` at runtime instead
of compile time:

```scala
import wvlet.uni.weaver.Weaver
import wvlet.uni.surface.Surface

val surface = Surface.of[Person]
val weaver  = Weaver.fromSurface(surface)   // Weaver[?]
```

`fromSurface` caches results, supports self-recursive types (e.g.
`class Tree(children: List[Tree])`), and is what the RPC framework uses to wire
up codecs without compile-time type information.

For types that aren't directly supported, `fromSurface` falls back to a lossy
empty-object weaver. Use `Weaver.fromSurfaceOpt(surface)`, which returns `None`
when the resulting weaver tree contains that fallback at any position, if you
want to detect the gap and choose a different encoding. See the
[ADR](https://github.com/wvlet/uni/blob/main/adr/2026-05-04-fromsurfaceopt-and-innerweavers.md)
for the rationale.

## Custom Weavers

Provide a `given Weaver[A]` to control encoding for a type Weaver can't derive
(or shouldn't derive losslessly — e.g. an open abstract type). Implement `pack`
and `unpack` against the low-level [MessagePack](./msgpack) packer/unpacker:

```scala
import wvlet.uni.weaver.{Weaver, WeaverConfig, WeaverContext}
import wvlet.uni.msgpack.spi.{Packer, Unpacker}

opaque type Email = String

given Weaver[Email] with
  def pack(p: Packer, v: Email, config: WeaverConfig): Unit =
    p.packString(v)
  def unpack(u: Unpacker, context: WeaverContext): Unit =
    context.setObject(u.unpackString)
```

Once in scope, the custom weaver participates in derivation: any case class with
an `Email` field will use it automatically.

## Cross-Platform Support

Weaver works on JVM, Scala.js, and Scala Native. The core type set is identical
on all three; only the `JvmWeaver` extras above are JVM-specific.
