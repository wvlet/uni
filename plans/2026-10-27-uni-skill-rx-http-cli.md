# Extend the uni skill: Rx, SSE, WebSocket, OpenAPI, Electron IPC, CLI

## Goals

- Agents that load the `uni` skill today get no guidance for Rx operators and
  concurrency primitives, SSE, WebSocket, OpenAPI generation, the Electron IPC
  transport, or the CLI module, so they fall back to recalled (often Airframe or
  wrong) APIs. Add short, verified references for these areas.
- Keep SKILL.md short: only new rows in the References table (and the canonical
  imports table where an entry point is missing).
- Keep `docs/guide/for-ai-agents.md` (source of truth) in sync, per AGENTS.md.

## Background

- #758 created the skill; #765 added RPC/HTTP/testing references, verifying each
  snippet in an uncommitted scratch test in `uni-netty/src/test` against a live
  `NettyServer` on port 0. The same method applies here.
- docs/ pages can be stale (follow-up 2 fixes them). Source wins; mismatches are
  reported in the PR, not fixed here.
- Follow-up 1 will add a CI check compiling reference snippets, so every snippet
  must be self-contained (imports included, no elided `...` bodies in code that
  is meant to compile).

## Design

Lane: **Express** — docs-only change to the skill and the AI-agent page; no
program logic or always-gate area.

New reference files under `plugins/uni/skills/uni/references/`:

| File | Content | Platforms |
|------|---------|-----------|
| `rx.md` | Rx sources, core operators, running/cancelling, completion semantics; concurrency primitives (`RxDeferred`, `RxFiber`, `RxSemaphore`, `RxBoundedQueue`, …) | per source |
| `streaming.md` | SSE client (`sendSSE` / handler) and server; WebSocket server routes and client | marked per API |
| `openapi.md` | Generating an OpenAPI spec from RPC/REST routers | per source |
| `electron.md` | Uni RPC over Electron IPC: main-process handler, preload bridge, renderer client | Scala.js |
| `cli.md` | Launcher (commands/options), Tint colors, progress bars | per source |

SSE and WebSocket are grouped (both are streaming HTTP and small). Each file:
one-line platform table, minimal runnable snippets, gotchas from the ADRs
(Rx completion/take semantics, SSE status handling, WebSocket Origin check and
connection caps, Electron renderer-is-async-only and sender checks).

Validation: each snippet compiled and, where it runs, executed in an
uncommitted scratch test (JVM; uni-netty test dir for server-side). JS-only
Electron snippets are compiled in a scratch Scala.js test where feasible.

Other edits: SKILL.md References rows; for-ai-agents.md canonical imports rows
for the new areas; bump plugin version 0.2.1 → 0.3.0 (new content area).

Out of scope: a separate `wvlet` query-language skill (noted as a follow-up).
