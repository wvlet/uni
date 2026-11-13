# Make the UI stack readable by agents

## Goals
- Agents that build Scala.js UIs with Uni get the same quality of guidance as agents doing HTTP or RPC work: a skill reference page, plus examples that compile.
- This is step 1 of [2026-11-13-ui-sdk-learnings.md](2026-11-13-ui-sdk-learnings.md).

## Background
- The `uni` skill describes all of uni-ui in one long table row. It has no `references/ui.md`, so agents read raw sources or guess.
- `docSnippets` compiles Markdown fences against `uni.jvm` only. UI fences in the skill are marked `skip`, and `docs/dom/*.md` (34 fences) is never compiled, so those examples can drift from the API.
- `llms.txt` / `llms-full.txt` cover the whole site. An agent doing UI work loads everything.

## Design
Lane: Express. The change is docs plus a test-only build project. No library code or public API changes.
- **`references/ui.md` in the skill:** covers
  - the uni-dom basics (`RxElement`, `renderTo`, `each`/`eachRx`);
  - the uni-ui catalog, one builder example per component group;
  - the `--uni-*` tokens and the `data-state` hooks;
  - CSS delivery;
  - testing with `DomTestSupport`.

  `SKILL.md` keeps a one-line row that links to it.
- **`docSnippetsJS`:** a new Scala.js test project, aggregated into `projectJS`.
  - It runs the same `MarkdownSnippets` generator on `references/ui.md`, `docs/dom/index.md` and `docs/dom/ui.md`, compiled against `uni.js` and `ui`.
  - The JVM `docSnippets` excludes `references/ui.md`.
  - JS snippets are compiled, not run. A `test` fence would need a browser.
- **Fix fences that fail to compile:** fix them, or mark them `skip` when they are placeholders.
- **Split `llms.txt` by area:** use the `vitepress-plugin-llms` subdirectory output, if the installed version supports it, so `/uni/dom/llms.txt` and similar exist. If it doesn't, leave it for a follow-up.
- Bump the plugin version, and add the `ui` keyword.

## Alternatives and Why Not?
- **Compile JS fences inside the JVM project with stubs:** this would need fake DOM types and would prove nothing.
- **A `js` fence modifier:** whole files are JS-only, so choosing roots per file is simpler.
