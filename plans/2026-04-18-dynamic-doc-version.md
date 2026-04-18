# Dynamic Version in Documentation

## Summary

Replace hardcoded version numbers (e.g. `2026.1.0`) in `libraryDependencies` examples
throughout the VitePress documentation with a dynamically-fetched value, so readers
always see the latest released version without manual edits per release.

## Problem

Today, every doc page that shows `libraryDependencies` hardcodes a specific version:

```
docs/index.md                        2026.1.0
docs/guide/installation.md           2026.1.0 (x5)
docs/agent/index.md                  2026.1.0 (x2)
docs/agent/bedrock.md                2026.1.0
docs/uni-walkthrough.md              2026.1.0
docs/.vitepress/theme/HomeHeroCode.vue 2026.1.0
```

This drifts from reality the moment a new release is cut. The project is currently at
`v2026.1.6` (per `git log`), but the docs still advertise `2026.1.0`.

## Goal

At docs build time, detect the latest GitHub release tag and substitute it into all
`libraryDependencies` examples. Fall back to a sensible hardcoded version if the
network call fails (so local dev / offline builds still work).

## Design

### Build-time version fetch

Create `docs/.vitepress/fetchLatestVersion.ts` exporting an async helper that calls
`https://api.github.com/repos/wvlet/uni/releases/latest`, strips the leading `v` from
`tag_name`, and returns the version string. On failure (non-200, network error,
malformed response), return a supplied fallback.

The fetch runs once per `vitepress build` / `vitepress dev` invocation from inside
`config.mts` via top-level `await` (supported in `.mts`).

### Markdown token replacement

Introduce the placeholder `__UNI_VERSION__` for every spot in markdown that should
render the latest version. Register a markdown-it transform in `markdown.config(md)`
that wraps the `fence`, `code_inline`, and `text` renderer rules and replaces the
token with the resolved version. This keeps Shiki syntax highlighting intact (tokens
are rewritten on the emitted content strings, not on source before parsing).

### Vue component (HomeHeroCode)

`HomeHeroCode.vue` is a Vue component rendering hand-styled Nord-themed HTML; it is
not processed by markdown-it. Expose the resolved version via
`themeConfig.uniVersion` in the VitePress config and read it in the component through
`useData().theme.value.uniVersion`.

### Placeholder adoption

Replace hardcoded `2026.1.0` in:

- `docs/index.md` (2 occurrences: sbt + Scala CLI snippet)
- `docs/guide/installation.md` (5 occurrences)
- `docs/agent/index.md` (2 occurrences)
- `docs/agent/bedrock.md` (1 occurrence)
- `docs/uni-walkthrough.md` (1 occurrence)
- `docs/.vitepress/theme/HomeHeroCode.vue` (1 occurrence; replaced via `useData`)

`docs/core/unitest.md` already uses `"(version)"` placeholder text — switch it to
`__UNI_VERSION__` for consistency.

### Fallback version

Keep a single `DEFAULT_VERSION` constant in `config.mts` (e.g. `"2026.1.6"` matching
the latest release at plan time). Bump it occasionally, but the primary driver of the
displayed version is the live GitHub fetch.

## Non-goals

- Runtime (browser) fetching. Build-time is sufficient, avoids CORS and rate-limit
  concerns on the live docs site, and keeps the page static-cacheable.
- A separate "latest release" badge on the landing page. Could be added later; out of
  scope for this change.
- Maven Central fallback. GitHub releases are authoritative for this project; if the
  fetch fails we use the hardcoded default.

## Risks & mitigations

- **GitHub API rate-limit during CI builds.** Anonymous limit is 60 req/IP/hour. Docs
  builds use one call per build — well within limits. On hit, the fallback kicks in.
- **Offline local builds.** Fetch catches network errors and returns the fallback
  version, so `npm run docs:dev` works without connectivity.
- **Token leaking into unintended code blocks.** Placeholder is deliberately
  uncommon (`__UNI_VERSION__`) and only appears where we add it.

## Validation

- `npm run docs:build` succeeds and the generated HTML shows the latest GitHub tag
  (currently `2026.1.6`) wherever the token was placed.
- `npm run docs:dev` serves the same.
- Simulate fetch failure (block network) → falls back to default version cleanly.
