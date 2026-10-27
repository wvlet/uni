# AGENTS.md

## Project Overview

**Uni** is an essential Scala utilities, refined for Scala 3 with minimal dependencies.

## Modules

- **uni-core**: Pure-Scala essential libraries shared with uni-test
- **uni**: Main library collection (logging, DI, JSON/MessagePack, RPC/HTTP)
- **uni-test**: Unit testing framework

The build runs on **sbt 2.x** (Scala 3 metabuild). Cross-platform — JVM, Scala.js, Scala Native — via uni's own [`sbt-uni-crossproject`](sbt-uni-crossproject/) plugin (`crossProject`, `CrossType.Pure`), which replaced the unported portable-scala plugins. Platform-specific code in `.jvm`, `.js`, `.native` folders.

Note (sbt 2.x): use `%%` (not the old `%%%`) for Scala.js/Native deps, and wrap a non-serializable `Test / jsEnv` in `Def.uncached(...)`.

## Commands

```bash
./sbt compile                              # Compile all
./sbt test                                 # Test all
./sbt coreJVM/test                         # Test specific module
./sbt "coreJVM/testOnly *DesignTest"       # Test specific class
./sbt "coreJVM/testOnly * -- -l debug"     # With debug logging
./sbt scalafmtAll                          # Format (CI checks this)
pnpm docs:dev                              # Start docs server (http://localhost:5173)
```

## Testing (UniTest)

Avoid mocks. Use `shouldBe`, `shouldNotBe`, `shouldContain`, `shouldMatch`.

```scala
// Comparison operators
(value >= 1) shouldBe true  // NOT: should be >= 1

// Type checking
result shouldMatch { case x: ExpectedType => }  // NOT: .asInstanceOf[X]
```

See `.github/instructions/unitest.instructions.md` for more.

## Coding Style

- Scala 3 syntax only
- Omit `new`: `StringBuilder()` not `new StringBuilder()`
- String interpolation: always use `${...}` with brackets
- Avoid `Try[A]` return types
- Config classes: `withXXX(...)` for all fields, `noXXX()` for optional fields
- uni: minimal dependencies only
- Terminology: call `Design` "object wiring", never "dependency injection" or "DI", in code, docs, and metadata. The DI label pulls in debates from legacy Java DI frameworks that don't apply to Uni.

## Git Workflow

Save plan documents to plans/YYYY-MM-DD-(topic).md files

### Branches and PRs
- Never push directly to main. All changes require PRs.
- Create branch FIRST: `git switch -c <prefix>/<description>`
- Prefixes: `feature/`, `fix/`, `chore/`, `deps/`, `docs/`, `test/`, `breaking/`
- Use `gh` for PR management
- Merge with: `gh pr merge --squash --auto` (branch protection requires `--auto`)
- Never enable auto-merge without user approval

### Commits
- Focus on "why" not "what"
- Example: `feature: Add XXX to improve user experience`

## AI-agent docs

[`docs/guide/for-ai-agents.md`](docs/guide/for-ai-agents.md) lists canonical imports, entry points, and an Airframe→Uni table that AI agents rely on. When a change renames, moves, or removes a public API, package, or artifact, update that page in the same PR. See `docs/AGENTS.md` for details.

The Claude Code plugin in [`plugins/uni/`](plugins/uni/) (listed by [`.claude-plugin/marketplace.json`](.claude-plugin/marketplace.json)) ships a `uni` skill that is a digest of that page. When you change the page or an API it lists, update `plugins/uni/skills/uni/` in the same PR, verify every snippet compiles (see below), and bump `version` in `plugins/uni/.claude-plugin/plugin.json` so installed copies update. Check with `claude plugin validate .` and `claude plugin validate plugins/uni`.

The `docSnippets` project (`./sbt docSnippets/test`, part of `projectJVM/test` in CI) compiles every `scala` fence of the skill's Markdown in place. The fences of one file form a chain, so a fence sees what earlier fences defined. Mark a fence in its info string when it should not be compiled as-is:

- `` ```scala sbt `` — build definitions (`build.sbt`, `project/plugins.sbt`)
- `` ```scala skip `` — `...` placeholders, Scala.js- or Native-only code
- `` ```scala test `` — bare `test(...)` calls; the fence runs inside a generated `UniTest` class
- `` ```scala reset `` — start a new chain when names would clash with earlier fences

Names a snippet uses without defining (`token`, a generated client) go in stubs under `docs/snippets/src/test/scala/`, in the package of the Markdown file (`snippets.skill.references.http_client` for `references/http-client.md`). See [`docs/snippets/README.md`](docs/snippets/README.md).

## Architecture decisions

Key design decisions are recorded in [`adr/`](adr/) — see [`adr/README.md`](adr/README.md) for the index of what each ADR covers and when to read it. Consult the relevant ADR before modifying the area it covers; add a new ADR (`adr/YYYY-MM-DD-(topic).md`) and index entry when making a significant design decision.
