# Stream chat replies in uni-harness over a streaming Uni RPC

## Goals

- Replace the type-in animation in uni-harness (`Bubble(animate = true)`) with a reply that arrives token by token through a typed `ChatApi` call, in both Electron and the browser.
- Use it to show **server-streaming RPC** in Uni from end to end. An agent or LLM app built from the example can pass its model's token stream straight through to the UI.

## Background

- Uni RPC is unary today. `RPCDispatcher` turns the first value of an `Rx` result into one JSON response, and `RPCAsyncClient.call` decodes one body.
- HTTP already streams SSE on both ends: `Response.eventStream` on Node, Netty, and Native servers, and `HttpAsyncClient.sendSSE` on fetch and the JVM (ADR 2026-10-22).
- Electron IPC can't stream: `ElectronRendererChannel.sendStreaming` throws, and the preload bridge only exposes `request(payload): Promise`.
- So "streaming RPC end to end" needs work in the library (dispatcher, client, Electron transport), not just in the example.

## Design

Lane: Standard. It adds public RPC and Electron API and changes the preload bridge contract.

**User flow (harness):** the user sends a prompt. The draft prompt shows right away, the assistant bubble appears on the first token and grows as tokens arrive, and the composer unlocks when the stream ends. If the stream fails partway, the partial text stays, the error banner shows, and the composer unlocks.

**Library: server-streaming RPC**
- A service method returns `Rx[A]` as usual. A client asks for a stream with `rpc.stream[A]("method", args*)`, which sends `Accept: text/event-stream`. The dispatcher then sends each value as an SSE `data:` event (JSON), instead of only the first value.
- Unary calls stay byte-for-byte the same. A plain `call` to the same method still gets one JSON value.
- A failure mid-stream is sent as an `event: error` carrying the RPC error JSON. The client fails its `Rx` with the same `RPCException` a unary call would raise.
- Electron: `ElectronRPCServer` gets a second channel (`uni-rpc-stream`). Main forwards each event to the calling `webContents` under a per-call stream id, with the same sender checks as unary calls. The renderer channel implements `sendStreaming` over a new optional bridge function, `uniRPC.stream(payload, onEvent)`, which returns a cancel function. Closing the window cancels the server stream. Cancelling the renderer `Rx` only stops delivery, because `Rx` has no unsubscribe hook (same as fetch).
- Docs: a "Streaming" section in `docs/http/rpc.md` and the Electron preload snippet. Update `for-ai-agents.md` and the `uni` plugin skill (version bump), and add an ADR for the wire format.

**Harness**
- `ChatApi.streamMessage(conversationId, text): Rx[ChatEvent]`, where `ChatEvent` is an enum: `Started(user)`, `Delta(text)`, `Done(reply)`. It replaces `sendMessage`. The mock model streams its echo a few words at a time.
- `ChatService(respond = ...)` takes `String => Rx[String]` (chunks), so a real model's token stream fits directly.
- `ChatStore` appends deltas to a live assistant message keyed like the drafts, so the bubble renders once and only its text grows. `Bubble` loses `animate` and the interval timer.
- The Activity log shows one row per stream, with time to first token and total time. The call-path diagram stays as it is.
- Tests: `ChatStoreTest` with held-back chunks (order, mid-stream failure, switching threads mid-stream) and dispatcher/client tests for SSE framing and errors on JVM and JS.

## Alternatives and Why Not?

- **Mark streaming methods with an `@rpcStream` annotation instead of content negotiation.** It is clearer for codegen and OpenAPI, but adds an annotation every server trait must carry. The Accept header keeps the trait unchanged, and an annotation can still be added later for codegen.
- **Harness-only streaming (ad-hoc SSE endpoint plus a custom IPC event).** It skips the library work but bypasses `ChatApi`, so it doesn't show RPC streaming, which is what this item is for.
- **Electron streaming over `MessagePort`.** It gives per-call isolation for free, but the preload code gets harder and `ipcMain.handle`-style sender checks don't apply. An id on a shared channel matches how the pty already streams.
- **Split into two PRs (library, then harness).** Reasonable if the diff grows past review size. The harness is the first user and the test bed, so one PR keeps the API honest.
