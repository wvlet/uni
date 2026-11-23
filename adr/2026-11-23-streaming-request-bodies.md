# Streaming request bodies

## Context

Every backend buffered a request body in full before the handler ran, up to `maxContentLength` (1 MiB on the socket servers and Node.js, 64 KiB on Netty). An app taking large uploads to a file or an `ObjectStore` had to raise that limit, and with it the memory every request could claim, because the same limit covered every route. On the built-in JVM server each connection runs on its own virtual thread, and on Scala Native on a worker, so a handler can read a body as a blocking stream without blocking an event loop.

## Decision

- **Opt in per request, on the head:** `Http.server.withStreamingRequests(req => Boolean)`. The socket reader calls the predicate after it parses and validates the head, and before reading any body bytes. Only requests with a body (`Content-Length` > 0 or chunked) and a known method are offered to it, and a predicate that throws streams nothing. Every other request is buffered as before, so filters and loggers that read bodies keep working.
- **The body is `HttpContent.StreamContent`:** `openStream: InputStream` and `length`, which is the `Content-Length` or -1 for a chunked body. `toContentBytes` reads what is left and keeps it, so a filter that reads the body leaves it for the handler. `contentHash` hashes the identity, as `FileContent`'s does, so hashing reads nothing.
- **The same framing as buffered bodies.** The streamed body is read from the reader's own buffer with the same strict rules. The chunk size-line parser is shared with the buffered path (`chunkSizeLine`), so the Lean model's framing rules cover both. A broken body sets the stream's failure and makes reads throw `HttpContent.RequestBodyException`, a client error that is not logged at warn. The server then answers its status (400, 408, 413) instead of the handler's response, and closes the connection.
- **`100 Continue` on the first read**, so filters run first and a rejected request is never uploaded. If the handler never reads, the server answers without `100` and closes the connection, since the client may still send the body.
- **Limits:**
  - `maxStreamingRequestSize` (1 GiB by default) replaces `maxContentLength`. A larger declared length gets 413 at the head, and a chunked body over it gets 413 when it passes the limit.
  - `streamingRequestTimeoutMillis` (1 hour from the end of the head) replaces the 30 s whole-request deadline, and each read still waits at most the per-read timeout (30 s), so a slow but moving upload is not cut off and a stalled one is.
  - Each upload holds a `maxStreamingConnections` slot while its handler runs, so on Native uploads can't take every worker.
  - Streaming requests have no handler timeout.
- **After the response is decided,** the stream refuses further reads, then the server reads and discards up to 64 KiB of an unread remainder to keep the connection. A longer remainder (or a known `Content-Length` beyond it) closes the connection. An error met while draining only closes the connection; it never replaces the handler's response. The predicate may run more than once per request (Netty's decompressor and its handler each ask), so it must depend only on the head.
- **No decompression for streamed bodies**, on any backend: the handler sees `Content-Encoding` and the bytes as sent. Netty's decompressor passes streamed requests through.
- **Netty and Node.js buffer, then hand over a stream** of the buffered bytes, still bounded by `maxContentLength`. Handler code is the same on every backend. Node.js can't block on a read. Streaming through Netty would mean bypassing `HttpObjectAggregator` per request and bridging its event loop to a blocking stream; uni-netty is now optional, so it isn't worth that work.

## Alternatives

- **Opt in per route through a Router annotation:** cleaner for `@Endpoint` users, but it needs Router and RPC changes. It can come later on top of the predicate.
- **Stream every body lazily:** this breaks filters that read the body, and buffered handlers would lose the `maxContentLength` protection.
- **`Rx[Array[Byte]]` instead of `InputStream`:** this fits JS, but handlers on the JVM and Native would need back-pressure plumbing. The direct-style stream fits the virtual-thread design.
- **Drain the whole remainder after the response:** a client could make the server read gigabytes nobody wants. 64 KiB keeps short leftovers cheap.

## Consequences

- `HttpConnectionReader.readRequest` now takes the predicate. When it returns a streamed request, the server must call `finishBody` before reading the next one.
