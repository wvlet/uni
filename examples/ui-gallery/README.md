# UI gallery: uni-ui components next to their code

A live catalog of [uni-ui](https://wvlet.org/uni/dom/ui) components. Each story shows a
component running, with the Scala code that renders it underneath. The docs embed it on the
[Gallery](https://wvlet.org/uni/dom/gallery) page.

## What it shows

- **Stories as plain `def`s.** Each story is a `def` that returns an `RxElement`, between
  `// #region <id>` and `// #endregion` markers in [`stories/`](src/main/scala/example/gallery/stories/).
  `project/StoryRegions.scala` copies each block into the app at build time, so the code panel
  shows exactly the code that runs.
- **A shell built with uni-ui**, styled after uni-harness. An icon rail and a `Tree` in a resizable
  `FoldablePane` list the stories. A `TabList` switches between Preview, Code, and Split, where
  the code sits in a second `FoldablePane` with a `Splitter`. ⌘K opens a `CommandPalette` over
  the stories, and a `Menu` sets the `ColorScheme`.
- **Code and UI side by side.** The chips under a story's title name the uni-ui components its
  code uses. Hovering one outlines the component in the preview and marks the code lines that
  create it (`Spotlight` in [`StoryView.scala`](src/main/scala/example/gallery/StoryView.scala)).
- **State in the URL.** `#/overlays/menu` names the story, `?view=preview`, `code`, or `split`
  picks the view, `?theme=dark` or `?theme=light` forces a color scheme, and `?embed` shows only
  the story card for an iframe. An embedding page switches the theme with
  `postMessage({ type: 'uni-gallery-theme', theme: 'dark' })`, which keeps the story's state.

Source: [`Gallery.scala`](src/main/scala/example/gallery/Gallery.scala),
[`StoryView.scala`](src/main/scala/example/gallery/StoryView.scala),
[`CodeView.scala`](src/main/scala/example/gallery/CodeView.scala), and
[`Story.scala`](src/main/scala/example/gallery/Story.scala).

## Run

```bash
./sbt exampleUiGallery/gallerySite   # stages index.html, the CSS, and main.js in target/site
python3 -m http.server -d examples/ui-gallery/target/site 8080
# open http://localhost:8080
./sbt exampleUiGallery/test          # mounts every story in headless Chromium
```

`./sbt exampleUiGallery/galleryDocs` links with `fullLinkJS` into `docs/public/gallery`, where
`pnpm docs:dev` and the docs build pick it up.

## Add a story

1. Write a `def` that returns an `RxElement` in one of the `stories/*.Stories` objects, between
   `// #region <group>/<name>` and `// #endregion`. Keep everything the demo uses inside the block.
2. Add a `Story("<group>/<name>", title, summary, () => yourDef)` to that object's `all`.

`GalleryTest` fails if a story has no region or doesn't mount.
