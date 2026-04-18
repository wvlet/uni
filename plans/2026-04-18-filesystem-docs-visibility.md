# Raise FileSystem visibility in docs

## Problem

Scala historically lacked a cross-platform file I/O abstraction that works
uniformly on JVM, Scala.js (Node and browser), and Scala Native. `uni.io`
fills that gap with `FileSystem` and `IOPath`, and this is one of uni's
more distinguishing features.

Currently, `FileSystem` is documented at `/core/filesystem` and listed in
the sidebar, but it is missing from the higher-traffic discovery surfaces:

- `docs/index.md` — landing page `features` block (shown on the home page).
- `docs/guide/index.md` — "What is Uni?" bullet list and module table.
- `docs/uni-walkthrough.md` — full walkthrough of the main APIs.

A reader who lands on the home page or the intro guide would not learn
that uni provides cross-platform filesystem support at all.

## Changes

1. **`docs/index.md`** — add a FileSystem feature card to the `features`
   list on the hero section, with copy that highlights the cross-platform
   gap uni fills.
2. **`docs/guide/index.md`** — add a FileSystem bullet to the "What is
   Uni?" list and a row in the module table pointing to `/core/filesystem`.
3. **`docs/uni-walkthrough.md`** — add a "FileSystem" section with a brief
   cross-platform framing and minimal examples (path composition,
   sync/async read-write, directory listing). Update the Table of Contents.

Keep existing `docs/core/filesystem.md` as the canonical reference; the
new surfaces should be brief with links into it.

## Out of scope

- Restructuring sidebar ordering.
- Moving FileSystem out of the `Core` sidebar group.
- Changing the `/core/io.md` subprocess content.

## Success criteria

- FileSystem appears on `/` landing page features.
- FileSystem appears in `/guide/` intro list and module table.
- Walkthrough has a FileSystem section with working examples.
- `npm run docs:build` succeeds (no broken links, no markdown errors).
