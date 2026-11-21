# UI gallery: uni-ui components next to their code

A live catalog of [uni-ui](https://wvlet.org/uni/dom/ui) components: a page per component with
several examples, each next to the Scala code that renders it, then the component's parts, API,
and a live accessibility check. The docs embed it on the
[Gallery](https://wvlet.org/uni/dom/gallery) page.

## What it shows

- **Pages of examples.** Each component has a `ComponentPage` ([`Page.scala`](src/main/scala/example/gallery/Page.scala))
  with its examples, its API, and the WAI-ARIA patterns it follows. Each example is an
  `inline def` that returns an `RxElement`, between `// #region <page>/<example>` and
  `// #endregion` markers in
  [`stories/`](src/main/scala/example/gallery/stories/). `project/StoryRegions.scala` copies each
  block into the app at build time, so the code panel shows exactly the code that runs. It drops
  the `inline` modifier, which only the macro needs, so the shown and copied code is a plain `def`.
- **Exact links between the UI and the code.** The `SourceTag` macro
  ([`SourceTag.scala`](src/main/scala/example/gallery/SourceTag.scala)) finds each uni-ui
  component an example creates and tags it with `data-src-kind` and `data-src-line`. Hovering a line
  of code outlines what it creates; with Inspect on, hovering the preview marks the line that made
  each part. The chips in each card's toolbar list its components.
- **A shell built with uni-ui**, styled after uni-harness. An icon rail and a `Tree` in a resizable
  `FoldablePane` list the components. A group of toggle buttons switches between Preview, Code, Split, and Agent,
  where the code or the agent view sits in a second `FoldablePane` with a `Splitter`. ⌘K opens a `CommandPalette` over
  the pages and examples, and a `Menu` sets the `ColorScheme`.
- **Playgrounds.** `Story.playground` gives an example controls for the props of its `inline def`.
  The code panel shows each `p.<name>` as the control's current value, written as Scala, so the
  code always matches the preview.
- **What an agent sees.** The Agent view ([`AgentView.scala`](src/main/scala/example/gallery/AgentView.scala))
  installs a `UiAgent` over the example, lists its snapshot, and runs actions through it.
- **Reference sections.** Each page ends with the `data-slot` parts read from the running examples,
  the API listed at compile time by `ApiDoc` ([`ApiDoc.scala`](src/main/scala/example/gallery/ApiDoc.scala),
  with doc comments read from TASTy by `-Xread-docs`), and `checkPattern` run on the live
  components ([`PageView.scala`](src/main/scala/example/gallery/PageView.scala)). Under each
  example, an event log lists the changes its components report and who made them
  ([`EventLog.scala`](src/main/scala/example/gallery/EventLog.scala)).
- **Colors.** The Colors page ([`ColorsPage.scala`](src/main/scala/example/gallery/ColorsPage.scala))
  shows every `--uni-*` color variable as a swatch, light and dark side by side, read live with
  `getComputedStyle` from hidden frames that load the page's stylesheets with `data-theme` forced.
  Click a swatch to copy its `var()`, or a value to copy the value. A test checks the page against
  the variables `uni-ui.css` declares.
- **State in the URL.** `#/menu` names a page and `#/menu/checked` an example,
  `?view=preview`, `code`, `split`, or `agent` picks the view, `?theme=dark` or `?theme=light` forces a color scheme, and `?embed` shows only
  one example's card for an iframe. An embedding page switches the theme with
  `postMessage({ type: 'uni-gallery-theme', theme: 'dark' })`, which keeps the examples' state.

Source: [`Gallery.scala`](src/main/scala/example/gallery/Gallery.scala),
[`StoryView.scala`](src/main/scala/example/gallery/StoryView.scala),
[`CodeView.scala`](src/main/scala/example/gallery/CodeView.scala), and
[`Story.scala`](src/main/scala/example/gallery/Story.scala).

## Run

```bash
./sbt exampleUiGallery/gallerySite   # stages index.html, the CSS, and main.js in target/site
python3 -m http.server -d examples/ui-gallery/target/site 8080
# open http://localhost:8080
./sbt exampleUiGallery/test          # mounts every example in headless Chromium
```

`./sbt exampleUiGallery/galleryDocs` links with `fullLinkJS` into `docs/public/gallery`, where
`pnpm docs:dev` and the docs build pick it up.

## Add a story

1. Write an `inline def` that returns an `RxElement` in one of the `stories/*.Stories` objects,
   between `// #region <page>/<name>` and `// #endregion`. Keep everything the demo uses inside
   the block. Scala 3 rejects a case class inside an `inline def`, so put case classes (and enums) above
   it, still inside the region.
2. Add `Story("<page>/<name>", title, whatToTry)(yourDef)` to that page's `examples` in the
   object's `pages`. A new component gets a `ComponentPage(id, name, summary, examples,
   ApiDoc.of[YourComponent], a11y = Seq(A11yCheck(pattern, selector)))`. For a
   playground, add `Story.playground(id, title, summary, initialProps, controls)((p: Props) => yourDef(p))`.

After changing `SourceTag.scala`, run `./sbt exampleUiGallery/clean` first: the incremental
compiler doesn't recompile the stories when only the macro changes.

`GalleryTest` fails if an example has no region or doesn't mount, or a page has no API.
