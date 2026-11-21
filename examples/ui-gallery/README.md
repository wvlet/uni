# UI gallery: uni-ui components next to their code

A live catalog of [uni-ui](https://wvlet.org/uni/dom/ui) components. Each story shows a
component running, with the Scala code that renders it underneath. The docs embed it on the
[Gallery](https://wvlet.org/uni/dom/gallery) page.

## What it shows

- **Stories as plain `inline def`s.** Each story is an `inline def` that returns an `RxElement`,
  between `// #region <id>` and `// #endregion` markers in
  [`stories/`](src/main/scala/example/gallery/stories/). `project/StoryRegions.scala` copies each
  block into the app at build time, so the code panel shows exactly the code that runs.
- **Exact links between the UI and the code.** The `SourceTag` macro
  ([`SourceTag.scala`](src/main/scala/example/gallery/SourceTag.scala)) finds each uni-ui
  component a story creates and tags it with `data-src-kind` and `data-src-line`. Hovering a line
  of code outlines what it creates; with Inspect on, hovering the preview marks the line that made
  each part. The chips under a story's title list its components.
- **A shell built with uni-ui**, styled after uni-harness. An icon rail and a `Tree` in a resizable
  `FoldablePane` list the stories. A `TabList` switches between Preview, Code, Split, and Agent,
  where the code or the agent view sits in a second `FoldablePane` with a `Splitter`. ⌘K opens a `CommandPalette` over
  the stories, and a `Menu` sets the `ColorScheme`.
- **Playgrounds.** `Story.playground` gives a story controls for the props of its `inline def`.
  The code panel shows each `p.<name>` as the control's current value, written as Scala, so the
  code always matches the preview.
- **What an agent sees.** The Agent view ([`AgentView.scala`](src/main/scala/example/gallery/AgentView.scala))
  installs a `UiAgent` over the story, lists its snapshot, and runs actions through it.
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

1. Write an `inline def` that returns an `RxElement` in one of the `stories/*.Stories` objects,
   between `// #region <group>/<name>` and `// #endregion`. Keep everything the demo uses inside
   the block. Scala 3 rejects a case class inside an `inline def`, so put case classes (and enums) above
   it, still inside the region.
2. Add `Story("<group>/<name>", title, summary)(yourDef)` to that object's `all`. For a
   playground, add `Story.playground(id, title, summary, initialProps, controls)((p: Props) => yourDef(p))`.

After changing `SourceTag.scala`, run `./sbt exampleUiGallery/clean` first: the incremental
compiler doesn't recompile the stories when only the macro changes.

`GalleryTest` fails if a story has no region or doesn't mount.
