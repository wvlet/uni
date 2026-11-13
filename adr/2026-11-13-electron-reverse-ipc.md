# 2026-11-13: Main→renderer Electron IPC and the uni-harness agent bridge

## Context

`UiAgent` (adr/2026-11-13-ui-agent.md) runs in the renderer because it walks the DOM. An AI agent outside the app (an MCP client) can only reach the Electron main process, but the IPC transport (adr/2026-06-24-electron-ipc-transport.md) only went renderer→main. `UiAgentApi`'s `snapshot`, `query`, and `changes` returned a plain `String`, which a main-process forwarder cannot produce synchronously over IPC.

## Decision

- **Reverse channel in `wvlet.uni.electron`.** `ElectronMainChannel` is an `HttpAsyncChannel` bound to one `webContents`: it sends `(id, request)` on `<channel>-reverse` and takes `(id, response)` from `ipcMain` on `<channel>-reverse-reply`, with the existing request/response envelope. `ElectronMain.newAsyncClient(ipcMain, webContents)` wraps it. The renderer serves routers with `ElectronRenderer.serve(routers*)`, which hands an `RPCDispatcher`-backed handler to one more preload function, `bridge.serve(handler)`. `ipcMain` and `webContents` are passed in as values; Uni never calls `require("electron")`.
- **Who may answer.** A reply counts only when its `event.sender` is the target `webContents`, its frame is top-level, and its id names a waiting call. Ids carry a random per-channel prefix, so channels on the same window don't collide.
- **Waiting calls end.** `UNAVAILABLE_I2` when the `webContents` is destroyed or its main frame starts a cross-document navigation (the page that would answer is gone), `DEADLINE_EXCEEDED_I4` after a configurable timeout (120 s default). The preload listens from the start and answers `503` before the renderer serves, which the channel maps to `UNAVAILABLE_I2`, so an early call fails at once.
- **The harness client never times out.** An `act` on a `UiAgent.confirm` component waits for the human; a timeout would tell the agent it failed while the action could still apply. Close and reload still end every waiting call.
- **The preload always replies.** A handler that throws gets a 500 reply, so the main process never waits for a reply that won't come.
- **No retry.** `ElectronMain.newAsyncClient` uses `noRetry`: a call cut off by a reload must not run again on the new page, since `act` changes app state.
- **`UiAgentApi` returns `Rx[String]` everywhere.** A forwarder in another process can then implement the trait over any async transport. #905 had not been released, so no released API changed.
- **uni-harness agent bridge.** With `--agent-bridge` (`pnpm dev:agent`), the main process serves `MCPServer.withTools[UiAgentApi](forwarder)` with `Http.server` on 127.0.0.1 at an OS-chosen port, on `/mcp` only. Every request needs `Authorization: Bearer <token>` (scheme case-insensitive), 32 bytes from `crypto.getRandomValues` made at startup and compared in constant time; others get 401 before MCP parsing. MCPServer's Origin check still applies. The main process prints the URL, the token, and a `claude mcp add` line; a bridge that fails to start is logged and the window still opens. Without the flag there is no endpoint, and the preload's `uniHarness.agentBridge` is false, so the renderer doesn't install `UiAgent`.

## Alternatives considered

- **A dispatch hook on `MCPServer` that forwards raw RPC requests.** Avoids the `Rx` signature change, but adds transport-specific API to `MCPServer`.
- **MCP over stdio from the main process.** Electron's stdout is shared with logs and the dev server, and an MCP client cannot attach to an app it did not start.
- **A fixed port.** Collides between instances; the per-launch token already forces a fresh registration.
- **Streaming main→renderer.** Not needed by `UiAgentApi`; `sendStreaming` fails with `NotImplementedError`.

## Consequences

- The bridge talks to one window at a time: `attachAgentBridge(webContents)` points it at the newest window.
- An error reply waits out uni's retry backoff once (about 1 s) even with `noRetry`, as the renderer→main channel already does. A fix belongs in `Retry`, not here.
- The bearer token is the only credential, and it lives in the developer's terminal. The bridge is a development tool, not something to ship enabled.
