# Update electron-app example to use Rx HTML (wvlet.uni.dom)

Date: 2026-08-12

## Goal

The `examples/electron-app` renderer currently builds its UI with hand-written
`org.scalajs.dom` calls: a `el(tag, classes, text)` helper, manual
`createElement`/`appendChild`, direct `textContent`/`className` mutation, and a
`setTimeout`-driven pop animation. Update it to use uni's reactive DOM toolkit,
`wvlet.uni.dom` (Rx HTML), so the example showcases the recommended way to build
Scala.js UIs.

## Current state

`renderer/src/main/scala/example/renderer/RendererApp.scala`:
- imports `org.scalajs.dom`, `org.scalajs.dom.html`
- `el[E <: html.Element](tag, classes, text)` creates elements imperatively
- `renderUI()` builds card / title / subtitle / display / buttons with
  `createElement`, `appendChild`, `className =`, `textContent =`
- `show(state)` mutates `display.textContent` + toggles `scale-110` via
  `window.setTimeout`
- `get().run(show)` loads the initial value

## Proposed design

Rewrite `RendererApp.scala` to declare the UI with Rx HTML and let reactivity
drive the display:

- `import wvlet.uni.dom.all.*` (+ the `given` conversions, per the docs setup
  block) instead of raw scalajs-dom.
- Replace `el(...)` with the DSL: `div(cls -> "...", h1(...), p(...), button(...))`.
- Replace imperative `display.textContent = ...` with an `RxVar` for the count
  embedded in the markup:
  ```scala
  val count = Rx.variable(0)
  ...
  p(cls -> "mb-8 text-7xl ...", count.map(_.toString))
  ```
- Buttons use `onclick -> { () => ... }`:
  ```scala
  button(cls -> "...", onclick -> { () => increment(1).run(show) }, "+1")
  ```
  where `show(s: CounterState)` just does `count := s.value`.
- Keep the pop animation: `DomRef` to the display element, add/remove
  `scale-110` with `window.setTimeout` (only remaining imperative bit, and a
  good showcase of `DomRef`). Alternatively drop the animation for simplicity —
  decide during implementation.
- Mount with `CounterUI().renderTo("app")` (returns `RxDomNode`).

Design the renderer as an `RxElement` subclass (`CounterUI extends RxElement`,
`override def render: RxElement`) so the example demonstrates the recommended
component shape rather than an ad-hoc builder.

The Electron IPC / RPC plumbing (`ElectronRenderer.install()`, the `rpc` /
`client` calls) stays unchanged — only the DOM-building half of the renderer
changes.

## Files to change

- `examples/electron-app/renderer/src/main/scala/example/renderer/RendererApp.scala`
  — rewrite UI construction with `wvlet.uni.dom`. Rendered as an `RxElement`
  subclass (`CounterUI`) with the count held in an `RxVar` and embedded in the
  markup; the pop animation uses a `DomRef` (the only imperative bit).
- `examples/electron-app/.scalafmt.conf` — symlink to the root config, so the
  example build is formatted with the same rules.
- `examples/electron-app/project/plugins.sbt` — add `sbt-scalafmt` 2.6.2 (same
  version as the root and sbt plugin sub-builds).
- `.github/workflows/test.yml` — check the example's `scalafmtCheckAll` in the
  required `code_format` job (it's a standalone sbt build the root check never
  sees, same rationale as the plugin sub-builds).
- `examples/electron-app/README.md` — mention Rx HTML in the renderer row.
- `docs/http/electron-tutorial.md` — update the Step 5 renderer snippet (was raw
  DOM) and the HTML mount point (`#app` instead of `#counter-value`) to match.

## Verification

- `./sbt projectJS/publishLocal` then `./sbt renderer/compile` with
  `-Duni.version=<snapshot>`: compiles clean (7 deprecation warnings come from
  the uni library's internal `EmbeddableNode`/`EmbeddableAttribute` givens —
  Scala 3.10 forward-compat, not from this example).
- `./sbt scalafmtCheckAll` in the example passes.
- `pnpm docs:build` passes.
- Example is not built by root CI, so full `pnpm`/electron run is out of scope.
