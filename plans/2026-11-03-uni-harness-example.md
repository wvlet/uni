# uni-harness example: a chat workspace UI for Electron and the browser

## Goals

- Show that Uni + Scala.js can build a full desktop-style app shell, not just a counter: icon rail, foldable left sidebar, chat with a composer, and a right dock.
- Run the same UI code as an Electron app and as a web app in a browser, so users see that one Scala.js renderer serves both.
- Give people a template they can copy for their own chat or agent tools.

## Background

- `examples/electron-app` is a minimal counter: Rx HTML renderer + RPC to the main process over Electron IPC (`ElectronRenderer.install()` / `ElectronRPCServer.serve`).
- `examples/web-ui` is a browser todo list with no backend.
- Uni's Http client picks its transport from the installed channel factory, and `Http.server` runs on Node via Scala.js. So one `ChatApi` RPC trait can be called over IPC in Electron and over HTTP in the browser, with no change to the calling code.
- `wvlet.uni.dom` already has the pieces a shell needs: `Rx.variable`, `Storage` (persisted UI state), `Keyboard`, `MediaQuery`, `DomRef`.

## Design

Lane: Standard — a new example app with new screens and two run modes.

**User flow**

- A developer copies `examples/uni-harness`, runs `pnpm dev` for the Electron window, or `pnpm dev:web` and opens `http://localhost:5173` for the browser.
- The window shows: an icon rail (Chat, Files, Settings), a left sidebar listing conversations, the chat thread with a composer at the bottom, and a right dock (context/details panel).
- Clicking a rail icon switches the sidebar view. Clicking the active icon, or the toggle button (Cmd/Ctrl+B), folds the sidebar; another toggle opens and closes the right dock. Fold state is remembered across reloads.
- Typing in the composer and pressing Enter (Shift+Enter for a newline) sends a message. The reply appears in the thread, and "New chat" adds a conversation to the sidebar.

**Decisions**

- New standalone example `examples/uni-harness`, modeled on `electron-app` (electron-vite + `vite-plugin-scalajs` + Tailwind); the counter example stays minimal.
- Shared `api` module: a `ChatApi` RPC trait (list conversations, create one, list messages, send a message).
- A `server` Scala.js module implements `ChatApi` once and exposes it two ways: `ElectronRPCServer.serve(ipcMain, …)` in Electron, `Http.server` on Node for the web build.
- The renderer detects `window.uniRPC`: present → `ElectronRenderer.install()` (IPC); absent → default fetch to same origin `/…` RPC paths (Vite proxies them in dev).
- The assistant is a built-in echo/mock responder with a short delay, so the example has no API keys, network calls, or LLM dependency. A comment points to where a real model call would go.
- Layout components (`IconRail`, `Sidebar`, `ChatView`, `Composer`, `Dock`) live in the example, not in the `uni` library. We can promote them later once the API settles.
- Out of scope: streaming tokens, Markdown rendering, resizable panes, persistence of messages beyond the process lifetime.
- Docs: a README for the example, a row in `examples/README.md`, and a link from `docs/http/electron.md`. CI does not build it (standalone build like `electron-app`).

## Alternatives and Why Not?

- **Extend `electron-app` instead.** It would lose its role as the smallest IPC example.
- **Add shell components to `wvlet.uni.dom`.** Fixes a public API before we know the right shape; easier to start as an example.
- **JVM (Netty) server for the web mode.** Needs a second implementation language target; the Node `Http.server` reuses the exact Scala.js code that Electron's main process runs.
- **Call a real LLM.** Needs credentials and a provider choice; out of scope for an example of UI + transport.
