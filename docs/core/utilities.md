# Utilities

Human-readable value types and ID generators for common programming tasks.

## UUIDv7

Time-ordered UUIDs (RFC 9562) — the recommended ID format for internal use. UUIDv7 embeds a millisecond-precision Unix timestamp, is monotonically sortable, and can be compactly encoded with Base62.

```scala
import wvlet.uni.util.UUIDv7

// Generate a new UUIDv7
val id = UUIDv7.newUUIDv7()
println(id)  // e.g., "019078e5-3f6a-7b1c-8d4e-2f9a0b1c3d5e"

// Compact 22-character Base62 representation (URL-safe, sort-order preserving)
val short: String = id.toBase62  // e.g., "0K9mFb3xYz1Qw5Rv8NpJ2a"

// Parse back from Base62
val recovered = UUIDv7.fromBase62(short)
```

### Timestamps and Conversion

```scala
val id = UUIDv7.newUUIDv7()

// Extract timestamp
val millis: Long = id.timestamp

// Convert to/from java.util.UUID
val uuid: java.util.UUID = id.toUUID
val back: UUIDv7 = UUIDv7.fromUUID(uuid)

// Binary representation
val bytes: Array[Byte] = UUIDv7.toBytes(id)   // 16 bytes
val fromBytes: UUIDv7 = UUIDv7.fromBytes(bytes)

// Parse from standard UUID string
val parsed = UUIDv7.fromString("019078e5-3f6a-7b1c-8d4e-2f9a0b1c3d5e")
```

### Custom Generator

```scala
// Create an independent generator (useful for testing or isolated contexts)
val gen = UUIDv7.createGenerator()
val id = gen.newUUIDv7()

// With a custom random source
val gen2 = UUIDv7.createGenerator(new scala.util.Random(42))
```

## Base62

Sort-order preserving encoding using the `0-9A-Za-z` alphabet (62 characters). Encodes 128-bit values to compact 22-character URL-safe strings. Use `UUIDv7.toBase62` / `UUIDv7.fromBase62` for the common case.

```scala
import wvlet.uni.util.Base62

// Encode / decode 128-bit values directly
val encoded: String = Base62.encode128bits(hi, low)        // 22 chars
val (hi, low): (Long, Long) = Base62.decode128bits(encoded)

// Validate
Base62.isValid("0K9mFb3xYz1Qw5Rv8NpJ2a")  // true
```

## NanoId

Compact, URL-safe random IDs for cases where time-ordering is not needed. Useful as user-facing tokens, short links, or external keys that map back to an internal UUIDv7.

```scala
import wvlet.uni.util.NanoId

// Generate a default NanoId (21 chars, A-Za-z0-9_-)
val id: String = NanoId.generate()  // e.g., "V1StGXR8_Z5jdHi6B-myT"

// Custom size
val short = NanoId.generate(10)

// Custom alphabet and size
val hex = NanoId.generate("0123456789abcdef", 32)
```

::: tip ID Strategy
Use **UUIDv7** as the internal primary key — it is time-ordered, monotonic, and embeds a timestamp. For user-facing URLs and APIs, either:
- Expose the UUIDv7 in **Base62** format (22 chars, sortable, reversible), or
- Generate a **NanoId** as an external key and maintain a mapping to the internal UUIDv7.
:::

## ULID

Universally Unique Lexicographically Sortable Identifiers — 128-bit IDs encoded as 26-character Crockford Base32 strings. ULID predates UUIDv7 and serves a similar purpose; **prefer UUIDv7 for new projects**.

```scala
import wvlet.uni.util.ULID

// Generate a new ULID
val id = ULID.newULID
println(id)  // e.g., "01arz3ndektsv4rrffq69g5fav"
```

<details>
<summary>ULID API details</summary>

### Parsing and Validation

```scala
val parsed = ULID.fromString("01arz3ndektsv4rrffq69g5fav")
ULID.isValid("01arz3ndektsv4rrffq69g5fav")  // true
```

### Extracting Timestamps

```scala
val id = ULID.newULID
val millis: Long = id.epochMillis
val instant: java.time.Instant = id.toInstant
val historical = ULID.ofMillis(1609459200000L)
```

### Converting to/from UUID

```scala
import java.util.UUID

val ulid = ULID.newULID
val uuid: UUID = ulid.toUUID
val back: ULID = ULID.fromUUID(uuid)
val bytes: Array[Byte] = ulid.toBytes  // 16 bytes
val fromBytes: ULID = ULID.fromBytes(bytes)
```

