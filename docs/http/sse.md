# Server-Sent Events

Server-Sent Events (SSE) enable streaming server push over HTTP. Uni provides a model for creating, parsing, and handling SSE streams.

## ServerSentEvent Model

```scala
import wvlet.uni.http.ServerSentEvent

// Simple data event
val event = ServerSentEvent.data("Hello, world!")

// Event with type and ID
val typed = ServerSentEvent
  .data("user logged in")
  .withEvent("login")
  .withId("evt-001")

// Set reconnection interval (milliseconds)
val withRetry = ServerSentEvent
  .data("status update")
  .withRetry(5000)
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `data` | `String` | Event payload (required). Multiline supported. |
| `event` | `Option[String]` | Event type (defaults to `"message"` if omitted) |
| `id` | `Option[String]` | Event identifier for resumption |
| `retry` | `Option[Long]` | Reconnection time in milliseconds |

### Wire Format

`toContent` converts an event to the SSE wire format:

```scala
val event = ServerSentEvent
  .data("line1\nline2")
  .withId("123")
  .withEvent("update")
  .withRetry(5000)

println(event.toContent)
// id: 123
// event: update
// retry: 5000
// data: line1
// data: line2
//
```

Multiline data is automatically split into separate `data:` lines per the SSE specification.

### Builder Methods

All fields support fluent builder methods:

```scala
val event = ServerSentEvent.data("payload")
  .withId("id-1")       // Set id
  .withEvent("type")    // Set event type
  .withRetry(3000)      // Set retry interval
  .withData("new data") // Replace data

// Remove optional fields
event.noId
event.noEvent
event.noRetry
```

## Best Practices

1. **Use `id` for resumable streams** — clients can send `Last-Event-ID` to resume after disconnection
2. **Set `retry`** to control client reconnection behavior
3. **Use `event` types** to multiplex different message kinds on a single stream
4. **Keep payloads small** — SSE is text-based; for large binary data, send a reference and fetch separately
