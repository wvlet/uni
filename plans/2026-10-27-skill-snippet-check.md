# Compile-check the uni skill's Markdown snippets

## Goals

- Stop the `uni` skill (`plugins/uni/skills/uni/SKILL.md`, `references/*.md`) from drifting away from the API. Its Scala snippets were verified once with an uncommitted scratch test; nothing re-checks them.
- Check the Markdown itself, not a hand-copied mirror (the `bookExamples` approach), so a snippet edit is checked by the same PR.
- Leave room for `docs/` pages later without redesigning; this PR covers only the skill.

## Background

- AGENTS.md requires skill snippets to compile, but only reviewers enforce it.
- `bookExamples` (`book-examples/`) compile-checks the book by hand-copying snippets into `ChNN*.scala`; the copy can drift from the Markdown.
- The snippets are fragments: top-level vals and expressions, `@main` methods, bare `test(...)` calls, sbt build snippets, `{ ... }` placeholders, and names defined in an earlier fence or never defined (`token`, `handler`, `x`, `y`).
- The JVM build sees `uni`, `uni-netty`, and `uni-test`, which covers every snippet except Scala Native/Scala.js-only servers and the sbt-uni generated client.

## Design

Lane: **Express** — test/CI infrastructure with no change to any published artifact or user-facing behaviour; touches no always-gate area.

- **New unpublished JVM project `docSnippets`** (`docs/snippets/`), depending on `uni.jvm`, `netty`, and `test.jvm % Test`, added to `jvmProjects` so the required `projectJVM/test` CI job compiles and runs it.
- **Source generator** `project/MarkdownSnippets.scala` (metabuild code) reads the configured Markdown files and writes one Scala file per Markdown file into `Test / sourceManaged`. A compile error in a snippet fails `docSnippets/Test/compile`; each fence is preceded by a `// <file>.md:<line>` comment so errors point back to the Markdown.
- **Scope model (mdoc-like):** the `scala` fences of a file form one chain, and each fence sees everything defined in earlier fences of the same file. A chain is emitted as `object ChainN extends Assertions, TestControl, PropertyCheck` (not `UniTest`, so the test framework never instantiates it and nothing in it runs), which lets top-level expressions, `@main`, and `shouldBe` fragments compile.
- **Fence modifiers** go in the info string after `scala`, which GitHub, VitePress, and skill readers all ignore for highlighting:
  - `scala sbt` — a build definition; not compiled.
  - `scala skip` — not compiled: `...` placeholders, Scala.js/Native-only code.
  - `scala test` — the body goes in a generated `class … extends UniTest` that imports the chain, so its `test(...)` calls **run** under `docSnippets/test`.
  - `scala reset` — start a new chain (for a fence that would clash with earlier names).
  - An unknown modifier fails the build, so a typo never silently skips a fence.
- **`package` fences** (e.g., `package example.api` in rpc.md) become their own compilation unit; later chains of the file import that package.
- **Stubs:** hand-written Scala in `docs/snippets/src/test/scala/snippets/<dir>/<file>/` in the same package as the generated code (`snippets.skill.references.http_client` …), so free names (`token`, `handler`, `x`, `y`) and the sbt-uni generated `example.client.UserServiceClient` resolve without imports or changes to the Markdown. The generated-client stub is built on `RPCClient`, so the testing.md RPC round-trip test really calls a live NettyServer.
- **Skill edits:** add `sbt`/`skip`/`test` markers where needed. In testing.md, rename the Design example's `UserService` (which had `create`) so it no longer collides with the RPC `UserService` two sections later — a reader who copies both sections hits the same clash.
- **AGENTS.md:** document the modifiers and the stub directory next to the existing skill-sync rule; `docSnippets/test` is the command to run.
- **Extending to docs/:** the project setting lists Markdown roots with their package prefix (`skill` → `plugins/uni/skills/uni`); adding `docs/guide` later is one entry plus stubs.

## Alternatives and Why Not?

- **mdoc** — an extra dependency and sbt plugin with no sbt 2.x release; Uni avoids third-party build deps.
- **Hand-copied test (like bookExamples)** — the copy is exactly what drifts.
- **Each fence in its own scope** — most files build on earlier fences (rpc.md, http-client.md), so every fence would need explicit sharing; chain-by-default matches how the references read.
- **Hidden setup blocks in the Markdown (HTML comments)** — skills are read raw, so hidden stubs cost tokens and add noise for the agent; keeping stubs in Scala keeps the skill clean.
