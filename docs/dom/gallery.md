---
description: A live gallery of uni-ui components for Scala.js, each shown next to the Scala code that renders it.
aside: false
---

# Gallery

**[Open the gallery →](/gallery/index.html){target="_self"}** It is a full-window app, best on its
own page. A smaller copy is embedded below.

The gallery opens on **Get started**, which sets up a Scala.js project with Uni: the build files,
the stylesheet, the imports, and a first app shown live next to its code. After it, every uni-ui
component has a page: several live examples, each next to the Scala code that
renders it, then the component's parts, API, and an accessibility check. Pick a component in the
sidebar or press ⌘K (Ctrl+K) to search components and examples.

- **Examples.** Each card switches between Preview, Code, Split, and Agent (what an AI agent
  sees). The line above it says what to try. Button props and Input props are playgrounds:
  change a control, and the preview and the code change together.
- **From the UI to the code and back.** Hover a line of code to outline what it creates, with
  the component's name, line, slot, and state. Turn on Inspect and hover the preview to find the
  line behind each part. Hover a chip, such as `Button`, to mark every place that creates one.
- **Events.** Under each card, every change the components report through
  [`onChange`](./changes): the key, the old and new value, the reason, and who made it (pointer,
  keyboard, app code, or an agent).
- **Parts.** The `data-slot` parts the examples render and the `data-state` values seen so far,
  read from the running page: the hooks to [style](./theming) them by.
- **API.** The component's options and methods, listed from its declarations and doc comments
  when the gallery compiles.
- **Accessibility.** [`checkPattern`](./testing#checking-aria-patterns), the WAI-ARIA check
  uni-ui's own tests use, run on the live examples. Turn on keys to have it press the keys the
  pattern requires too.

<UiGallery story="button" height="780px" />

The gallery is built with uni-ui itself. The sidebar is a `Tree` in a `FoldablePane`, the rail's
buttons show `Tooltip`s, the code sits in a second `FoldablePane` with a `Splitter`, the search
is a `CommandPalette`, and the theme switch is a `Menu` over `ColorScheme`. Open it
[on its own page](/gallery/index.html) to use the whole window.

## The code is the code that runs

Each example is one `inline def` between `// #region <page>/<example>` markers in
[`examples/ui-gallery`](https://github.com/wvlet/uni/tree/main/examples/ui-gallery):

```scala skip
// #region button/variants
inline def buttonVariants: RxElement =
  val clicks = Rx.variable(0)
  div(
    Button("Primary").primary.onClick(() => clicks.update(_ + 1)),
    p(clicks.map(n => s"Clicked ${n} times"))
  )
// #endregion
```

At build time, `project/StoryRegions.scala` copies each block into the app, so the code panel
can't drift from the demo. At compile time, the gallery's `SourceTag` macro finds each uni-ui
component the example creates and tags it with its name and line (`data-src-kind`,
`data-src-line`), which is how the preview and the code find each other. The `def` is `inline`
in the source so the macro can see its code. The code panel and the copy button drop that
`inline`, so you copy a plain `def buttonVariants: RxElement`. The `def` also compiles with the rest of the build. The file name
above the code links to those lines on GitHub. Each example assumes the imports from
[Setup](./#setup).

## Run it locally

```bash
./sbt exampleUiGallery/gallerySite   # stages examples/ui-gallery/target/site
python3 -m http.server -d examples/ui-gallery/target/site 8080
```

The URL holds the gallery's state: `#/menu` opens a component's page and `#/menu/checked` one of
its examples, `?view=code` (or `preview`, `split`, `agent`) picks the view, `?theme=dark` forces
a color scheme, and `?embed` shows only one example's card, for an iframe. The catalog pages
embed single examples this way, with
`<UiGallery story="menu/basic" embed height="380px" />`.
