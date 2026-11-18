# Docs structure: make the sidebar, nav, and pages agree

## Problem

Readers lose track of where they are because the top nav, the left sidebar, and the overview
pages each organize the same content differently.

1. **The sidebar changes shape between sections.** `/guide/` shows Getting Started plus one
   link per module area. Click into a module and the `/` sidebar replaces it with every module
   group (all collapsed) and Getting Started folded away. `/dom/` and `/http/electron*` get a
   third sidebar.
2. **The "Modules" nav item is really `/core/`.** It goes to "Core Utilities" and is only
   highlighted on `/core/`. On `/http/`, `/rx/`, `/control/`, `/text/`, `/cli/`, `/mcp/`, and
   `/build/` no nav item is active.
3. **The taxonomy differs by page.** `guide/index.md` "What's inside" groups by task (Core,
   Data & Serialization, Async & Control Flow, HTTP & RPC, UI & CLI, Testing). The sidebar
   groups by URL folder (Core, Text Formats, Control Flow, HTTP, MCP, Reactive Streams, CLI,
   Web UI, Build Tooling). `core/index.md` lists YAML/Markdown/Cron, which the sidebar puts
   under Text Formats.
4. **Pages appear twice or not at all.** `/core/json` sits under both Core and Text Formats.
   Two pages are about retries (`/control/retry` and `/http/retry`). `/design/rx-concurrency`
   is in no sidebar or llms.txt.
5. **Overview tables are stale.** `rx/index.md` skips Concurrency. `http/index.md` skips
   OpenAPI, MCP, and Electron. `core/index.md` skips IO, SettingStore, and Result.
6. **Labels don't match H1s.** Core vs "Core Utilities", HTTP vs "HTTP Framework", Reactive
   Streams vs Rx, MCP vs "Build Your Own MCP Server (stdio)". The UI sidebar has a "Getting
   Started" group (which clashes with the Guide's) and a "Components" page inside a
   "Components" group.

## Proposal (URLs unchanged)

One sidebar for the Guide and every module page, grouped the way `guide/index.md` already
groups features. The UI area and the Book keep their own sidebars.

```
Getting Started        Introduction · Installation · Design Principles · For AI Agents ·
                       Uni and the Scala Toolkit · All Modules (new /modules page)
Core                   Overview · Design · Logging · Type Introspection · Result ·
                       SettingStore · Utilities
Data & Serialization   Overview (/text/) · Weaver · JSON · MessagePack · YAML · Markdown · Cron
Files & Memory         IO · FileSystem · Canvas
Reactive Streams (Rx)  Overview · Basics · Operators · Concurrency
Control Flow           Overview · Retry · Circuit Breaker · Rate Limiter · Caching ·
                       Resource Management · Background Task
HTTP & RPC             Overview · Client · Server · Router · RPC · OpenAPI · SSE · WebSocket ·
                       HTTP Retry · MCP Server (stdio)
CLI                    Overview · Terminal Styling · Progress · Line Editing · Command Launcher
Testing & Build        UniTest · sbt-uni · sbt-uni-playwright · sbt-uni-crossproject ·
                       sbt-uni-javadoc
Web UI (Scala.js)      the UI pages (the /dom/ sidebar is unchanged apart from group names)
```

- **Nav:** Guide · Modules (`/modules`) · UI · Book · For AI Agents · Blog · GitHub, with
  `activeMatch` on each so the current area is always highlighted (Guide used to light up only
  on `/guide/` itself).
- **New `/modules` page:** one table of every module page under the same group names. It
  replaces `/core/` as the nav target, and `core/index.md` covers only Core.
- **Labels:** each sidebar label matches the page's H1 (shorten H1s where they are long).
  UI groups become Basics · Components · Building Blocks · Desktop, and `/dom/ui` is
  "Component Catalog".
- **Overview tables:** fix the ones listed in Problem 5.
- **Orphans:** link `/design/rx-concurrency` from `/rx/concurrency` (or move it to `adr/`).
  Leave `/uni-walkthrough` as the router page it already is.

Files stay where they are, so no links break. The cost is that the URL folder won't always
match the sidebar group (`/core/json` under Data Formats). Moving files to match is a separate,
optional step: GitHub Pages has no server-side redirects, so every old URL would need a stub
page.

## Verification

- `pnpm docs:build` (dead links), then check the sidebar on `/guide/`, `/core/design`,
  `/http/client`, `/dom/`, `/book/` in the dev server.
- Diff `llms.txt` before and after so no page drops out.
