# README: Focus on uni and uni-test, add version badge

Date: 2026-04-20

## Goal

Rework the top-level `README.md` so a newcomer can quickly understand
what `uni` and `uni-test` offer and how to use them. Also surface the
latest released version so users don't have to guess.

The `uni-agent` and `uni-bedrock` modules are still evolving and are
not ready to be highlighted on the landing page — they should be hidden
from the README for now (the docs site still covers them for anyone
who needs them).

## Changes

1. **Version indicator**: Add a Maven Central badge
   (`shields.io/maven-central/v/org.wvlet.uni/uni_3`) alongside the
   existing license badge so the current release is visible at a
   glance.
2. **Quick-start for `uni`**: Show the sbt coordinate plus a minimal
   `LogSupport` snippet mirroring `docs/index.md`, so the two landing
   pages tell the same story.
3. **Quick-start for `uni-test`**: Show the sbt coordinate (including
   the `testFrameworks` line, which is easy to miss) and a minimal
   `UniTest` snippet.
4. **Trim the module table**: Drop the `uni-agent` and `uni-bedrock`
   rows. Keep `uni`, `uni-test`, and `uni-netty` since those are the
   stable surface.
5. **Point to docs for depth**: Keep the docs link prominent — the
   README is a doorway, not a substitute for the reference site.

## Non-goals

- No changes to `docs/` — the site already documents all modules,
  and `__UNI_VERSION__` substitution only runs there.
- No module code changes.

## Verification

- Render the README locally (GitHub preview or `glow`) and confirm
  badges load and code fences are syntactically valid.
- `./sbt scalafmtCheckAll` isn't relevant (markdown only), but run
  `./sbt compile` to confirm nothing Scala-side regressed when the
  worktree is touched.
