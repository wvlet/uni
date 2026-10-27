# Uni vs the Scala Toolkit comparison page

## Goals

- When someone (or their agent) asks "Scala Toolkit or Uni?" or "what should I use for X in Scala 3?", AI search and agents should find a fair, quotable answer on wvlet.org. The Scala Toolkit is the comparison target the user picked (see `plans/2026-10-27-ai-first-marketing.md`, track 1).
- Be fair. The Toolkit is official, has a larger community, and is the default for scripts and teaching. A one-sided page would not get cited.
- Every claim has been checked. Uni claims are checked against source (docs/AGENTS.md Rule #0), and Toolkit claims against Maven Central and docs.scala-lang.org, not memory.

## Background: the Scala Toolkit today (researched 2026-10-27)

- **What it is:** "a set of libraries to perform common programming tasks", described as supporting "Scala 3 and Scala 2 · JVM, Scala.js, and Scala Native" ([toolkit introduction](https://docs.scala-lang.org/toolkit/introduction.html)). It is published as `org.scala-lang::toolkit` and `org.scala-lang::toolkit-test` ([scala/toolkit](https://github.com/scala/toolkit)).
- **Current version: 0.9.2**, released 2026-04-08. Source: Maven Central [`toolkit_3/maven-metadata.xml`](https://repo1.maven.org/maven2/org/scala-lang/toolkit_3/maven-metadata.xml). There is also a `_2.13` build. The docs pages still print `0.7.0` in their sbt/Mill lines, and the scala/toolkit README still says `0.2.0`.
- **Install:** Scala CLI uses `//> using toolkit latest`. sbt uses `"org.scala-lang" %% "toolkit" % "<v>"`, with `toolkit-test` added under `% Test`. Mill uses `ivy"org.scala-lang::toolkit:<v>"` ([HTTP request page](https://docs.scala-lang.org/toolkit/http-client-request.html), [testing page](https://docs.scala-lang.org/toolkit/testing-suite.html)).
- **Modules in 0.9.2, from the published POMs:**

  | Module | Version | JVM | Scala.js | Native 0.5 |
  |---|---|---|---|---|
  | sttp client4 (`core`, `upickle`) | 4.0.21 | yes | yes | yes |
  | uPickle (JSON + MessagePack) | 4.4.3 | yes | yes | yes |
  | os-lib (files, processes) | 0.11.8 | yes | **no** (not in `toolkit_sjs1_3`) | yes |
  | MUnit (`toolkit-test`) | 1.2.1 | yes | yes | yes |

  POMs: [toolkit_3](https://repo1.maven.org/maven2/org/scala-lang/toolkit_3/0.9.2/toolkit_3-0.9.2.pom), [toolkit_sjs1_3](https://repo1.maven.org/maven2/org/scala-lang/toolkit_sjs1_3/0.9.2/toolkit_sjs1_3-0.9.2.pom), [toolkit_native0.5_3](https://repo1.maven.org/maven2/org/scala-lang/toolkit_native0.5_3/0.9.2/toolkit_native0.5_3-0.9.2.pom), [toolkit-test_3](https://repo1.maven.org/maven2/org/scala-lang/toolkit-test_3/0.9.2/toolkit-test_3-0.9.2.pom). uPickle describes itself as "JSON and Binary (MessagePack)" ([readme](https://github.com/com-lihaoyi/upickle)).
- **Cask (web server)** is covered in the Toolkit tutorials but is **not part of the artifact**. It is added as its own dependency, `com.lihaoyi::cask` ([web server intro](https://docs.scala-lang.org/toolkit/web-server-intro.html)), and it is JVM-only: Maven Central has `cask_3` 0.11.3 but no `_sjs1`/`_native` builds.
- **Not covered by the Toolkit:** object wiring, logging, Rx, RPC, MCP, and CLI argument parsing.
- **Verified with scala-cli 0.9.2 + Uni 2026.1.35 on Scala 3.9.0** (scratch project, not committed):
  - The Toolkit GET+uPickle snippet and the Uni GET+Weaver snippet both run against the GitHub API.
  - One case class can derive both `ReadWriter` and `Weaver`.
  - A munit `FunSuite` and a `UniTest` class run in the same `scala-cli test`.

## Design

**Lane: Express.** This is a docs page plus a 3-line change to the snippet generator. It touches no always-gate area. The prototype gate still runs because the page is new and user-facing.

- New page `docs/guide/uni-vs-scala-toolkit.md` with a `description` frontmatter. It contains:
  - An answer-first paragraph: the Toolkit is the official starter kit for scripts and teaching; Uni is one library for building applications (object wiring, logging, RPC, MCP, Rx) with the same API on all three platforms.
  - A decision table with these rows: object wiring and lifecycle, logging, JSON/MessagePack, HTTP client, HTTP server, RPC, MCP servers, Rx, CLI, file I/O, testing, platforms, and install.
  - "Choose the Scala Toolkit when…" and "Choose Uni when…" sections. The page concedes the Toolkit's strengths: it is official, has a bigger community, is used in courses, supports Scala 2, and offers one-line Scala CLI setup and os-lib's process API.
  - Side by side, in `::: code-group` tabs: (1) HTTP GET + JSON decode, (2) a unit test, (3) read/write a file.
  - "Using them together": they coexist (different packages, verified above). Use munit or UniTest; both run in one project.
- **Compile check:** Uni fences are compiled by `docSnippets`. Toolkit fences are marked `scala skip` and were checked with scala-cli instead.
  - `MarkdownSnippets` accepts a single Markdown file as a root. Today `Files.walk` on a file yields an empty relative path.
  - The parser ignores code-group `[Label]` tokens in fence info strings.
  - This page is added to `docSnippets` as `snippets.guide`.
- **Links:**
  - The `/guide/` and `/` sidebars ("Getting Started" group).
  - One question on the For AI Agents page ("Should I use the Scala Toolkit or Uni?").
  - llms.txt picks the page up from the sidebar automatically.
  - The top page has no comparison strip, so it is unchanged.
  - The `uni` skill gets one line under "when to use", plus a plugin version bump.

## Alternatives and Why Not?

- **Add `docs/guide` as a whole snippet root:** this would also compile `installation.md`, `for-ai-agents.md`, and other pages that were never written for the checker. That is scope creep for this PR.
- **Leave the page's Uni snippets unchecked:** this goes against the compile-check rule, and the cost of including them is three lines in the generator.
- **Compare against Li Haoyi's libraries individually:** the Toolkit is the bundle users actually choose between, and it is what the user asked for.
