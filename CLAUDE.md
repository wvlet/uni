# CLAUDE.md

## Project Overview

**Uni** is an essential Scala utilities, refined for Scala 3 with minimal dependencies.

## Modules

- **uni-core**: Pure-Scala essential libraries shared with uni-test
- **uni**: Main library collection (logging, DI, JSON/MessagePack, RPC/HTTP)
- **uni-test**: Unit testing framework
- **uni-agent**: LLM agent interface, orchestration, and tool integration

Cross-platform: JVM, Scala.js, Scala Native via sbt-crossproject. Platform-specific code in `.jvm`, `.js`, `.native` folders.

## Commands

```bash
./sbt compile                              # Compile all
./sbt test                                 # Test all
./sbt coreJVM/test                         # Test specific module
./sbt "agent/testOnly *LLMAgentTest"       # Test specific class
./sbt "coreJVM/testOnly * -- -l debug"     # With debug logging
./sbt scalafmtAll                          # Format (CI checks this)
./sbt integrationTest/test                 # Integration tests (requires AWS creds)
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

### Code Reviews
Gemini reviews PRs. Address feedback before merging.

## Architecture decisions

- [`adr/2026-05-04-fromsurfaceopt-and-innerweavers.md`](adr/2026-05-04-fromsurfaceopt-and-innerweavers.md) — `Weaver.fromSurfaceOpt` + `Weaver.innerWeavers` for detecting lossy fallbacks anywhere in a derived weaver tree. Read before adding new composite weaver classes.
