# Known Issues & Lessons Learned

Operational memory for the uni project. Observations from CI failures, PR reviews, library usage, and agent sessions accumulate here. When a pattern becomes well-understood, promote it to the relevant module's `CLAUDE.md` or `docs/dev/` guide.

## How to Use This File

**Adding entries**: When you observe a recurring pattern — a CI failure, a PR review comment that keeps coming up, a user confusion — add it here with the date and source.

**Promoting entries**: Once an issue is understood and a preventive rule is clear, move it to the right place:
- Module-specific → that module's `CLAUDE.md`
- Universal coding pattern → `docs/dev/scala3-conventions.md`
- Test pattern → `docs/dev/unitest-guide.md`
- Architecture constraint → `docs/dev/architecture.md`

After promotion, mark the entry as `[promoted]` with a pointer to where it went.

---

## Cross-Platform Issues

### Scala.js: No java.io.File access
**Source**: CI failures, recurring
**Pattern**: Code that uses `java.io.File` or `java.nio.file.Path` compiles on JVM but fails on JS/Native.
**Fix**: Use platform-specific code in `.js/` directory or avoid filesystem APIs in shared code.
**Status**: [promoted] → `uni-core/CLAUDE.md`, `docs/dev/architecture.md`

### Scala Native: libcurl required for HTTP
**Source**: CI setup
**Pattern**: Scala Native HTTP tests fail if libcurl headers are not installed.
**Fix**: CI installs `libcurl4-openssl-dev`. Local dev needs the same.
**Status**: [promoted] → `docs/dev/architecture.md`

## Testing Patterns

### UniTest: `should be >= 1` is not valid syntax
**Source**: Agent-generated code, recurring
**Pattern**: Agents write `value should be >= 1` which doesn't exist in UniTest.
**Fix**: Use `(value >= 1) shouldBe true` instead.
**Status**: [promoted] → root `CLAUDE.md`, `docs/dev/unitest-guide.md`

### UniTest: `new` keyword in test setup
**Source**: Agent-generated code
**Pattern**: Agents write `new StringBuilder()` instead of `StringBuilder()`.
**Fix**: Scala 3 convention: omit `new`. Scalafmt enforces this.
**Status**: [promoted] → `docs/dev/scala3-conventions.md`

## Build & CI Patterns

### scalafmt check fails on CI
**Source**: CI, frequent on agent PRs
**Pattern**: Agent modifies Scala files but doesn't run `./sbt scalafmtAll` before pushing.
**Fix**: Verification workflow in `CLAUDE.md`. Stop hook reminds agents.
**Status**: [promoted] → root `CLAUDE.md` verification workflow

## Dependency Issues

### AWS SDK updates are batched
**Source**: `.scala-steward.conf`
**Pattern**: AWS SDK dependencies should not be updated individually; they are grouped by Scala Steward on a 7-day cadence.
**Fix**: Do not manually bump individual AWS SDK versions. Let Scala Steward handle it.
**Status**: Documented here

---

## Template for New Entries

```
### [Short title]
**Source**: [CI / PR review / user report / agent session]
**Date**: YYYY-MM-DD
**Pattern**: [What keeps happening]
**Fix**: [How to prevent it]
**Status**: [open / promoted → location]
```
