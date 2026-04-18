# Writing Guide — Uni Reference Docs

> This file is for contributors (humans and Claude) writing the
> **reference** docs under `docs/` (everything outside `docs/book/`,
> which has its own [CLAUDE.md](./book/CLAUDE.md)). It is excluded from
> the rendered site via VitePress's `srcExclude` config.

## Rule #0 — Verify before you write

**Every symbol, method signature, option name, and behavior mentioned in
a reference page MUST correspond to code that exists in this repository
right now.** Reference docs are contractual: users paste snippets into
their code. If a doc promises `withWarmupPeriod(...)` and the builder
silently ignores it, the bug is on us.

Before claiming an API exists:

1. **Read the source.** Open the module file (e.g.
   `uni/.jvm/src/main/scala/wvlet/uni/control/RateLimiter.scala`) and
   confirm the method, signature, default values, and return type.
2. **Check that Builder fields are actually consumed.** A field on a
   `case class Builder` is easy to mistake for a working feature — grep
   the implementation (`Grep` for `config.fieldName`) to confirm it is
   read, not just stored.
3. **Distinguish blocking from non-blocking semantics.** If you describe
   testing or timing behavior, verify whether the call uses
   `Thread.sleep`, waits on a condition, or just reads state. A
   manual/fake `Ticker` controls virtual time for state-based reads, not
   real `Thread.sleep`.
4. **Check platform availability.** The module layout encodes platform:
   `src/main/scala` is shared, `.jvm/`, `.js/`, `.native/` are
   platform-specific. Call out any JVM-only (or JS/Native-only) API
   explicitly. Example: `RateLimiter` is under `uni/.jvm/...`.
5. **Run the snippet mentally against the actual API.** If the snippet
   depends on imports, factory methods, or return types you did not
   verify, it does not go in.

When in doubt, drop the claim. A shorter, correct doc is worth more than
a longer one that needs retractions.

### Common hallucination shapes to watch for

- **Inventing Builder options** because they seem like they ought to
  exist (warmup, name, metrics hooks).
- **Assuming a factory method** is named after the nearest analogue
  (`RateLimiter.newRateLimiter` when it is actually `newBuilder`).
- **Mixing ticker semantics.** A manual ticker fast-forwards refill
  state; it does not skip `Thread.sleep`.
- **Stale package paths.** Packages move. `wvlet.uni.control` today may
  re-export from elsewhere — verify the import you write resolves.

## Audience

Reference readers know Scala 3 and already know *which* component they
are looking up. Keep pages:

- **Scannable.** Lead with the minimum working snippet, then options,
  then composition patterns.
- **API-shaped.** Organize around methods/classes, not around tasks.
  Task-shaped narrative lives in `/book/`.
- **Linked.** Cross-link freely into related reference pages; assume the
  reader is navigating, not reading front-to-back.

## Framework: VitePress

Reference docs are built with [VitePress](https://vitepress.dev). A few
project-specific conventions:

- **`srcExclude`** (in `docs/.vitepress/config.mts`) hides
  `**/CLAUDE.md` from the rendered site. Keep this guide and any
  contributor-only notes in `CLAUDE.md` files.
- **Version interpolation.** The token `__UNI_VERSION__` inside code
  blocks is replaced at build time with the latest release version (see
  `docs/.vitepress/fetchLatestVersion.ts`). Prefer `__UNI_VERSION__` in
  `libraryDependencies` examples so docs stay current:

  ````markdown
  ```scala
  libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
  ```
  ````

- **Sidebars are explicit.** When you add or rename a page, update
  **both** sidebar entries in `docs/.vitepress/config.mts`
  (the `/guide/` sidebar and the `/` sidebar). Missing sidebar entries
  produce pages that exist but are not navigable.
- **Callout blocks** use VitePress containers:

  ```markdown
  ::: tip JVM-only
  ...
  :::

  ::: warning
  ...
  :::
  ```

- **Code theme** is Nord (light + dark). No extra setup needed per page.
- **Clean URLs** are enabled. Link to `/control/retry`, not
  `/control/retry.html` or `/control/retry.md`.

## Local workflow

```bash
npm run docs:dev     # http://localhost:5173 with hot reload
npm run docs:build   # Production build; run before merging large changes
```

`docs:dev` does not fail on dead links. `docs:build` catches a lot more.
For non-trivial changes, run the build locally and visit the page in a
browser before opening the PR.

## Structure of a reference page

A typical reference page has this skeleton:

1. **One-line purpose.** What the component does, in one sentence.
2. **Package / import.** A single `import` line so the reader can copy.
3. **Minimum example.** The smallest thing that works.
4. **API tour.** Methods / options, grouped logically. Each with a
   snippet.
5. **Composition patterns.** How it plays with other Uni primitives.
6. **Best practices.** 3–5 bullets. No filler.

Not every page needs every section, but if you skip one, be deliberate
about it.

## Style

- **Second person, active voice.** "You call `acquire()`…", not "`acquire()`
  is called…".
- **Present tense.** "returns", not "will return".
- **Show the shape before the prose.** A code snippet followed by one
  paragraph of explanation beats a paragraph introducing a snippet.
- **No emoji** unless the user explicitly requests it.
- **Scala 3 only.** Indentation-based syntax, `new` omitted, `${...}`
  for interpolation.

## Linking

- Link **into** other reference pages (e.g. "see [Retry](/control/retry)")
  rather than restating them.
- Reference pages should **not** link into `/book/`. Reference stays
  reference; the Book links out to reference when it wants the deep dive.
- Use absolute site paths (`/control/rate-limiter`) for
  cross-section links, relative paths (`./cache`) within the same
  section.

## Review checklist before merging a reference page

- [ ] Every method, option, and class name appears in the source (verified
      with `Read`/`Grep`, not memory).
- [ ] Builder fields mentioned in docs are actually **read** by the
      implementation, not just stored on the case class.
- [ ] Platform availability is called out (JVM-only, JS-only, etc.).
- [ ] Imports in snippets resolve against current packages.
- [ ] Timing / blocking semantics match the implementation (`Thread.sleep`
      vs virtual ticker vs state-only).
- [ ] Sidebar entry added in both `/guide/` and `/` sidebars in
      `docs/.vitepress/config.mts`.
- [ ] Overview page (e.g. `docs/control/index.md`) updated if a new
      component is added.
- [ ] `npm run docs:build` succeeds.
