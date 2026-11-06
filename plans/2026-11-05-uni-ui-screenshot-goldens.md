# uni-ui screenshot goldens

## Goals

- Catch visual regressions in `uni-ui.css` and the components, which the behavior tests in `ComponentTest` cannot see.
- Cover light and dark themes for Button (variants, sizes), IconButton, Input/Textarea (invalid, disabled), TabList/TabPanel, FoldablePane + Splitter, Badge, Kbd, StatusBar.

## Background

- `domTest` already runs `Playwright.expectScreenshot` with goldens named `<name>-chromium-<os>.png` in `src/test/screenshots`; a missing golden is written and fails (ADR 2026-10-19).
- The browser cannot read `uni-ui.css` from the jar, so the test page has no styles today; `ComponentTest` checks only classes and ARIA.
- Components render text, so goldens depend on the OS fonts. CI runs on Ubuntu; no Docker is available locally.

## Design

Lane: **Express** — tests and CI config only, no change to library code or always-gate areas.

- New `uni-ui/src/test/.../VisualTest.scala`: one fixed-size gallery per component group, mounted once per theme by setting `data-theme` on `<html>`. Goldens are named `<group>-<theme>` (e.g. `button-dark`), about 7 groups × 2 themes.
- Load the CSS with a test-only sbt source generator that embeds `uni-ui.css` as a string constant, and inject it as one `<style>` element. The test always uses the CSS in the source tree, with no new page command.
- Exact comparison (`maxDiffPixelRatio = 0`), like `domTest`. No hover, focus, or animation: the shots show the resting state only.
- Wire `ui`'s `Test / jsEnv` with `withScreenshotDir` / `withSnapshotDir` exactly like `domTest`, with goldens in `uni-ui/src/test/screenshots`.
- Commit both `darwin` goldens (generated locally, so `./sbt ui/test` passes on macOS) and `linux` goldens (from CI).
- Linux goldens come from the documented path: the first push fails CI, I download the `playwright-screenshots` artifact, check the images, and commit them.
- `test.yml`: the "List new golden images" step and the artifact paths also cover `uni-ui/src/test/screenshots`.

## Alternatives and Why Not?

- Add a Playwright `addStyleTag` page command: it reaches the build only after a plugin release, so it is too slow for this.
- Use a tolerance (`maxDiffPixelRatio > 0`) for fonts: it would hide small CSS regressions, and goldens are already per OS.
- Linux-only goldens: local macOS runs would always fail on missing goldens.
