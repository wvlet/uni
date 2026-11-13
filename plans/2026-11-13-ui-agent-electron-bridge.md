# UiAgent over Electron: reverse IPC and the harness agent bridge

## Goals

- Let an AI agent outside the app (Claude Code, an MCP client) read and operate a running Electron app built with uni-ui, through the `UiAgent` that #905 added.
- Keep the bridge a developer tool: off by default, local only, and guarded by a token.

## Background

- `UiAgent` runs in the renderer, because it walks the DOM. An MCP client can only reach the main process (Node), so main has to call the renderer.
- Today's Electron IPC (adr/2026-06-24-electron-ipc-transport.md) only goes renderer→main: `ipcRenderer.invoke` plus a stream channel.
- `UiAgentApi` (in `uni`) is the JSON-text wire trait. Its `snapshot`, `query`, and `changes` return a plain `String`, which a main-process forwarder cannot produce synchronously over IPC.

## Design

Lane: Standard (new transport and a local network endpoint, a security boundary). The user approved this direction on 2026-11-13, including the always-gate parts: the reverse channel, the `--agent-bridge` flag, the 127.0.0.1 binding, and the bearer token.

Developer flow:
- Start the harness with `pnpm dev:agent` (`electron-vite dev -- --agent-bridge`). The terminal prints the MCP URL and a fresh token.
- Register it in an MCP client (`claude mcp add --transport http uni-harness <url> --header "Authorization: Bearer <token>"`), then call `snapshot` and `act`.
- Without the flag there is no endpoint, and the renderer does not install `UiAgent`. Quitting the app ends the bridge; a restart prints a new token.

Reverse channel (`wvlet.uni.electron`):
- Main calls the renderer as an `HttpAsyncChannel`: `webContents.send("<channel>-reverse", id, payload)`, and the renderer answers on `ipcMain` `"<channel>-reverse-reply"` with `(id, response)`. Payloads are the existing request/response envelope.
- The renderer serves routers with `ElectronRenderer.serve(routers*)`, which hands an `RPCDispatcher`-backed handler to one more preload function, `bridge.serve(handler)`.
- `ipcMain` and `webContents` are passed in as values; Uni never calls `require("electron")`.
- Main accepts a reply only from the target `webContents`' top-level frame, matched by call id. A pending call fails when the `webContents` is destroyed or its main frame reloads, or after a timeout (configurable).
- A renderer that has not called `serve` answers at once with an UNAVAILABLE RPC error, so main does not wait for the timeout.
- `ElectronTestbed` gains the renderer side of the reverse channel, so apps can test it without Electron.

Harness bridge (examples/uni-harness):
- Main runs `MCPServer.withTools[UiAgentApi](forwarder)` with `Http.server` on 127.0.0.1, port chosen by the OS. The forwarder calls the renderer through the reverse channel.
- Every request needs `Authorization: Bearer <token>`. The token is 32 random bytes from `crypto.getRandomValues`, compared in constant time. A missing or wrong token gets 401 before MCP parsing.
- MCPServer's existing Origin check stays, so a web page cannot call it from the browser either.

Decisions I made:
- `UiAgentApi.snapshot`, `query`, and `changes` return `Rx[String]`, like `act`, so a forwarder can implement the trait over any async transport. #905 is not released yet (last tag v2026.1.41 predates it), so no released API breaks.
- The forwarder stays in the harness as a few lines over `RPCClient.callAsync`, not a new public API.
- Tests cover the reverse round-trip, failure on destroy and reload, a reply from the wrong sender, an unserved renderer, and the harness endpoint's tool list and 401 without or with a wrong token.

## Alternatives and Why Not?

- Let `MCPServer` forward raw RPC requests to the renderer: avoids the `Rx` signature change, but adds a dispatch hook to `MCPServer` for one transport.
- Run MCP over stdio from main: Electron's stdout is shared with logs and the dev server, and an MCP client cannot attach to an app it did not start.
- A fixed port: collides between instances; the token already forces a fresh registration per launch.
