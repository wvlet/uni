# docs/ — Contributor Notes

Applies to reference pages under `docs/` (not `docs/book/`, which has
its own guide). Framework: VitePress 2 (`docs/.vitepress/config.mts`,
`package.json`).

## Rule #0 — Verify every API claim against source

Reference pages are contractual. Users paste snippets. Before documenting
any method, Builder option, factory, or behavior:

- **Read the source** and confirm signature, defaults, return type.
- **Check that Builder fields are read, not just stored.** A `case class
  Builder` may declare a field (`warmupPeriodMillis`, `name`) that no
  implementation ever consumes. `Grep` for `config.fieldName` usage
  before mentioning it.
- **Blocking vs virtual time.** A manual `Ticker` fast-forwards refill
  state; it does **not** skip `Thread.sleep` inside `acquire`/similar
  blocking calls. Verify which path the snippet exercises.
- **Platform matters.** Check whether the API lives under `src/` (shared)
  or `.jvm/` / `.js/` / `.native/`. Call it out if platform-specific.

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
