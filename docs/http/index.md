# HTTP Framework

uni provides a cross-platform HTTP client and server — running on the JVM
(Netty), Scala.js (Fetch / Node.js), and Scala Native (libcurl) — with
automatic retry, streaming support, and reactive integration.

## Overview

| Component | Description |
|-----------|-------------|
| [HTTP Client](./client) | Sync and async HTTP clients |
| [REST Server](./server) | Cross-platform HTTP server (Netty / Node.js / Native) with routing |
| [Router](./router) | Type-safe route definitions |
| [RPC](./rpc) | Type-safe remote calls over HTTP, with client code generation |
| [Server-Sent Events](./sse) | One-way server→client streaming |
| [WebSocket Client](./websocket) | Cross-platform bidirectional WebSocket client |
| [Retry Strategies](./retry) | Automatic retry for transient failures |

## Quick Start

```scala
import wvlet.uni.http.{Http, Request}

// Create a client
val client = Http.client.newSyncClient

// Make a GET request
val response = client.send(Request.get("https://api.example.com/users"))
println(response.contentAsString)

// Close the client
client.close()
```

## Async Client with Rx

```scala
import wvlet.uni.http.Http

val asyncClient = Http.client.newAsyncClient

asyncClient
  .send(Request.get("https://api.example.com/data"))
  .map(_.contentAsString)
  .subscribe { content =>
    println(s"Received: ${content}")
  }
```

## Key Features

- **Cross-Platform** - Works on JVM, Scala.js, and Scala Native
- **Automatic Retry** - Built-in retry with exponential backoff
- **Streaming** - Stream response bodies as byte chunks
- **Reactive** - Async client returns `Rx[HttpResponse]`
- **Configurable** - Timeouts, retries, headers

## Client Types

| Type | Method | Use Case |
|------|--------|----------|
| Sync | `newSyncClient` | Simple blocking requests |
| Async | `newAsyncClient` | Non-blocking with Rx |

## Package

```scala
import wvlet.uni.http.*
```
