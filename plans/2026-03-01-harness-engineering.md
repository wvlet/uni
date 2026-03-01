# Harness Engineering Integration for Claude Code

**Date**: 2026-03-01
**Status**: In progress

## Background

[Harness engineering](https://openai.com/index/harness-engineering/) is the discipline of designing the environment around a coding agent so it can consistently produce correct, high-quality output. The key insight: the bottleneck isn't the model — it's the structure you give it.

This plan applies harness engineering principles to the uni repository for Claude Code.

## Principles Applied

### 1. CLAUDE.md as Table of Contents, Not Encyclopedia

**Problem**: A monolithic instruction file crowds out the actual task context. Stale rules accumulate.

**Solution**: Keep CLAUDE.md concise (~100 lines of universal rules) and use it as a map pointing to deeper sources of truth via `.github/instructions/` files.

**Done**:
- [x] Restructured CLAUDE.md with a "Deeper Documentation" table
- [x] Added pointers to all instruction files and docs

### 2. File-Scoped Instructions (Context When Needed)

**Problem**: Generic instructions waste context on irrelevant rules. Agent doesn't know module-specific constraints.

**Solution**: Use `.github/instructions/*.instructions.md` with `applyTo` front matter so instructions activate only when editing matching files.

**Done**:
- [x] `architecture.instructions.md` — module dependency graph and layering rules (for build files)
- [x] `scala3.instructions.md` — coding conventions and common pitfalls (for all .scala files)
- [x] `agent-module.instructions.md` — agent module guide (for uni-agent/ files)
- [x] `http-module.instructions.md` — HTTP module guide (for HTTP-related files)
- [x] `unitest.instructions.md` — already existed for test files

### 3. Verification Workflow (Mechanical Enforcement)

**Problem**: Agents declare "done" without verifying. Broken code gets committed.

**Solution**: Added explicit verification steps to CLAUDE.md: compile → test → format → review layering.

**Done**:
- [x] Added "Verification Workflow" section to CLAUDE.md

### 4. Repository as Single Source of Truth

**Problem**: Knowledge in Slack, docs, or people's heads is invisible to agents.

**Solution**: All architectural decisions, module guides, and conventions are versioned markdown in the repo.

**Done**:
- [x] Module dependency graph documented in architecture instructions
- [x] Cross-platform rules documented
- [x] Common pitfalls documented in scala3 instructions

## Future Improvements

### Phase 2: Hooks & Automated Feedback
- [ ] Add Claude Code hooks for pre-commit format checking
- [ ] Add a post-task review hook using a subagent with critical mindset
- [ ] Consider a SessionStart hook to run `./sbt compile` on session start

### Phase 3: Background Quality Agents
- [ ] Periodic stale-documentation detection (are instructions still accurate?)
- [ ] Automated cleanup PRs for code consistency drift
- [ ] Quality grading system for modules (track improvement over time)

### Phase 4: Deeper Module Instructions
- [ ] Add instructions for `uni-core` (pure Scala constraints, logging patterns)
- [ ] Add instructions for cross-platform development (expect/actual patterns)
- [ ] Add instructions for `uni-dom-test` (JSDOM environment quirks)