</details>

## DataSize

Human-readable data size representation with parsing and unit conversion.

```scala
import wvlet.uni.util.DataSize

// Parse from string
val size = DataSize("128MB")

// Create from bytes
val fromBytes = DataSize(1073741824L)  // 1GB

// Auto-select best unit
val succinct = DataSize.succinct(1536000L)
println(succinct)  // "1.46MB"
```

### Unit Conversion

```scala
val size = DataSize("2.5GB")

size.toBytes                              // 2684354560L
size.valueOf(DataSizeUnit.MEGABYTE)       // 2560.0
size.convertTo(DataSizeUnit.MEGABYTE)     // DataSize(2560.0, MEGABYTE)
size.mostSuccinctDataSize                 // DataSize(2.5, GIGABYTE)
```

### Units

| Unit | Symbol | Bytes |
|------|--------|-------|
| `BYTE` | B | 1 |
| `KILOBYTE` | kB | 1,024 |
| `MEGABYTE` | MB | 1,048,576 |
| `GIGABYTE` | GB | 1,073,741,824 |
| `TERABYTE` | TB | 1,099,511,627,776 |
| `PETABYTE` | PB | 1,125,899,906,842,624 |

## Count

Human-readable large number representation.

```scala
import wvlet.uni.util.Count

// Parse from string
val count = Count("1.5M")

// Auto-select best unit
val succinct = Count.succinct(1500000L)
println(succinct)  // "1.50M"

// Raw value
count.toLong  // 1500000L
```

### Unit Conversion

```scala
val count = Count(2500000L)

count.mostSuccinctCount                // Count(2.50, MILLION)
count.convertTo(CountUnit.THOUSAND)    // Count(2500, THOUSAND)
count.valueOf(CountUnit.MILLION)       // 2.5
```

### Units

| Unit | Symbol | Value |
|------|--------|-------|
| `ONE` | (none) | 1 |
| `THOUSAND` | K | 1,000 |
| `MILLION` | M | 1,000,000 |
| `BILLION` | B | 1,000,000,000 |
| `TRILLION` | T | 1,000,000,000,000 |
| `QUADRILLION` | Q | 1,000,000,000,000,000 |

## ElapsedTime

Human-readable duration representation with unit conversion.

```scala
import wvlet.uni.util.ElapsedTime

// Parse from string
val duration = ElapsedTime("5ms")
val timeout  = ElapsedTime("30s")
val window   = ElapsedTime("2.5h")

// Create from measurements
val fromNanos  = ElapsedTime.succinctNanos(1500000L)
println(fromNanos)   // "1.50ms"

val fromMillis = ElapsedTime.succinctMillis(3600000L)
println(fromMillis)  // "1.00h"

// Measure elapsed time
val start = System.nanoTime()
// ... do work ...
val elapsed = ElapsedTime.nanosSince(start)
println(elapsed)  // e.g., "42.30ms"
```

### Unit Conversion

```scala
val duration = ElapsedTime("90s")

duration.toMillis                                       // 90000.0
duration.valueIn(java.util.concurrent.TimeUnit.MINUTES) // 1.5
duration.convertToMostSuccinctTimeUnit                  // ElapsedTime(1.50, MINUTES)
```

### Units

| Unit | Symbol | Example |
|------|--------|---------|
| `NANOSECONDS` | ns | `"500ns"` |
| `MICROSECONDS` | us | `"100us"` |
| `MILLISECONDS` | ms | `"5ms"` |
| `SECONDS` | s | `"30s"` |
| `MINUTES` | m | `"5m"` |
| `HOURS` | h | `"2.5h"` |
| `DAYS` | d | `"1d"` |

## Best Practices

1. **Use UUIDv7** for internal IDs — time-ordered, monotonic, with an embedded timestamp
2. **Use Base62** encoding for user-facing UUIDv7 — compact (22 chars), URL-safe, sort-order preserving
3. **Use NanoId** for opaque external tokens — map them back to internal UUIDv7 when needed
4. **Use succinct formatting** in logs and UIs for readability (`DataSize.succinct`, `Count.succinct`)
5. **Parse user input** with the string constructors — they handle common formats (`"128MB"`, `"1.5M"`, `"30s"`)
6. All value types implement `Comparable` for sorting and comparison
