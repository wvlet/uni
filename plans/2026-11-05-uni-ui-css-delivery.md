# Deliver uni-ui.css to apps outside the uni repo

## Goals

- An app outside this repo that depends on `uni-ui` can load `uni-ui.css` from its stylesheet without hunting for the file inside a jar.
- The CSS an app loads always matches the `uni-ui` version on its classpath; class names and tokens are public API, so drift between Scala and CSS breaks components silently.
- Settle the delivery question the uni-ui ADR left open.

## Background

- Today the file ships only in the jar at `wvlet/uni/ui/uni-ui.css`; the in-repo harness imports it by relative path into the source tree.
- uni-ui components are Scala.js, so every consumer already runs sbt; typical apps link with Vite through `@scala-js/vite-plugin-scalajs`, which runs sbt on each `vite dev` / `vite build`.
- Releases are one Sonatype bundle that publishes all or nothing (single-bundle ADR). sbt-uni is already in that bundle.

## Design

Lane: Standard — packaging/release-shape decision (always-gate).

- **sbt-uni task, no npm package.** Add `uniUiCss` to `UniPlugin`: it finds `wvlet/uni/ui/uni-ui.css` on the project's dependency classpath (jar or, for `dependsOn`, a directory) and copies it to `uniUiCssFile`.
- **User flow:**
  - `enablePlugins(UniPlugin)` on the Scala.js app project (already done by apps using codegen or OpenAPI).
  - Optionally set `uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css"`; the default is under `target/`, so it is gitignored.
  - `@import '<path>/uni-ui.css'` from the app stylesheet, with the existing layer-order line for Tailwind.
  - It worked when the file appears after `vite dev` / `sbt compile`; a missing `uni-ui` dependency logs nothing and copies nothing.
- **Stays in sync automatically.** The task is triggered by `Compile / compile` (same `triggeredBy` pattern as `uniOpenAPI`), so the Vite plugin's sbt call refreshes the CSS after a version bump; it rewrites only when the content changed, so Vite's watcher does not reload needlessly.
- **No-op without uni-ui.** Projects that enable sbt-uni for codegen but do not use uni-ui pay only a classpath lookup.
- **In-repo harness keeps its relative import.** The root build pins a released sbt-uni, so it can switch only after this ships; its comment points to `uniUiCss` instead of "copy from the jar".
- **Docs:** Setup in `docs/dom/ui.md`, the uni-ui row in `docs/guide/for-ai-agents.md`, the uni skill (version bump), and the ADR's Consequences bullet record the decision.
- Tests: a scripted test in sbt-uni where an app `dependsOn` a stub module holding the CSS resource checks that compile copies it and that a project without it copies nothing.

## Alternatives and Why Not?

- **npm package `@wvlet/uni-ui`.** Nicest import (`@import '@wvlet/uni-ui/uni-ui.css'`), but it adds an npm token and a second publish outside the all-or-nothing Sonatype bundle, and its version can drift from the jar the app links.
- **Both npm and sbt.** Two paths to document and keep in sync for one CSS file; can be added later if non-sbt consumers appear.
- **Docs-only recipe (a few lines of build.sbt).** Each app re-implements the jar lookup, and nothing keeps the copy fresh.
- **Put the CSS in the Scala.js linker output (`import 'scalajs:uni-ui.css'`).** Neat, but CSS `@import` does not go through the Vite plugin's `scalajs:` resolver, so the cascade-layer ordering would not work.
