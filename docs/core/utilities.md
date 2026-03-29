# Utilities

Human-readable value types and ID generators for common programming tasks.

## ULID

Universally Unique Lexicographically Sortable Identifiers — 128-bit IDs that are time-ordered and encoded as 26-character strings using Crockford Base32.

```scala
import wvlet.uni.util.ULID

// Generate a new ULID
val id = ULID.newULID
println(id)  // e.g., "01ARZ3NDEKTSV4RRFFQ69G5FAV"

// Generate as string directly
val idStr: String = ULID.newULIDString
```

### Parsing and Validation

```scala
// Parse from string
val parsed = ULID.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV")

// Validate
ULID.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV")  // true
ULID.isValid("invalid")                       // false
```

### Extracting Timestamps

```scala
val id = ULID.newULID

// Timestamp in milliseconds
val millis: Long = id.epochMillis

// Convert to Instant
val instant: java.time.Instant = id.toInstant

// Create from a specific timestamp
val historical = ULID.ofMillis(1609459200000L)
```

### Converting to/from UUID

```scala
import java.util.UUID

val ulid = ULID.newULID
val uuid: UUID = ulid.toUUID
val back: ULID = ULID.fromUUID(uuid)

// Binary representation
val bytes: Array[Byte] = ulid.toBytes  // 16 bytes
val fromBytes: ULID = ULID.fromBytes(bytes)
```

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
| `KILOBYTE` | kB | 1,000 |
| `MEGABYTE` | MB | 1,000,000 |
| `GIGABYTE` | GB | 1,000,000,000 |
| `TERABYTE` | TB | 1,000,000,000,000 |
| `PETABYTE` | PB | 1,000,000,000,000,000 |

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

1. **Use ULID** for distributed IDs — they are time-ordered, URL-safe, and monotonic
2. **Use succinct formatting** in logs and UIs for readability (`DataSize.succinct`, `Count.succinct`)
3. **Parse user input** with the string constructors — they handle common formats (`"128MB"`, `"1.5M"`, `"30s"`)
4. All value types implement `Comparable` for sorting and comparison
