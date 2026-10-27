# AI-First Technology Marketing for Uni and Wvlet

## Goals

- Grow adoption of Uni and Wvlet given that the **primary consumer is an AI coding agent**, not a human browsing GitHub. An agent picks a library when (a) its model already knows the library from training data, or (b) it finds the library through search/tools at task time and can use it correctly on the first try.
- Decide which campaign tracks to run, in what order, and which one this PR ships.
- Keep every artifact cheap to maintain: generated from the docs we already write, not a second copy that rots.

## Background

- Visibility today: `wvlet/uni` has **17 stars and no GitHub topics**; `wvlet/wvlet` has **158 stars** (topics: sql, duckdb, trino, dbt, query-language). Web search for "Scala 3 DI / JSON / HTTP client" does not surface Uni.
- Uni is the Scala 3 successor of Airframe (well known in training data). Models that know Airframe do not yet connect it to Uni, so they keep suggesting Airframe or other libraries.
- Assets we already have that are unusually agent-friendly: a VitePress site at wvlet.org/uni, an `uni.mcp` module that turns a trait into an MCP server, cross-platform Scala Native binaries, and a repo already developed mostly by agents (CLAUDE.md, ADRs, `.github/instructions`).
- How agents discover libraries in 2026: training corpora (GitHub READMEs, blogs, Stack Overflow/Reddit, Scaladex, Maven Central), live web search, `llms.txt`/Markdown docs fetched by agent tools, Agent Skills / plugin marketplaces, and MCP server registries. Stars still matter as a ranking signal for search and for human gatekeepers who approve dependencies.

## Design

**Lane: Standard** — strategy and scope need the user's direction; this PR only touches docs/build (no always-gate area).

### Campaign tracks (ordered by leverage per effort)

1. **Machine-readable docs (this PR).**
   - Generate `llms.txt` (index) and `llms-full.txt` (all pages concatenated) at docs build, plus a raw `.md` twin for each page, via `vitepress-plugin-llms`. Agents fetch one URL and get the whole API surface with code samples.
   - Add a "Uni for AI agents" page: when to pick Uni, one copy-paste snippet per module, Airframe→Uni migration table (captures "airframe scala 3" intent), and common mistakes agents make (`%%%` vs `%%`, `new`, `Try`).
   - **Optimize wvlet.org for AI search** (ChatGPT search, Perplexity, Claude web search, Google AI Overviews):
     - `robots.txt` that explicitly allows AI crawlers (GPTBot, OAI-SearchBot, ClaudeBot, Claude-SearchBot, PerplexityBot, Google-Extended) and points to the sitemap.
     - `sitemap.xml` with `lastmod` (VitePress `sitemap.hostname`), so crawlers revisit changed pages.
     - Per-page `<meta description>`, canonical URL, Open Graph tags, and JSON-LD (`SoftwareSourceCode` / `SoftwareApplication` with repo URL, Maven coordinates, license, version).
     - Answer-first page openings: each guide page starts with a one-paragraph "what this is / when to use it" plus a runnable snippet. AI search engines quote those first paragraphs.
     - Question-shaped headings matching real queries ("How do I do dependency injection in Scala 3?") on the agent page and FAQ.
   - Add GitHub topics (`scala3`, `scala-js`, `scala-native`, `dependency-injection`, `json`, `msgpack`, `http-client`, `mcp`, `testing`, `airframe`) and a keyword-rich description. (Repo settings, done with `gh`, not in the diff.)
2. **Agent Skill + plugin (follow-up PR).** Publish a `uni` Agent Skill (`SKILL.md` + references pulled from `llms-full.txt`) installable as a Claude Code plugin from this repo's marketplace manifest. When a user's agent works in a Scala project, the skill makes it write idiomatic Uni code. Same pattern for Wvlet (`wvlet` skill: write/convert queries).
3. **MCP presence (follow-up).** Ship small, useful MCP servers built *with* Uni (e.g., `wvlet-mcp`: run Wvlet/SQL on DuckDB, explain a query) as Scala Native binaries, and list them in the MCP registry, Smithery, and awesome-mcp lists. Each listing is a backlink and a showcase for `uni.mcp`.
4. **Wvlet positioning: "the query language agents edit well."** Flow-style queries are append-only and line-local, so an agent can add a step without rewriting nested SQL. Back this with a small public benchmark (same tasks, SQL vs Wvlet, success rate per model) and a blog post. Benchmarks get cited by humans and ingested by models.
5. **Training-data and search footprint (ongoing).** One technical post per month (dev.to / Medium / Zenn / wvlet.org blog), cross-posted to r/scala, Scala Times, This Week in Scala; Scaladex entry check; answer existing Stack Overflow / Reddit questions where Uni fits; conference talk (Scala Days, ScalaMatsuri) on "a Scala library designed for agents".
6. **Measure.** Monthly: stars, Maven Central downloads, docs traffic from AI referrers, and an "AI share of voice" check — ask 3–4 models a fixed set of prompts ("best Scala 3 DI library", "flow-style SQL language") and record whether Uni/Wvlet appears.

### This PR's scope

- Add `vitepress-plugin-llms` to the docs build; verify `llms.txt`, `llms-full.txt` and per-page `.md` appear in `docs/.vitepress/dist`.
- Enable the sitemap (with `lastmod` from full git history in CI), and add site-wide meta, canonical, OG, and JSON-LD in `docs/.vitepress/config.mts`. Add `description` frontmatter to the main guide pages.
- Add `docs/guide/for-ai-agents.md` (answer-first, with FAQ-style headings) and link it from the nav and README.
- README: one line pointing agents to `https://wvlet.org/uni/llms.txt`.
- `robots.txt` is only read at the host root, which is served by `wvlet/wvlet.github.io`, so it cannot be added here. That host currently returns 404 for it, which means all crawlers are already allowed. A follow-up there adds an explicit AI-crawler allowlist and `Sitemap:` lines for `/uni/` and `/wvlet/`.
- Commit this plan. Topics/description updates are applied with `gh repo edit` after merge approval.

## Alternatives and Why Not?

- **Classic star campaigns (Hacker News launch, Product Hunt).** One-day spikes; worth doing once track 1–2 exist so visitors land on agent-ready docs, not first.
- **Hand-written `llms.txt`.** Drifts from docs within weeks; generation keeps it in sync for free.
- **Paid promotion / SEO content farms.** Low credibility in the Scala community and low-quality training signal.
- **One combined Uni+Wvlet site.** The audiences differ (library users vs. query authors); cross-link instead.
