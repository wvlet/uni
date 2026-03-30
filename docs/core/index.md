# Core Utilities

The `uni` module provides essential utilities for Scala application development.

## Overview

| Component | Description |
|-----------|-------------|
| [Design](./design) | Object wiring with lifecycle management |
| [UniTest](./unitest) | Lightweight testing framework |
| [Logging](./logging) | Structured logging with LogSupport |
| [JSON](./json) | Pure Scala JSON parser and DSL |
| [MessagePack](./msgpack) | Binary serialization format |
| [Surface](./surface) | Compile-time type introspection |
| [FileSystem](./filesystem) | Cross-platform file I/O with IOPath |
| [Utilities](./utilities) | ULID, DataSize, Count, ElapsedTime |

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
