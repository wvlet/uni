# Remove uni-agent-bedrock and uni-agent; preserve ops as a util

Date: 2026-06-20
Branch: `chore/remove-agent-bedrock` (worktree: buzzing-fluttering-crystal)

## Motivation

Bedrock work is moving to Mantle and the Converse(Stream) API's importance has
dropped, so the `uni-agent-bedrock` AWS integration is no longer worth carrying.
The `uni-agent` module (chat/LLM/tool abstractions) existed only to back the
Bedrock integration — Bedrock is its sole consumer — so it goes too. The one
piece worth keeping is `ops.scala` (`pipe`/`ifDefined`/`when`), which are
generic builder/util helpers; it moves into the main `uni` library.

## Scope / dependency facts

- `uni-agent-bedrock` (718 LOC main) — depends on `uni-agent`; depended on only
  by `uni-integration-test`.
- `uni-agent` (1308 LOC main) — depends on `uni.jvm`; depended on only by
  `uni-agent-bedrock`. Nothing inside `uni-agent` uses `ops` itself.
- `uni-integration-test` — exists solely to run `BedrockIntegrationTest`.
- `AWS_SDK_VERSION` + langchain4j deps are Bedrock-only.
- `DomRenderer.pipe` in `uni` is stdlib `scala.util.chaining`, unrelated to `ops`.

## Decisions (confirmed with user)

1. Remove the **entire** `uni-agent` module, not just Bedrock. Abstractions are
   recoverable from git history if a future provider needs them.
2. Move `ops.scala` to `wvlet.uni.util` (per repo convention: utilities live in
   `wvlet.uni.util`, not root), keeping all three helpers (`pipe`, `ifDefined`,
   `when`). `pipe` overlaps stdlib `scala.util.chaining.pipe` but is kept for a
   self-contained util surface.

## Changes

### Code
- Add `uni/src/main/scala/wvlet/uni/util/ops.scala` — package `wvlet.uni.util`,
  copy of the current `ops` object (pipe/ifDefined/when).
- Add `uni/src/test/scala/wvlet/uni/util/OpsTest.scala` — coverage for the three
  helpers (none existed before; it was only exercised indirectly via Bedrock).
- Delete directories: `uni-agent/`, `uni-agent-bedrock/`, `uni-integration-test/`.

### build.sbt
- Remove `agent`, `bedrock` from `jvmProjects`.
- Remove `lazy val agent`, `lazy val bedrock`, `lazy val integrationTest`.
- Remove `val AWS_SDK_VERSION` and `val AIRFRAME_VERSION` — both Bedrock/agent-only
  (the airframe + airframe-codec deps lived only on the `agent` module).
  Note: `project/plugin.sbt` keeps its own independent `AIRFRAME_VERSION`.

### CI (.github/workflows/test.yml)
- Remove the `test_integration` job entirely — it existed only to run
  `integrationTest/test` (the Bedrock AWS integration test). Nothing else
  `needs` it.

### docs
- Delete `docs/agent/*` (index, llm-agent, chat-session, tools, bedrock) and
  `docs/book/ch11-00-agent.md`.
- **Book renumber.** The agent chapter was Part VII / Chapter 11, sitting between
  Cross-Platform (Part VI, ch10) and Testing (Part VIII, ch12). Deleting it would
  leave a numbering gap *and* dead nav links. So Testing was renumbered down:
  Part VIII → Part VII, Chapter 12 → Chapter 11, and `ch12-00-testing.md` →
  `ch11-00-testing.md`. Updated all cross-references: the prev/next footers in
  ch10, ch11-testing, and appendix-a, plus "Chapter 12" prose mentions in ch02
  and ch03. Verified with `pnpm docs:build` (fails on dead links).
- `docs/.vitepress/config.mts` — remove the two "Agent Framework" sidebar blocks
  (both `/guide/` and `/` sidebars) and renumber the book Part entries.
- `docs/index.md` — drop `uni-agent` / `uni-bedrock` module rows.
- `docs/guide/index.md` — drop the "uni-agent — LLM Agent Framework" section.
- `docs/guide/installation.md` — drop the uni-agent / uni-bedrock dependency snippets.
- `docs/book/index.md` — drop the `/agent/` reference and renumber Part VII/VIII.
- `docs/book/CLAUDE.md` — drop `/agent/` from the reference-areas list.

### CLAUDE.md
- Remove the `uni-agent` module bullet.
- Replace the `agent/testOnly` example with a `coreJVM/testOnly` one and remove
  the `integrationTest/test` command line.

## Result
- 62 files changed, ~+138 / −5225 lines.
- All platforms compile (JVM/JS/Native); `projectJVM/test` green; `pnpm docs:build`
  succeeds with no dead links.
- Remaining `uni-agent` / `bedrock` string hits live only in historical
  `plans/*.md` (left as-is) and `project/plugin.sbt`'s own `AIRFRAME_VERSION`.

## Note for repo admin
If "Integration test" / "Test Report Integration" was a *required* status check in
branch protection, it must be de-listed there — a required check that no longer
runs will otherwise block merges.

## Verification
- `./sbt compile` (all platforms) and `./sbt projectJVM/test`.
- `./sbt scalafmtAll` (CI checks formatting).
- `pnpm docs:build` to confirm no dangling doc links.
- Confirm `grep -rn "wvlet.uni.agent\|uni-bedrock\|AWS_SDK_VERSION"` returns nothing.

## Out of scope
- No new provider backend (Mantle/Anthropic) is added here — pure removal.
