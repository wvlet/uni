# Blog section and "Introducing Uni" post

## Goals

- Give Uni a dated, citable "what is Uni and why" page that people, search engines, and AI assistants can link to. Uni has shipped 37 releases (v2026.1.0 on 2026-03-28 through v2026.1.36 on 2026-10-27) without an announcement.
- Add a minimal blog to the docs site so later posts (release highlights, guides) have a home.
- The post is the link we share on Scala Users, r/scala, X, Bluesky, and LinkedIn.

## Background

- The docs site is VitePress 2 (`docs/.vitepress/config.mts`) with clean URLs, a sitemap, `vitepress-plugin-llms` (llms.txt from the sidebar), a per-page canonical link, and a site-wide `SoftwareSourceCode` JSON-LD and og:image (the banner).
- The llms plugin's `sidebar` callback merges `/book/` and `/` to avoid duplicate TOC entries. A page that is not in the sidebar it receives is missing from llms.txt.
- Scala fences in selected Markdown files are compile-checked by `docSnippets` (`build.sbt`, `project/MarkdownSnippets.scala`).
- A peer session (the one that drew the hero image) asked for a short, stable post URL, `/uni/blog/introducing-uni`, because the X post will go out with that link right after merge.

## Design

Lane: Standard (new page type and public writing). The launch prompt carries the full design, so the direction counts as approved; the prototype gate on the rendered post is the decision point, and merge waits for the user's approval there.

- **Files**: posts live at `docs/blog/YYYY-MM-DD-slug.md` with `title`, `date`, `author`, `description` (and optional `image`) frontmatter. A VitePress `rewrites` rule serves them at `/uni/blog/<slug>`, so the date stays in the filename and frontmatter but not in the URL. First post: `docs/blog/2026-10-27-introducing-uni.md` → `/uni/blog/introducing-uni`.
- **Index** `docs/blog/index.md`: lists posts newest first via VitePress's built-in `createContentLoader` (`docs/blog/posts.data.ts`) and a small Vue component. No new dependency.
- **Nav and sidebar**: add "Blog" to the nav; a `/blog/` sidebar that lists the posts. Add `/blog/` to the llms `sidebar` dedup callback so posts appear in llms.txt once.
- **Head tags**: in `transformPageData`, posts get `BlogPosting` JSON-LD (headline, datePublished, author, image, url, about = Uni) and per-page `og:type=article`, `og:title`, `og:description`, `og:url`, `og:image`, `twitter:image` (summary_large_image). Canonical uses the rewritten path.
- **Feed**: an Atom feed `/uni/blog/feed.xml` written in `buildEnd` from the post frontmatter (~30 lines, no dependency), linked with `<link rel="alternate">`.
- **Hero image**: copy the graphic recording to `docs/public/blog/many-libraries-or-one-uni.png`, resized to 1200 px wide and compressed (target < 500 KB; also a WebP for the page), with alt text describing both sides of the picture.
- **Post content**: problem (many libraries, each with its own docs and style) → Uni as one Scala 3 library with one set of conventions (Design object wiring, logging, Weaver JSON/MessagePack, HTTP client/server, typed RPC, MCP, Rx, CLI, UniTest) on JVM/Scala.js/Native → built for AI agents (llms.txt, For AI Agents page, Claude Code plugin, Context7, DeepWiki) → successor to Airframe → quick start (compile-checked; `__UNI_VERSION__`) → works alongside the Scala Toolkit and other libraries → links (guide, book, Toolkit page, GitHub). Every claim checked against source and docs.
- **Compile check**: add `docs/blog` as a `docSnippets` root (`snippets.blog`).
- **AGENTS notes**: document the blog page type (filename, frontmatter, rewrite, sidebar entry) in `docs/AGENTS.md`.
- **PR description**: drafts for Scala Users, r/scala, X (the peer's draft), Bluesky, LinkedIn. Nothing is posted.

## Alternatives and Why Not?

- **Date in the URL** (`/blog/2026-10-27-introducing-uni`): matches the filename with no rewrite, but the short URL is easier to share and is what the X post will use.
- **`feed` npm package for RSS**: small and maintained, but a hand-written Atom generator from frontmatter we already load is shorter than the integration.
- **A blog theme plugin**: more features than one post needs, and another dependency.
