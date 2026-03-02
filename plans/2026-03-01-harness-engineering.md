# Harness Engineering Integration for Claude Code

**Date**: 2026-03-01
**Status**: In progress

## Background

[Harness engineering](https://openai.com/index/harness-engineering/) is the discipline of designing the environment around a coding agent so it can consistently produce correct, high-quality output. The key insight: the bottleneck isn't the model — it's the structure you give it.

This plan applies harness engineering principles to the uni repository for Claude Code.

## Principles Applied

### 1. CLAUDE.md as Table of Contents, Not Encyclopedia

**Problem**: A monolithic instruction file crowds out the actual task context. Stale rules accumulate.

**Solution**: Keep root CLAUDE.md concise (~100 lines of universal rules) and use it as a map pointing to deeper sources of truth. Module-specific context lives in folder-local `CLAUDE.md` files that Claude Code auto-loads.

**Done**:
- [x] Restructured root CLAUDE.md with a "Deeper Documentation" table
- [x] Added pointers to `docs/dev/` reference docs

### 2. Folder-Local CLAUDE.md Files (Context When Needed)

**Problem**: Generic instructions waste context on irrelevant rules. Agent doesn't know module-specific constraints.

**Solution**: Place a `CLAUDE.md` in each module directory. Claude Code automatically loads these when working in the corresponding directory, providing module-specific constraints, dependencies, and test commands without polluting the root file.

**Done**:
- [x] `uni-core/CLAUDE.md` — pure Scala constraints, zero dependencies
- [x] `uni/CLAUDE.md` — main library, cross-platform patterns
- [x] `uni-test/CLAUDE.md` — test framework self-testing
- [x] `uni-agent/CLAUDE.md` — agent module, JVM-only, airframe dependency
- [x] `uni-agent-bedrock/CLAUDE.md` — AWS Bedrock, isolated AWS SDK usage
- [x] `uni-netty/CLAUDE.md` — Netty HTTP server, JVM-only

### 3. Reference Documentation in docs/dev/

**Problem**: Detailed guides (architecture, conventions, test patterns) are too long for CLAUDE.md but need to be discoverable by agents.

**Solution**: Store reference docs in `docs/dev/` and point to them from CLAUDE.md. Agents can read these when they need deeper context.

**Done**:
- [x] `docs/dev/architecture.md` — module dependency graph and layering rules
- [x] `docs/dev/scala3-conventions.md` — coding conventions and common pitfalls
- [x] `docs/dev/unitest-guide.md` — assertion syntax, examples, Design integration

### 4. Verification Workflow (Mechanical Enforcement)

**Problem**: Agents declare "done" without verifying. Broken code gets committed.

**Solution**: Added explicit verification steps to CLAUDE.md: compile → test → format → review layering.

**Done**:
- [x] Added "Verification Workflow" section to CLAUDE.md

### 5. Repository as Single Source of Truth

**Problem**: Knowledge in Slack, docs, or people's heads is invisible to agents.

**Solution**: All architectural decisions, module guides, and conventions are versioned markdown in the repo.

**Done**:
- [x] Module dependency graph documented in `docs/dev/architecture.md`
- [x] Cross-platform rules documented in folder-local CLAUDE.md files
- [x] Common pitfalls documented in `docs/dev/scala3-conventions.md`

## Future Improvements

### Phase 2: Hooks & Automated Feedback
- [ ] Add Claude Code hooks for pre-commit format checking
- [ ] Add a post-task review hook using a subagent with critical mindset
- [ ] Consider a SessionStart hook to run `./sbt compile` on session start

### Phase 3: Background Quality Agents
- [ ] Periodic stale-documentation detection (are instructions still accurate?)
- [ ] Automated cleanup PRs for code consistency drift
- [ ] Quality grading system for modules (track improvement over time)

### Phase 4: Additional Module CLAUDE.md Files
- [ ] Add `uni-dom-test/CLAUDE.md` (JSDOM environment quirks)
- [ ] Add `uni-integration-test/CLAUDE.md` (AWS credential requirements)
