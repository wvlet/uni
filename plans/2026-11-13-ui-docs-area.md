# UI docs area

## Goals

- Give the UI stack (uni-dom + uni-ui) its own top-level docs area, reachable from the top nav.
- Split the 500-line "UI Components" page into pages a reader (or agent) can load one topic at a time.
- Document testing and `checkPattern` in one place.

## Background

- Today: one sidebar group "Web UI (Scala.js)" with `/dom/` (RxElement), `/dom/ui` (everything uni-ui), `/dom/behaviors`.
- `/dom/llms.txt` and `/dom/llms-full.txt` are generated per directory (llms plugin `depth: 2`), and `addSectionMainPages` adds `dom/index.md` to them.
- `docSnippetsJS` compiles every `scala` fence under `docs/dom/`, one chain and one stub package per file.
- The site is static (GitHub Pages), so a moved page can't get a server redirect.

## Design

Lane: **Express** — docs only, no always-gate area; user approved Express with auto-merge.

- Keep every page flat under `docs/dom/`, so the existing `/dom/` llms section covers the whole UI area and no URL changes directory.
- Pages (sidebar order): Overview `/dom/`, RxElement & HTML DSL `/dom/rxelement`, Components `/dom/ui` (hub), Controls, Overlays, Collections, Dock & Layout, Behaviors, Changes & Parts, Agents, Theming, Testing, then the Electron guide and tutorial.
- `/dom/ui` stays as the Components hub (conventions + a table of the four component pages), so old links still land on the right topic.
- Old anchors (`/dom/#testing-components`, `/dom/ui#agents`, `/dom/ui#checking-aria-patterns`, ...) get a small client-side hash redirect in the theme to their new page.
- The Electron pages stay at `/http/electron*`; their sidebar entries move from HTTP into the UI group.
- Add "UI" to the top nav and update the module tables (guide intro, home page) and in-repo links (`for-ai-agents.md`, skill `references/*.md`, sbt-uni, electron).
- Content: move sections as-is, dedupe setup and testing, add the uni-harness MCP bridge to Agents; no prose rewrite.

## Alternatives and Why Not?

- Subdirectory `/ui/` with redirect stubs: breaks `/dom/llms.txt` and needs HTML redirect pages; flat `/dom/` avoids both.
- Keep `/dom/ui` as one page: still 500+ lines mixing six topics.
