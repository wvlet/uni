# Cancel streaming RPC on the server

## Goals

- When a client cancels the `Rx` from `rpc.stream` (unsubscribe, `take(n)`, a view that unmounts), the server stream stops, over both fetch and Electron IPC.
- A window reload or navigation cancels the Electron streams the old page started, instead of letting them run until the window closes.

## Background

- `Rx` has no hook that runs on unsubscribe. Both renderer channels push chunks into an `RxVar`, so cancelling the subscription only stops delivery (ADR 2026-11-06, Cancellation).
- `FetchChannel.sendStreaming` starts the fetch when it is called, not when the `Rx` is subscribed. There is nothing to abort, and an `Rx` that is never subscribed still sends the request.
- The Electron server cancels a stream only on the sender's `destroyed` event. A reload keeps the same `webContents`, so the old page's streams keep running.
- The preload's `stream(payload, onMessage)` returns nothing today, and ids are already unique per page load.
- Cancellation reaches the byte stream through `sendSSE` → `parseStream` → `streamAsync`, because they are plain operator chains (`flatMap`, `concat`, `map`).

## Design

Lane: Standard. It adds a public `Rx` operator and extends the preload bridge contract and the Electron IPC protocol.

**Flow (app author):** the app subscribes to `rpc.stream(...)` and later cancels it, for example the user presses Stop or leaves the chat. The server method's `Rx` gets cancelled within one IPC round trip or when the fetch aborts. Nothing changes in service traits or call sites.

**Rx hook (uni-core)**
- Add `rx.onCancel(f: => Unit): Rx[A]`. It runs `f` once per subscription when that subscription is cancelled before it sees `OnError` or `OnCompletion`.
- It doesn't run when the stream ends by itself, so a finished stream sends no cancel. It runs on `take(n)`, which cancels its upstream (ADR 2026-10-19). It doesn't run on `runOnce`/`await`, which don't cancel upstream today.
- A throw in `f` is logged and swallowed, so cancelling never fails.

**Fetch**
- Start the fetch on subscription (the same `RxVar` trick the Electron channel uses), with an `AbortController` signal. `onCancel` aborts it, and the abort's `AbortError` is not delivered.
- Servers already see an aborted fetch as a closed connection. Whether each server (Node, Netty, Native) then cancels the method's `Rx` is checked in tests, and fixed only if small.

**Electron**
- `bridge.stream(payload, onMessage)` may return a cancel function. The renderer channel calls it on `onCancel`. A bridge that returns nothing still works, with today's behavior.
- The preload's cancel function drops the listener and sends `<channel>-stream-cancel` with `(id)`. Update the harness preload, `docs/http/electron.md`, and the `uni` skill's Electron reference (version bump).
- The main process keeps running streams per `webContents` and id. A cancel message from the same sender cancels that stream, and an unknown id is ignored.
- A main-frame, cross-document navigation (`did-start-navigation`, which covers reload) cancels all streams of that `webContents`. Same-document navigation (hash, `pushState`) does not.
- Tests extend `ElectronTestbed` with a cancel message and a `navigate()` / `reload()` that fires the event, and check that listeners are removed when streams end.

**Docs**
- Update the ADR 2026-11-06 Cancellation and Consequences sections, and the `onCancel` entry in the Rx docs and `for-ai-agents.md` if it lists operators.

## Alternatives and Why Not?

- **A general finalizer (`guarantee`, runs on completion, error, and cancel).** It's a superset, but transports only need cancel, and "runs on completion" invites cleanup that races the final value. It can be added later on top of the same runner case.
- **A public callback source (`Rx.create(emitter => Cancelable)`).** It is the cleaner primitive for both channels, but a bigger API to design (back-pressure, threads). `onCancel` fixes the problem with one small operator.
- **Cancel on any `did-navigate` of a sub frame.** Streams from permitted iframes would also stop on their own navigation, but needs per-frame keying. Main-frame navigation and `destroyed` cover the reload case the item asks for.
- **JVM and Native clients.** Out of scope. `JavaHttpChannel` and `CurlChannel` can adopt `onCancel` in a follow-up.
