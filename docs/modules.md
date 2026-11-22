---
description: Every Uni module page in one place, grouped by task — core, data and serialization, files, Rx, control flow, HTTP and RPC, AI, CLI, testing and build, and web UI.
---

# All Modules

Every module page, grouped the same way as the sidebar. Add one dependency,
`"org.wvlet.uni" %% "uni"` ([Installation](./guide/installation)), and all of these are
available unless a row says otherwise.

## Core

| Page | Description |
|------|-------------|
| [Design](./core/design) | Object wiring with lifecycle management |
| [Logging](./core/logging) | Logging with source locations through `LogSupport` |
| [Type Introspection](./core/surface) | Compile-time type information with Surface |
| [Result](./core/result) | A value that may have failed, with a typed error channel |
| [SettingStore](./core/settings) | User settings that survive restarts, as an `RxVar` |
| [Utilities](./core/utilities) | IDs (UUIDv7, ULID, NanoId), encodings, value types, and timing |

## Data & Serialization

| Page | Description |
|------|-------------|
| [Weaver](./core/weaver) | JSON, MessagePack, and `Map` codecs from one `derives Weaver` |
| [JSON](./core/json) | Pure-Scala JSON parser, `JSONValue` tree, and query DSL |
| [MessagePack](./core/msgpack) | Compact binary serialization |
| [YAML](./text/yaml) | YAML 1.2 parser with Weaver decoding and frontmatter |
| [Markdown](./text/markdown) | CommonMark + GFM parser and safe HTML renderer |
| [Cron](./text/cron) | Cron expressions with next-run times and English descriptions |
| [Parquet](./text/parquet) | Pure-Scala Apache Parquet reader and writer: files, bytes, and HTTP ranges, from and to case classes |

## Files & Memory

| Page | Description |
|------|-------------|
| [IO](./core/io) | One entry point for file operations and subprocesses |
| [FileSystem](./core/filesystem) | Cross-platform file I/O with `IOPath` |
| [Object Storage](./core/object-storage) | Read and write S3, R2, MinIO, Google Cloud Storage, and Azure Blob objects without the AWS, Google, or Azure SDKs |
| [Canvas (Off-Heap Memory)](./core/canvas) | Memory buffers larger than 2 GB, addressed by `Long` offsets |

## Reactive Streams (Rx)

| Page | Description |
|------|-------------|
| [Overview](./rx/) | What `Rx` and `RxVar` are and when to use them |
| [Rx Basics](./rx/basics) | Creating and subscribing to streams |
| [Stream Operators](./rx/operators) | Transforming and combining streams |
| [Rx Concurrency](./rx/concurrency) | `RxRef`, `RxDeferred`, fibers, and semaphores |

## Control Flow

| Page | Description |
|------|-------------|
| [Overview](./control/) | Handling failures and managing resources |
| [Retry Logic](./control/retry) | Retry with backoff strategies |
| [Circuit Breaker](./control/circuit-breaker) | Stop calling a failing service |
| [Rate Limiter](./control/rate-limiter) | Throttle operation throughput (JVM) |
| [Caching](./control/cache) | TTL and LRU caches |
| [Resource Management](./control/resource) | Acquire and release resources safely |
| [Background Task](./control/background-task) | Cancellable background work you can poll for progress |

## HTTP & RPC

| Page | Description |
|------|-------------|
| [Overview](./http/) | The cross-platform HTTP stack at a glance |
| [HTTP Client](./http/client) | Sync and async HTTP clients |
| [REST Server](./http/server) | HTTP server built in on the JVM and Scala Native, on Node.js, or on Netty |
| [Router](./http/router) | Type-safe route definitions |
| [RPC](./http/rpc) | Scala traits as remote services, with generated clients |
| [OpenAPI](./http/openapi) | OpenAPI 3.0 documents from `@Endpoint` routes |
| [Server-Sent Events](./http/sse) | One-way server-to-client streaming |
| [WebSocket Client](./http/websocket) | Cross-platform bidirectional WebSocket client |
| [HTTP Retry Strategies](./http/retry) | Automatic retry for transient HTTP failures |
| [MCP Server (stdio)](./mcp/) | A Scala trait as an MCP server that AI agents call as tools |

## AI

| Page | Description |
|------|-------------|
| [LLM APIs](./ai/) | Typed Anthropic, OpenAI, and Gemini (also on Vertex AI and Bedrock) requests, responses, and streams, with clients and converters between vendors |

## CLI

| Page | Description |
|------|-------------|
| [Overview](./cli/) | Building command-line applications |
| [Terminal Styling](./cli/tint) | Colored terminal output with Tint |
| [Progress Indicators](./cli/progress) | Progress bars and spinners |
| [Line Editing](./cli/line-reader) | Line editing, history, and completion for REPLs |
| [Command Launcher](./cli/launcher) | Type-safe command-line argument parsing |

## Testing & Build

| Page | Description |
|------|-------------|
| [UniTest](./core/unitest) | Lightweight cross-platform test framework (`uni-test`) |
| [sbt-uni Plugin](./build/sbt-uni) | RPC client generation and other build tasks |
| [sbt-uni-playwright Plugin](./build/sbt-uni-playwright) | Run Scala.js tests in a real browser |
| [sbt-uni-crossproject Plugin](./build/sbt-uni-crossproject) | Cross-build for JVM, Scala.js, and Scala Native |
| [sbt-uni-javadoc Plugin](./build/sbt-uni-javadoc) | Keep published `-javadoc.jar` files small |

## Web UI (Scala.js)

Browser and desktop UIs have their own section: start at [Web UI](./dom/), which covers
uni-dom, the uni-ui components (`uni-ui`), and [Electron desktop apps](./http/electron).
