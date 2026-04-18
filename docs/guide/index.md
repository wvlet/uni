# Introduction

**Uni** — Essential Scala Utilities, refined for Scala 3 with minimal dependencies.

Uni provides small, reusable building blocks that complement the Scala standard library. It consolidates foundational utilities from the [wvlet/airframe](https://github.com/wvlet/airframe) project into a single, cohesive library.

## What is Uni?

Uni provides:

- **Design** - Object wiring with lifecycle management
- **Logging** - Structured logging with source code location tracking
- **Serialization** - JSON parsing/generation and MessagePack binary format
- **FileSystem** - Cross-platform file I/O and path handling for JVM, Scala.js, and Scala Native
- **HTTP Client** - Cross-platform client with retry and streaming support
- **Reactive Streams** - Rx-based operators for async data flows
- **Control Flow** - Retry logic, circuit breakers, and resource management
- **CLI Utilities** - Terminal styling, progress bars, and command launching
- **Type Introspection** - Compile-time reflection with Surface

## Design Philosophy

uni follows these principles:

1. **Minimal Dependencies** - Core functionality without heavy external dependencies
2. **Cross-Platform** - Works on JVM, Scala.js, and Scala Native
3. **Scala 3 First** - Modern Scala 3 syntax and features
4. **Composable** - Small, focused utilities that combine well
5. **Production Ready** - Battle-tested code from the Airframe ecosystem

## Module Structure

### uni — Core Utilities

| Module | Description |
|--------|-------------|
| [Design](/core/design) | Object wiring with lifecycle management |
| [Logging](/core/logging) | Structured logging with source code location tracking |
| [JSON](/core/json) | JSON parsing and generation |
| [MessagePack](/core/msgpack) | Binary serialization format |
| [Surface](/core/surface) | Compile-time type introspection |
| [FileSystem](/core/filesystem) | Cross-platform file I/O with `IOPath` for JVM, Scala.js, and Scala Native |
| [HTTP](/http/) | HTTP client with retry and streaming support |
| [Rx](/rx/) | Reactive streams and async data flows |
| [Control](/control/) | Retry logic, circuit breakers, and caching |
| [CLI](/cli/) | Terminal styling, progress bars, and command launching |

### uni-agent — LLM Agent Framework

| Module | Description |
|--------|-------------|
| [LLM Agent](/agent/llm-agent) | Core agent abstraction for AI workflows |
| [Chat Sessions](/agent/chat-session) | Conversation state management |
| [Tool Integration](/agent/tools) | Function calling and external tools |
| [AWS Bedrock](/agent/bedrock) | Bedrock chat model integration |

## Next Steps

- [Installation](./installation) - Add uni to your project
- [Design Principles](./principles) - Learn about the architecture
- [Core Utilities](/core/) - Explore the foundational APIs
