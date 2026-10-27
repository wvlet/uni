# Uni Agent Skill as a Claude Code Plugin

## Goals

- Make AI coding agents write idiomatic Uni code on the first try, even when the model's training data predates Uni or confuses it with Airframe.
- Ship it as track 2 of [AI-first marketing](2026-10-27-ai-first-marketing.md): an Agent Skill installable as a Claude Code plugin straight from this repo, so `wvlet/uni` itself is the marketplace.
- Keep maintenance near zero: the skill is a short digest of `docs/guide/for-ai-agents.md`, and points to `llms-full.txt` for everything else.

## Background

- PR #755 added `docs/guide/for-ai-agents.md` (canonical imports, Airframe→Uni table, common mistakes) and the generated `llms.txt` / `llms-full.txt`. Those help an agent that goes looking. A skill helps an agent that does not: Claude Code loads its description into every session and pulls the body in when the task matches.
- Claude Code plugin format (checked against code.claude.com docs: plugins/create-marketplace, marketplace-reference, manifest-reference, skills):
  - `.claude-plugin/marketplace.json` at the repo root: `name`, `owner.name`, `plugins[]` with `name` and `source`. A relative source must start with `./`.
  - A plugin dir with an optional `.claude-plugin/plugin.json` (`name` required) and `skills/<name>/SKILL.md`.
  - SKILL.md frontmatter: `name`, `description` (description + `when_to_use` truncated at 1,536 chars, so lead with the trigger). Supporting files are linked from SKILL.md.
  - `claude plugin validate <dir>` checks both the marketplace and the plugin.
- `docs/AGENTS.md` Rule #0: every API claim is verified against source. The SKILL.md is held to the same rule.

## Design

**Lane: Express** — docs and static plugin metadata only; no Scala code, build, or always-gate area changes. The direction is fully specified in the request.

Layout:

```
.claude-plugin/marketplace.json        # marketplace "uni", one plugin
plugins/uni/.claude-plugin/plugin.json # name, description, author, homepage, license
plugins/uni/skills/uni/SKILL.md        # the skill (short)
plugins/uni/skills/uni/references/
  snippets.md                          # verified snippets: DI, logging, JSON, HTTP client, MCP, Retry, UniTest
  airframe-migration.md                # Airframe → Uni table
```

Install: `/plugin marketplace add wvlet/uni`, then `/plugin install uni@uni`.

SKILL.md content:
- `description`: triggers first — Scala 3 project, `build.sbt` mentions `org.wvlet.uni`, imports `wvlet.uni.*`, or the user asks for DI / logging / JSON / MessagePack / HTTP / RPC / MCP / testing in Scala, or migrating from Airframe.
- sbt setup (no hardcoded version: tell the agent to take the latest from Maven Central or the user's build).
- Canonical imports table (same rows as the docs page).
- Mistakes to avoid (docs page list + repo style rules that apply to users: Scala 3 syntax, no ScalaTest matchers, `derives Weaver`, no second DI/logging/JSON lib, `%%` vs `%%%`).
- Pointers: `references/*.md`, `https://wvlet.org/uni/llms-full.txt`, per-page `.md` URLs.

Single source of truth:
- `docs/guide/for-ai-agents.md` stays canonical. SKILL.md and references are a digest of it.
- Add a rule to root `AGENTS.md` (and `docs/AGENTS.md` AI-agent section): a change to the docs page, or to any API it lists, updates `plugins/uni/skills/uni/` in the same PR. Bump `plugin.json` `version` when the skill changes so installed copies update.

Docs:
- New "Install the Uni skill" section on the For AI Agents page.
- README: one line next to the existing llms.txt pointer.

Verification:
- Grep every symbol in SKILL.md and references against `uni/`, `uni-core/`, `uni-test/` sources.
- Compile the snippets in a scratch sbt test (or an existing test module) where practical.
- `claude plugin validate .` and `claude plugin validate plugins/uni`.

## Alternatives and Why Not?

- **Symlink the docs page into the skill.** Plugins are copied into a cache on install; links outside the plugin dir break. The page also uses `__UNI_VERSION__` placeholders and site-relative links that make no sense inside a skill.
- **Generate SKILL.md from the docs page at build time.** Adds a generator and a CI check for ~80 lines of text. Revisit if the two drift in practice.
- **Cursor / Copilot rule files.** Each would be another copy of the same text in a different format. Skipped; `llms.txt` already serves agents without a plugin system.
- **Separate `wvlet/agent-skills` marketplace repo.** Better once a `wvlet` skill exists; for now one repo keeps the skill next to the code it describes.
