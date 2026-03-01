# CLAUDE.md

## Project Overview

**Uni** is an essential Scala utilities, refined for Scala 3 with minimal dependencies.

## Modules

- **uni-core**: Pure-Scala essential libraries shared with uni-test (cross-platform)
- **uni**: Main library collection — logging, DI, JSON/MessagePack, RPC/HTTP (cross-platform)
- **uni-test**: Unit testing framework (cross-platform)
- **uni-agent**: LLM agent interface, orchestration, and tool integration (JVM only)
- **uni-agent-bedrock**: AWS Bedrock integration (JVM only)
- **uni-netty**: Netty-based HTTP server (JVM only)

Cross-platform: JVM, Scala.js, Scala Native via sbt-crossproject. Platform-specific code in `.jvm`, `.js`, `.native` folders.

### Deeper Documentation

| Topic | Location |
|---|---|
| Module dependency graph & layering rules | `.github/instructions/architecture.instructions.md` |
| Scala 3 coding conventions & pitfalls | `.github/instructions/scala3.instructions.md` |
| UniTest assertion patterns | `.github/instructions/unitest.instructions.md` |
| Agent module guide | `.github/instructions/agent-module.instructions.md` |
| HTTP module guide | `.github/instructions/http-module.instructions.md` |
| Design principles | `docs/guide/principles.md` |
| Code review criteria | `.github/prompts/review.prompt.md` |
| Feature plans | `plans/YYYY-MM-DD-(topic).md` |

## Commands

```bash
./sbt compile                              # Compile all
./sbt test                                 # Test all
./sbt coreJVM/test                         # Test specific module
./sbt "agent/testOnly *LLMAgentTest"       # Test specific class
./sbt "coreJVM/testOnly * -- -l debug"     # With debug logging
./sbt scalafmtAll                          # Format (CI checks this)
./sbt integrationTest/test                 # Integration tests (requires AWS creds)
npm run docs:dev                           # Start docs server (http://localhost:5173)
```

## Verification Workflow

Before declaring a task complete, always run these steps:

1. **Compile**: `./sbt compile` — fix all errors before proceeding
2. **Test**: `./sbt <module>/test` for affected modules (or `./sbt test` for broad changes)
3. **Format**: `./sbt scalafmtAll` — CI will reject unformatted code
4. **Review**: Check that changes respect module layering (see architecture instructions)

If a compile or test error is unclear, read the error message carefully. Custom linter errors contain remediation instructions.

## Testing (UniTest)

Avoid mocks. Use `shouldBe`, `shouldNotBe`, `shouldContain`, `shouldMatch`.

```scala
// Comparison operators
(value >= 1) shouldBe true  // NOT: should be >= 1

// Type checking
result shouldMatch { case x: ExpectedType => }  // NOT: .asInstanceOf[X]
```

See `.github/instructions/unitest.instructions.md` for full assertion syntax and examples.

## Coding Style

- Scala 3 syntax only
- Omit `new`: `StringBuilder()` not `new StringBuilder()`
- String interpolation: always use `${...}` with brackets
- Avoid `Try[A]` return types
- Config classes: `withXXX(...)` for all fields, `noXXX()` for optional fields
- uni: minimal dependencies only
- Cross-platform modules must not use JVM-only APIs

See `.github/instructions/scala3.instructions.md` for full conventions and common pitfalls.

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
