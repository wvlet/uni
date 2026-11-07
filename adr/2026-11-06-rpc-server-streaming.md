# Server-streaming RPC over SSE and Electron IPC

## Context

Uni RPC was unary: `RPCDispatcher` answered with the first value of an `Rx` result, and `RPCAsyncClient.call` decoded one JSON body. An app that streams a model's tokens to its UI, the first one being the uni-harness example, had to leave its typed service API and add an ad-hoc SSE route or IPC channel. HTTP already streamed SSE on every server and on the JVM and fetch clients (ADR 2026-10-22). Electron IPC did not stream: `ipcRenderer.invoke` resolves once, and `ElectronRendererChannel.sendStreaming` threw.

## Decision

- **Content negotiation, not a marker.** A method that returns `Rx[A]` streams when `text/event-stream` is the *first* media type the request accepts (`RPCDispatcher.acceptsEventStream`). A generic client that sends `application/json, text/event-stream` keeps its JSON answer. `RPCAsyncClient.stream` / `RPCClient.streamAsync` send that header through `sendSSE`. Service traits, routers, and unary calls are unchanged, including the first-value behavior of `call` on a multi-value `Rx`. A method that returns a plain value streams it as one event.
- **Wire format.** One SSE `data:` event per value, carrying the same JSON `call` would return. The response carries `x-rpc-status: 0`.
- **Errors.** A failure before the method returns (decode error, unknown method, a throw in the method) is an ordinary error response, so the client fails with the same `HttpException` a unary call gets. A failure after the stream started is an `rpc-error` event (`RPCDispatcher.ErrorEvent`) carrying the RPC error JSON. It ends the stream, through `recoverWith` because `recover` does not complete, and the client fails with that `RPCException`. Unexpected exceptions are hidden unless `detailedErrors` is on, as for unary errors.
- **Old servers.** A 2xx answer that isn't an event stream (a server without streaming RPC ignores `Accept`) fails the client `Rx` with `UNIMPLEMENTED_U8`, instead of completing as an empty stream. The client checks the content type in `sendSSE`'s `onConnect`.
- **No retry.** It follows `sendSSE`: a started stream cannot be replayed.
- **Electron IPC.** It uses a second channel pair derived from the RPC channel: the renderer sends `(id, payload)` on `<channel>-stream` with `ipcRenderer.send`. The main process answers on `<channel>-stream-event` with `(id, message)`: one `head` (status, headers, body), then `chunk`s of the SSE text, then `end` (or `error` for an unexpected failure). The renderer channel feeds the chunks to the same `sendSSE` parsing as fetch, so decoding and error semantics are shared with HTTP.
  - Replies go through `event.reply`, so a permitted iframe gets its own messages. A payload that can't be read ends the call with an `error` message.
  - The ids are chosen by the preload and must be unique across page loads: a reload keeps the `webContents`, and the old page's stream may still be sending. One preload listener demultiplexes them, which avoids `EventEmitter`'s listener-count warning and a listener per call.
  - Sender checks are the same `ElectronRPCServerConfig.isAllowedSender` as for `invoke`. A rejected stream gets a `PERMISSION_DENIED_U14` head.
  - `bridge.stream` is optional. Without it, a streaming call fails with a `NotImplementedError` that names the missing function, and unary calls are unaffected.
- **Cancellation.** The main process cancels a stream when the sender's `webContents` emits `destroyed`, and removes that listener when the stream ends. Renderer-side cancellation of the `Rx` stops delivery but not the server stream. Uni's `Rx` has no hook that fires on unsubscribe, and the fetch channel behaves the same way.

## Alternatives

- **An `@rpcStream` annotation, or a distinct return type.** It is explicit for codegen and OpenAPI, but every streaming trait must carry it. Negotiation keeps traits unchanged, and a marker can still be added later for codegen without changing the wire format.
- **`MessagePort` per call in Electron.** It gives per-call isolation, but needs more preload code, and the `ipcMain` sender checks don't apply to port messages.
- **Structured stream messages (one IPC message per value).** It would skip SSE framing, but needs a second decoding path. Reusing the SSE text keeps one parser and one error model for both transports, at a small cost in bytes.

## Consequences

- `ElectronTestbed` fakes `ipcMain.on`, the sender `webContents` (async, ordered delivery), and `closeRenderer()`, so stream tests run without Electron.
- The fetch and Electron channels don't propagate renderer-side cancellation to the server. Fixing that needs an unsubscribe hook in `Rx` (or `AbortController` plumbing in fetch).
- The `sbt-uni` client generator still writes `call` for every method. Generating `stream` needs a marker (see Alternatives). Update (2026-11-07): `@rpcStream` is that marker. It is a Java runtime annotation that only codegen reads, and negotiation still decides streaming on the server.
