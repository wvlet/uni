# Let the uni-harness agent bridge target any window, and trim the package

## Goals

- **Any window**: an MCP client of the `--agent-bridge` endpoint (#906) can list the open harness windows and run `snapshot`, `query`, `act`, and `changes` on any one of them. Today the bridge talks only to the newest window, so an agent can't drive a second window or tell which one it is operating.
- **Smaller package**: the macOS app ships only the node-pty prebuild it runs (today it also carries the Windows and darwin-x64 ones, ~1.6 MB), and `pnpm package` stops warning that `package.json` has no `author`.

## Background

- The bridge's MCP tools are generated from uni's `UiAgentApi` trait (`MCPServer.withTools[UiAgentApi]`). The same trait is what each renderer serves over IPC, and other transports (WebMCP, plain Uni RPC) expose it too, so it describes one page and has no notion of windows.
- `AgentBridge.attach` keeps a single client: a new window replaces the previous one, and a closed window leaves a dead client behind until the next window opens.
- The harness has no way to open a second top-level window today: `createWindow()` runs at startup and on macOS `activate` when no window is left. So "newest window" only matters after closing and reopening.
- Detached dock panels (`uni-dock-*` popups) render into their own `document`, which the main window's `UiAgent` does not walk (it walks `dom.document.body`). An agent can't see a detached panel today; fixing that is a uni-ui `UiAgent` change, not a bridge change.
- ADR `2026-11-13-electron-reverse-ipc.md` records "the bridge talks to one window at a time" as a consequence.
- The security boundary is 127.0.0.1, an OS-picked port, and a random bearer token checked in constant time. Nothing here changes it.
- node-pty is a production dependency, so electron-builder copies its whole `prebuilds/` folder (every platform and arch) into the app. The harness's `electron-builder.yml` has no per-platform file rules.

## Design

Lane: Standard — part 1 changes the MCP tool surface and part 2 changes packaging, both always-gate areas.

Ship as **two PRs** from origin/main, not stacked: they touch disjoint files, need different verification (CDP vs. `pnpm package`), and each can merge on its own.

### PR 1: target any window

User flow: the developer opens a second window with **File > New Window** (Cmd/Ctrl+N), a menu item this PR adds so multiple windows exist at all. The agent connects as today and calls `windows` to see the list; each other tool takes an optional `window` id and, without one, keeps working on the default window, so existing prompts and the README's example keep working unchanged.

- Add a bridge-only tool surface in the harness (a `HarnessAgentApi` in `example.server`), leaving uni's `UiAgentApi` unchanged. The renderer keeps serving the plain trait; only the bridge knows about windows.
- New tool `windows`: JSON list of `{ id, title, focused, default }`, one per open harness window. `id` is Electron's `BrowserWindow.id`, stable for the window's lifetime.
- `snapshot`, `query`, `act`, `changes` gain an optional `window` id. Omitted: the focused harness window, else the most recently opened. An unknown or closed id fails with a not-found error that tells the agent to call `windows`.
- The bridge keeps one IPC client per window and drops it when the window closes, so no dead client is left behind.
- Every result names the window that answered (`window` id alongside the renderer's JSON), so an agent that relied on the default can see which window it hit.
- `changes` cursors are per window (each renderer's `UiAgent` owns its log); the description says a cursor is valid only for the window that returned it.
- `index.js` passes the `BrowserWindow` to `attachAgentBridge` instead of its `webContents`, so the bridge can read `id`, title, and focus.
- Loopback bind, random port, and token check are untouched.
- README's agent section names `windows`, the `window` argument, and New Window; the ADR gets an addendum replacing the one-window consequence.
- Out of scope: detached dock panels stay invisible to the agent; filed as a follow-up item (uni-ui `UiAgent` walking popup documents).
- Tests: Scala tests of the routing (default choice, explicit id, unknown id, close drops the window) with in-memory fakes, no mocks library; a CDP + Playwright run with two windows confirms `act` on the older window changes that window only.

### PR 2: trim node-pty prebuilds, set author

- Exclude node-pty prebuilds for other platforms and archs in `electron-builder.yml`, with `files` rules that drop `node_modules/node-pty/prebuilds/**` and re-include `prebuilds/${platform}-${arch}/**` (electron-builder's `${platform}` is `process.platform`, matching node-pty's folder names; `${os}` would be `mac`), so a macOS arm64 build keeps only `darwin-arm64`.
- Set `author` in `package.json` to the project (`wvlet`), matching the repo's other published metadata.
- Verify: `pnpm package` on macOS, compare the `.app` and `.dmg` sizes before and after, list the bundled `prebuilds/`, and launch the packaged app to confirm the terminal still spawns a shell.

## Alternatives and Why Not?

- **Add a `window` parameter to uni's `UiAgentApi`.** Pushes an Electron-only concept into a library trait that WebMCP and single-page transports implement, where it means nothing.
- **A `selectWindow` tool that switches the bridge's current window.** Stateful across calls, so two agents (or one agent's parallel calls) would race; an explicit argument per call has no shared state.
- **One MCP endpoint per window.** The agent would need a new URL and `claude mcp add` per window, and the bridge would hold more listeners and tokens.
- **Delete the extra prebuilds in an `afterPack` hook.** The files are inside `app.asar` by then; filtering in `files` keeps them out from the start.
- **Make dock panels targetable windows instead.** Needs `UiAgent` to walk popup documents in uni-ui; a bigger, separate change from the bridge routing.
- **One combined PR.** Mixes a tool-surface review with a packaging review for no shared benefit.
