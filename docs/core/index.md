# Core

The `uni` module provides essential utilities for Scala application development. This
section covers the building blocks most applications start with.

## Overview

| Component | Description |
|-----------|-------------|
| [Design](./design) | Object wiring with lifecycle management |
| [Logging](./logging) | Structured logging with LogSupport |
| [Type Introspection](./surface) | Compile-time type introspection with Surface |
| [Result](./result) | A value that may have failed, with a typed error channel |
| [SettingStore](./settings) | User settings that survive restarts |
| [Utilities](./utilities) | IDs (UUIDv7, ULID, NanoId), encodings (Base62, CrockfordBase32), value types (DataSize, Count, ElapsedTime), timing (StopWatch, Timer) |

The same module also has [Data & Serialization](../text/) (Weaver, JSON, MessagePack, YAML,
Markdown, Cron), [Files & Memory](./io) (IO, FileSystem, Canvas), and the
[UniTest](./unitest) test framework. See [All Modules](../modules) for the full list.

## Quick Start

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport
import wvlet.uni.json.JSON

// Service with logging
class UserService extends LogSupport:
  def findUser(id: String): Option[User] =
    debug(s"Finding user: ${id}")
    // Implementation
    None

// Object wiring with Design
val design = Design.newDesign
  .bindSingleton[UserService]

design.build[UserService] { service =>
  service.findUser("123")
}

// JSON parsing
val json = JSON.parse("""{"name": "Alice", "age": 30}""")
val name = json("name").toStringValue  // "Alice"
```

## Package Structure

```
wvlet.uni.design   - Object wiring framework
wvlet.uni.log      - Logging framework
wvlet.uni.json     - JSON processing
wvlet.uni.msgpack  - MessagePack serialization
wvlet.uni.surface  - Type reflection
wvlet.uni.weaver   - Object serialization
wvlet.uni.io       - Cross-platform file system
wvlet.uni.canvas   - Off-heap memory buffers
wvlet.uni.util     - ID generators and value types
```

## Cross-Platform Support

All core utilities work across JVM, Scala.js, and Scala Native platforms.

| Feature | JVM | JS | Native |
|---------|-----|-----|--------|
| Design | ✓ | ✓ | ✓ |
| Logging | ✓ | ✓ | ✓ |
| JSON | ✓ | ✓ | ✓ |
| MessagePack | ✓ | ✓ | ✓ |
| Surface | ✓ | ✓ | ✓ |
| FileSystem | ✓ | ✓ | ✓ |
| Utilities | ✓ | ✓ | ✓ |
