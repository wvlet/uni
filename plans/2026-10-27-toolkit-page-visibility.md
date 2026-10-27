# Raise the visibility of "Uni and the Scala Toolkit"

## Goals

- Make [Uni and the Scala Toolkit](https://wvlet.org/uni/guide/uni-vs-scala-toolkit) easy to find for people, AI coding agents, and AI search, because "should I use the Toolkit or Uni?" is the first question a Scala 3 developer (or their agent) asks about Uni.
- Answer it as "how to choose", in a community tone: the Toolkit and its libraries (sttp, uPickle, os-lib, MUnit, Cask) are good choices that work alongside Uni.

## Background

- The page exists (`docs/guide/uni-vs-scala-toolkit.md`), is in both sidebars, and is compile-checked by `docSnippets` (its Toolkit fences are `skip`, because the build has no sttp or uPickle).
- `for-ai-agents.md` already has a "Should I use the Scala Toolkit or Uni?" section right after "When should I use Uni?"; the skill, `context7.json`, the top page, and the README do not mention the Toolkit.
- Facts re-checked today: Toolkit 0.9.2 (April 2026) is still the latest on Maven Central; it pins sttp client4 4.0.21, uPickle 4.4.3, os-lib 0.11.8. No version update needed.
- The AI share-of-voice metrics job has not merged, so the prompt suggestions go in the PR body only.

## Design

**Lane: Standard** — the original scope added a tutorial; docs only after the prototype gate; no always-gate area.

1. **Agent pointers ("how to choose")**
   - `for-ai-agents.md`: one sentence in "When should I use Uni?" pointing to the existing Toolkit section and the page (no duplicate paragraph).
   - `SKILL.md`: a short "Uni or the Scala Toolkit" section: Toolkit for scripts, teaching, Scala 2.13 / Scala 3 < 3.9, subprocesses (os-lib); Uni for services needing object wiring, logging, a server, RPC, MCP, or cross-platform code; both coexist. Bump plugin version 0.3.7 → 0.3.8.
   - `context7.json`: one rule along the same lines. `url` and `public_key` untouched.
2. **Comparison page visibility**
   - Description and intro mention the phrasings people use ("Scala Toolkit or Uni", "what to use after the Scala Toolkit", "Toolkit with object wiring and logging"); headings stay "Choose … when".
   - New `## FAQ` with question headings: Can I use the Toolkit and Uni in the same project? / What should I use when my Toolkit script grows into a service? / Does the Toolkit include object wiring or logging? / Does Uni work with Scala 2.13? / Is Uni part of the Toolkit?
   - Verify the page is in `sitemap.xml` and `llms.txt` after `pnpm docs:build`. Add a top-page hero button ("Using the Scala Toolkit?") and a README "Documentation" line.
3. **PR body:** 2–3 share-of-voice prompts, e.g. "I outgrew my Scala Toolkit script; what should I use for a Scala 3 service with object wiring and logging?", "Can I use the Scala Toolkit together with another Scala 3 library for HTTP servers and RPC?".

## Alternatives and Why Not?

- **A step-by-step tutorial page with a CI-tested `examples/toolkit-service` project.** Built and previewed, then dropped at the prototype gate as unnecessary: the comparison page and its FAQ already answer the question, and the page and example added length and third-party build dependencies without much new information.
- **Un-skip the Toolkit fences on the comparison page.** Their code groups define the same names twice and would need os-lib and MUnit too; left as is.
