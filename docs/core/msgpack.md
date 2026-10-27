# MessagePack Serialization

MessagePack is a binary serialization format that's more compact and faster than JSON.

## Basic Usage

### Packing Data

```scala
import wvlet.uni.msgpack.spi.MessagePack

// Create a packer
val packer = MessagePack.newPacker()

// Pack primitive values
packer.packString("Alice")
packer.packInt(30)
packer.packBoolean(true)

// Get the bytes
val bytes = packer.toByteArray
```

### Unpacking Data

```scala
// Create an unpacker
val unpacker = MessagePack.newUnpacker(bytes)

// Unpack in the same order
val name = unpacker.unpackString  // "Alice"
val age = unpacker.unpackInt       // 30
val active = unpacker.unpackBoolean // true
```

## Packing Complex Structures

### Arrays

```scala
val packer = MessagePack.newPacker()

// Pack array header with size
packer.packArrayHeader(3)
packer.packInt(1)
packer.packInt(2)
packer.packInt(3)
```

### Maps

```scala
val packer = MessagePack.newPacker()

// Pack map header with size
packer.packMapHeader(2)
packer.packString("name")
packer.packString("Alice")
packer.packString("age")
packer.packInt(30)
```

## Unpacking Complex Structures

### Arrays

```scala
val unpacker = MessagePack.newUnpacker(bytes)

val size = unpacker.unpackArrayHeader
val items = (0 until size).map(_ => unpacker.unpackInt)
```

### Maps

```scala
val unpacker = MessagePack.newUnpacker(bytes)

val size = unpacker.unpackMapHeader
val map = (0 until size).map { _ =>
  val key = unpacker.unpackString
  val value = unpacker.unpackInt
  key -> value
}.toMap
```

## Supported Types

| Type | Pack Method | Unpack Method |
|------|-------------|---------------|
| Boolean | `packBoolean` | `unpackBoolean` |
| Int | `packInt` | `unpackInt` |
| Long | `packLong` | `unpackLong` |
| Float | `packFloat` | `unpackFloat` |
| Double | `packDouble` | `unpackDouble` |
| String | `packString` | `unpackString` |
| Binary | `packBinaryHeader` + write | `unpackBinaryHeader` + read |
| Nil | `packNil` | `unpackNil` |

## Object Weaving

For automatic serialization of case classes, use Weaver:

```scala
import wvlet.uni.weaver.Weaver

// Using derives clause
case class User(name: String, age: Int) derives Weaver

// Or explicit derivation
case class Address(city: String, country: String)
given Weaver[Address] = Weaver.of[Address]

// Serialize to MessagePack bytes
val userBytes = Weaver.weave(User("Alice", 30))
val addressBytes = Weaver.weave(Address("Tokyo", "Japan"))

// Deserialize from bytes
val user = Weaver.unweave[User](userBytes)
val address = Weaver.unweave[Address](addressBytes)
```

## MessagePack vs JSON

| Aspect | MessagePack | JSON |
|--------|-------------|------|
| Format | Binary | Text |
| Size | Smaller | Larger |
| Speed | Faster | Slower |
| Human-readable | No | Yes |
| Schema | Optional | None |

Use MessagePack when:
- Performance is critical
- Bandwidth is limited
- Human readability isn't needed

Use JSON when:
- Debugging/logging
- API responses
- Configuration files

## Best Practices

1. **Match pack/unpack order** - Data must be unpacked in the same order it was packed
2. **Include length headers** - For arrays and maps
3. **Use Weaver** - For automatic case class serialization
4. **Handle format errors** - Wrap unpacking in try-catch
