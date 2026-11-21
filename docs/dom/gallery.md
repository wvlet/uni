---
description: A live gallery of uni-ui components for Scala.js, each shown next to the Scala code that renders it.
aside: false
---

# Gallery

Each uni-ui component below is running live, with the Scala code that renders it underneath. Pick
a component in the sidebar or press ⌘K (Ctrl+K) to search. The preview and the code are panels of
a [`Dock`](./layout#dock): drag a tab to put the code beside the preview.

<UiGallery height="760px" />

The gallery is built with uni-ui itself. The sidebar is a `Tree` in a `FoldablePane`, the search
is a `CommandPalette`, and the theme switch is a `Menu` over `ColorScheme`. Open it
[on its own page](/gallery/index.html) to use the whole window.

## The code is the code that runs

Each story is one `def` between `// #region` markers in
[`examples/ui-gallery`](https://github.com/wvlet/uni/tree/main/examples/ui-gallery):

```scala skip
// #region controls/buttons
def buttons: RxElement =
  val clicks = Rx.variable(0)
  div(
    Button("Primary").primary.onClick(() => clicks.update(_ + 1)),
    p(clicks.map(n => s"Clicked ${n} times"))
  )
// #endregion
```

At build time, `project/StoryRegions.scala` copies each block into the app, so the code panel
can't drift from the demo. The `def` also compiles with the rest of the build. The file name
above the code links to those lines on GitHub. Each story assumes the imports from
[Setup](./#setup).

## Run it locally

```bash
./sbt exampleUiGallery/gallerySite   # stages examples/ui-gallery/target/site
python3 -m http.server -d examples/ui-gallery/target/site 8080
```

The URL holds the gallery's state: `#/overlays/menu` opens a story, `?theme=dark` forces a color
scheme, and `?embed` leaves out the header and sidebar for an iframe.
