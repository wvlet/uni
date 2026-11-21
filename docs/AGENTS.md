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

- **`__UNI_VERSION__`** inside code fences and inline code is replaced
  at build time with the newest release on repo.wvlet.org
  (`docs/.vitepress/fetchLatestVersion.ts`), and
  **`__UNI_CENTRAL_VERSION__`** with the newest one on Maven Central.
  Use `__UNI_VERSION__` in `libraryDependencies` examples instead of a
  hardcoded version; a getting-started snippet also needs the
  `repo.wvlet.org` resolver, since newer releases are not on Central. Likewise **`__SCALA_VERSION__`** is replaced with `SCALA_3`
  from the root `build.sbt`; use it for `scalaVersion` in samples.
- **Sidebars are built from shared lists.** In `docs/.vitepress/config.mts`,
  a new module page goes in `moduleGroups` and in `docs/modules.md`, a guide page in
  `gettingStarted`, and a UI page (`/dom/`, `/http/electron*`) in
  `uiSidebar`. The main sidebar (`/`, shared by guide and module pages) is built from them, so a
  page not in these lists is un-navigable.
- **`srcExclude: ['**/AGENTS.md']`** keeps these files out of the
  rendered site, so it is safe to put contributor notes next to the
  content they describe.
- **Clean URLs** are on; link to `/control/retry`, not `.html` or `.md`.

## Blog posts

- **One file per post**: `docs/blog/YYYY-MM-DD-slug.md` with `title`, `date`,
  `author`, and `description` frontmatter, plus an optional `image` (a path
  under `docs/public/`, used as og:image and twitter:image). Keep frontmatter
  values on one line; `config.mts` reads them with a flat `key: value` parser.
- **Author avatars**: the post header and the blog index show the author's GitHub
  avatar when `author` matches an entry in `docs/.vitepress/theme/authors.ts`; add
  new authors there. Unlisted authors are shown by name only.
- **The URL drops the date**: a rewrite in `config.mts` serves the post at
  `/blog/slug`. Link to it that way, and never rename a published slug.
- **Generated for you**: the blog index (`blog/posts.data.ts`), the `/blog/`
  sidebar, the Atom feed (`/blog/feed.xml`), and the post's `BlogPosting`
  JSON-LD and Open Graph tags all come from the frontmatter.
- **Scala fences are compile-checked** by `docSnippets`, like the skill pages.
- **Keep a post's code current, not its story.** Fix errors and refresh code
  examples in place when an API they use changes (e.g. a simpler entry point),
  so readers who copy them get working code. Keep the post's claims and
  narrative as published; announce new features in a new post. `scala skip`
  fences (Scala.js, Electron) are not compiled by `docSnippets`, so compile
  them by hand when you update them.

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
- Page added to `moduleGroups`, `gettingStarted`, or `uiSidebar`.
- `pnpm docs:build` succeeds (catches dead links `dev` misses).
