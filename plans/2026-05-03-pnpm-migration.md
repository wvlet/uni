# Migrate from npm to pnpm

## Why

The Node side of this repo is small — one `package.json` driving VitePress
docs and a `jsdom` dep used by Scala.js tests. But every CI run still pays
npm's slow install cost and ships a 97 KB `package-lock.json`. pnpm's
content-addressable store is faster, the strict `node_modules` layout
catches phantom-dependency bugs, and `pnpm-lock.yaml` is friendlier to
review than `package-lock.json`.

This migration is a pure tooling swap. There are **no app-code changes**;
the package set in `package.json` stays identical.

## Scope

Single-package repo (root `package.json`). No workspaces today, but pnpm
gives us a clean upgrade path if we ever split docs / tooling /
generators into separate packages.

## What changes

### `package.json`
- Add `"packageManager": "pnpm@10.7.1"` — pins the manager so CI and
  contributors with corepack get the same version.

### Lockfile
- Delete `package-lock.json`.
- Generate `pnpm-lock.yaml` via `pnpm install`.

### `.github/workflows/doc.yml`
- Path filter: `package-lock.json` → `pnpm-lock.yaml` (so doc CI fires
  when deps change).
- Add `pnpm/action-setup@v4` step before `actions/setup-node@v6`.
- `cache: 'npm'` → `cache: 'pnpm'`.
- `npm ci` → `pnpm install --frozen-lockfile`.
- `npm run docs:build` → `pnpm docs:build`.

### `.github/workflows/test.yml`
Two jobs (`test_js`, `package_src`) install npm deps for jsdom under
Scala.js. Both need:
- `pnpm/action-setup@v4` step before `actions/setup-node@v6`.
- `npm install` → `pnpm install --frozen-lockfile`.

### `.github/dependabot.yml`
No change needed. Dependabot's `npm` ecosystem auto-detects
`pnpm-lock.yaml` and produces correct PRs. The stale "Docusaurus"
group comment is unrelated to this PR — leave it.

### `.gitignore`
No change. `node_modules` is already ignored; pnpm uses the same
directory.

### Docs (`CLAUDE.md` files)
- Root `CLAUDE.md`: `npm run docs:dev` → `pnpm docs:dev`.
- `docs/CLAUDE.md`: `npm run docs:build` → `pnpm docs:build`.
- `docs/book/CLAUDE.md`: `npm run docs:build` → `pnpm docs:build`.

Plan files under `plans/*.md` are historical and intentionally
left as-is.

## Verification

Locally before opening the PR:

```bash
pnpm install --frozen-lockfile
pnpm docs:build
```

CI provides the rest:
- `doc` workflow runs pnpm-based VitePress build.
- `test_js` and `package_src` jobs run pnpm install before Scala.js
  tests / packageSrc.

## Risks and rollbacks

- **Risk:** `pnpm-lock.yaml` resolves a slightly different transitive
  graph than `package-lock.json`. Mitigation: `pnpm docs:build` locally
  confirms VitePress still builds; the only runtime dep is `jsdom`
  (used only by Scala.js tests, not at doc build time).
- **Risk:** `pnpm/action-setup@v4` version drift. Mitigation: pinned
  major; `packageManager` field locks the pnpm version regardless.
- **Rollback:** revert the PR. Re-running `npm install` from the
  restored `package.json` regenerates `package-lock.json`.

## Out of scope

- pnpm workspaces (single package today).
- Replacing the stale "Docusaurus" comment in `dependabot.yml`.
- Updating historical plan files that mention `npm run …`.
