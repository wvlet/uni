# Docs Top Page for People, AI Agents, and AI Search

## Goals

- Make https://wvlet.org/uni/ answer "what is Uni, is it for me, how do I start" in the first screen, for a human skimming it and for an AI answer engine quoting it.
- Convey the positioning honestly: the whole Scala 3 stack in one dependency, the same API on JVM / Scala.js / Scala Native, the Scala 3 successor of Airframe, used by Wvlet, and agent-ready docs. No "fastest/best" claims.
- Keep the hero, `description`, OG/Twitter, JSON-LD, `llms.txt` intro, and README opening on one tagline so search engines and models see one consistent sentence.

## Background

- Part of the AI-first campaign (`plans/2026-10-27-ai-first-marketing.md`). Uni has ~17 stars, while Airframe (665) is in training data; the top page must connect the two.
- The current page leads with "Essential Scala Utilities", lists five cards (no DI/JSON/RPC/MCP cards, no platform scope), and has no `description` frontmatter.
- `docs/.vitepress/theme/HomeHeroCode.vue` and `CustomHome.vue` exist but are not wired in (`theme/index.ts` only extends the default theme), so the hero shows the logo only.
- `vitepress-plugin-llms` defaults to `excludeIndexPage: true`: the home page has no `/uni/index.md` twin and is not in `llms-full.txt`. The `llms.txt` intro comes from `description`/`details` in `config.mts`, not from the hero.
- Verified facts:
  - Wvlet's `build.sbt` (`/Users/leo/work/wvlet`) depends on `uni`, `uni-test`, and `uni-netty`.
  - `uni` and `uni-test` are cross-built for JVM, JS, and Native. `uni-netty` (the Netty HTTP server) is JVM-only; `uni` itself ships `NodeServer` (Scala.js) and `NativeServer` (Scala Native). RPC client, MCP stdio server, and HTTP client live in shared sources. `wvlet.uni.dom` is Scala.js-only. The Scala Native HTTP client needs libcurl.
  - The `uni` Claude Code plugin has merged (README and the For AI Agents page document `/plugin install uni@uni`), so the page can link it.

## Design

**Lane: Standard.** The page structure changes (new hero code panel, new sections), and the user asked to confirm the direction first.

1. **Tagline** (one sentence, reused everywhere):
   "Uni is a Scala 3 library that gives you dependency injection, logging, JSON/MessagePack, Rx, HTTP client/server, RPC, MCP servers, and testing in one dependency, with the same API on JVM, Scala.js, and Scala Native."
   - Hero: `name: Uni`, `text: One Scala 3 library for the whole stack`, `tagline:` the sentence above, shortened, plus "The Scala 3 successor of Airframe."
   - Actions: Get Started · For AI Agents · Coming from Airframe? (→ migration table) · GitHub.
2. **Hero image**: keep the logo (user decision). The install snippet and a ~10-line runnable example (Design + LogSupport + `derives Weaver`) go in the Markdown body directly under the hero. The unused `HomeHeroCode.vue` stays untouched.
3. **Feature cards** (8, each linked, with a platform note in `details`):
   Design (DI) · Logging · Weaver JSON/MessagePack · Rx · HTTP client (all; Native needs libcurl) · HTTP server & RPC (servers on all three — Netty via `uni-netty` on the JVM, Node.js, native sockets; annotation router and RPC server JVM-only; RPC clients everywhere) · MCP servers (stdio, all three) · UniTest. CLI and Web UI (Scala.js only) are moved into the modules table to keep the grid even.
4. **Markdown body** (what agents and the `.md` twin see), answer-first:
   - One-paragraph "What is Uni?" with the tagline.
   - Install (`__UNI_VERSION__`, sbt and Scala CLI) and the same 10-line example.
   - **Why one library?** Three or four bullets: one version, one set of conventions, one dependency tree across platforms. Fairness line: "If you already use sttp, circe, ZIO, or MacWire and they serve you well, Uni does not ask you to switch; it fits best when you want them all from one place, especially on Scala Native or Scala.js."
   - **Used by Wvlet**: one line, linked.
   - **For AI agents**: llms.txt, llms-full.txt, For AI Agents page, Context7, the Claude Code plugin install.
   - Modules table with a Platforms column (`uni`, `uni-netty`, `uni-test`).
5. **Metadata**: `description` frontmatter = the tagline. In `config.mts`, update site `description`, OG/Twitter title/description, JSON-LD `description`, and the llms plugin `description` to the tagline; set `excludeIndexPage: false` so `/uni/index.md` is published and the home page is included in `llms-full.txt` (the plugin appends it last). An `<llm-only># Uni</llm-only>` heading gives the Markdown twin an H1 without rendering on the site.
6. **README**: replace the "Essential Scala Utilities" line with the tagline plus "The Scala 3 successor of Airframe." Small edit only.
7. **Verification**: `pnpm docs:build` (dead links), inspect `dist/index.md`, `llms.txt`, `llms-full.txt` head; compile the hero example with scala-cli against the released version; prototype via `pnpm docs:preview`.

## Alternatives and Why Not?

- **Replace the logo with the unused `HomeHeroCode.vue` code panel.** Shows "how" in the first screen on wide screens, but the user preferred keeping the logo; the body snippet right under the hero covers it.
- **A full comparison table against sttp/circe/ZIO/MacWire.** Feature tables drift and read as attacks; one fair sentence is enough on a home page.
- **Keep `excludeIndexPage: true`.** Then the home page's answer-first text never reaches agents that read `llms-full.txt` or `index.md`; including it costs about 60 lines.
