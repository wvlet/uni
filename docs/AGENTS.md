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
  version. Likewise **`__SCALA_VERSION__`** is replaced with `SCALA_3`
  from the root `build.sbt`; use it for `scalaVersion` in samples.
- **Sidebars are duplicated.** `docs/.vitepress/config.mts` defines
  sidebars for `/guide/` **and** `/`. Adding a page requires updating
  both — a single-location edit silently leaves the page un-navigable.
- **`srcExclude: ['**/AGENTS.md']`** keeps these files out of the
  rendered site, so it is safe to put contributor notes next to the
  content they describe.
- **Clean URLs** are on; link to `/control/retry`, not `.html` or `.md`.

## Blog posts

- **One file per post**: `docs/blog/YYYY-MM-DD-slug.md` with `title`, `date`,
  `author`, and `description` frontmatter, plus an optional `image` (a path
  under `docs/public/`, used as og:image and twitter:image). Keep frontmatter
  values on one line; `config.mts` reads them with a flat `key: value` parser.
- **The URL drops the date**: a rewrite in `config.mts` serves the post at
  `/blog/slug`. Link to it that way, and never rename a published slug.
- **Generated for you**: the blog index (`blog/posts.data.ts`), the `/blog/`
  sidebar, the Atom feed (`/blog/feed.xml`), and the post's `BlogPosting`
  JSON-LD and Open Graph tags all come from the frontmatter.
- **Scala fences are compile-checked** by `docSnippets`, like the skill pages.
- **Posts are dated records.** Fix errors in place, but don't rewrite an old
  post to describe newer APIs; write a new post instead.

## AI-agent docs

The site is read by AI agents as much as by people.

- **`llms.txt`, `llms-full.txt`, and per-page `.md` copies are generated** by
  `vitepress-plugin-llms` from the sidebar and page sources. Never hand-write
  them. A page missing from the sidebar is missing from `llms.txt`.
- **`guide/for-ai-agents.md` is a contract for agents.** Keep its imports table,
  Airframe mapping, and "mistakes" list in sync with the source, verified the
  same way as Rule #0.
- **The `uni` skill (`plugins/uni/skills/uni/`) is a digest of that page.**
  Edit both in the same PR and bump the plugin `version` (see root `AGENTS.md`).
- **Give new pages a `description` frontmatter** of one or two sentences that
  name the problem the page solves. It becomes `<meta name="description">`,
  which AI search engines quote.
- **Open pages with the answer.** The first paragraph says what the feature is
  and when to use it, followed by a runnable snippet.

## Before merging

- Every symbol appears in current source (verified, not recalled).
- Sidebar updated in both `/guide/` and `/` entries.
- `pnpm docs:build` succeeds (catches dead links `dev` misses).
