# uni-ui: DOM components in their own artifact, styled by a plain CSS file

## Context

Apps built on `uni-dom` keep rebuilding the same desktop UI pieces. `examples/uni-harness` grew its
own panes, resize handles, tabs, and themes. Other UI kits split unstyled behavior from styled
components; GPUI Kit (Rust, on Zed's GPUI) does it with `gpui-base` and `gpui-component`. Uni needs
the same split, but desktop apps stay on Electron with a single Chromium engine, so the components
render to the DOM, not to a GPU surface.

## Decision

- Add a Scala.js-only artifact `uni-ui` (package `wvlet.uni.ui`) that depends on `uni.js`, where the
  `wvlet.uni.dom.all` DSL and `Storage` live. `uni` gains no dependencies, and apps that don't build
  UIs don't link uni-ui.
- Split it into `wvlet.uni.ui.base` (behavior, ARIA, focus, keyboard, `data-state`, no visual
  classes) and `wvlet.uni.ui` (styled components on top of `base`).
- Style the components with one plain CSS file, `uni-ui.css`, shipped in the jar. Don't generate CSS
  from Scala. Components read semantic `--uni-*` custom properties, themes are token sets selected
  by `[data-theme]`, class names are `uni-<component>` with `--modifier`s, state goes in ARIA and
  `data-*` attributes, and every rule sits in `@layer uni`, so app CSS overrides it without
  specificity tricks.

## Alternatives

- **CSS-in-Scala** (styles defined in Scala and injected as a `<style>` element at mount). It needs
  no bundler step, but adds runtime work and indirection, is harder to read and override in
  devtools, and gives little that CSS custom properties don't already give.
- **Tailwind utility classes in the components**, as the harness does. Every app would have to run
  Tailwind and scan uni-ui's sources. Tokens stay compatible with Tailwind instead: an app maps them
  into its `@theme`.
- **Inside `uni.js`.** Every Scala.js user of `uni` would get the component code and a CSS file they
  may not want, and the UI code would release on the same terms as the core.
- **A GPU-native renderer** (Skia on Scala Native, as GPUI does in Rust). This is a multi-year
  project. It would give up the browser target and the accessibility, IME, and text layout that the
  DOM provides.

## Consequences

- Apps must load `uni-ui.css` once. How apps outside the repo get the file (an npm package or an sbt
  copy task) is settled in the first implementation PR.
- Class names, token names, and `data-state` values are public API. Renaming one is a breaking
  change and goes in `docs/guide/for-ai-agents.md`.
- `uni`, `uni-core`, and `uni-ui` release together, like the other artifacts.
- `VisualTest` keeps light and dark screenshot goldens per OS (`darwin`, `linux`) in
  `uni-ui/src/test/screenshots`, so a CSS change shows up as a golden diff. The build embeds
  `uni-ui.css` into the test as `UniUiCss.text`, because the browser cannot read it from the jar.
