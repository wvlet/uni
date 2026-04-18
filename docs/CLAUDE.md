# docs/ — Contributor Notes

Applies to reference pages under `docs/` (not `docs/book/`, which has
its own guide). Framework: VitePress 2 (`docs/.vitepress/config.mts`,
`package.json`).

## Rule #0 — Verify every API claim against source

Reference pages are contractual. Users paste snippets. Before documenting
any method, Builder option, factory, or behavior:

- **Read the source** and confirm signature, defaults, and return type.
- **Confirm config fields are actually consumed**, not just declared.
  Grep the implementation for each field before documenting it.
- **Confirm described behavior matches the code path the snippet
  exercises** (e.g. whether a call blocks, is async, or only reads
  state).
- **Call out platform scope.** APIs live under `src/` (shared) or
  `.jvm/` / `.js/` / `.native/`. Flag anything platform-specific.

If any of the above is unverified, drop the claim.

## Non-obvious VitePress bits

- **`__UNI_VERSION__`** inside code fences is replaced at build time
  with the latest release (`docs/.vitepress/fetchLatestVersion.ts`).
  Use it in `libraryDependencies` examples instead of a hardcoded
  version.
- **Sidebars are duplicated.** `docs/.vitepress/config.mts` defines
  sidebars for `/guide/` **and** `/`. Adding a page requires updating
  both — a single-location edit silently leaves the page un-navigable.
- **`srcExclude: ['**/CLAUDE.md']`** keeps these files out of the
  rendered site, so it is safe to put contributor notes next to the
  content they describe.
- **Clean URLs** are on; link to `/control/retry`, not `.html` or `.md`.

## Before merging

- Every symbol appears in current source (verified, not recalled).
- Sidebar updated in both `/guide/` and `/` entries.
- `npm run docs:build` succeeds (catches dead links `dev` misses).
