---
description: A live gallery of uni-ui components for Scala.js, each shown next to the Scala code that renders it.
aside: false
---

# Gallery

Each uni-ui component below is running live, with the Scala code that renders it. Pick a component
in the sidebar or press ⌘K (Ctrl+K) to search.

- **Preview, Code, Split, Agent.** Switch between the component, its code, both side by side,
  and what an AI agent sees. Drag the edge between the panes to resize them.
- **From the UI to the code and back.** Hover a line of code to outline what it creates in the
  preview, with the component's name, line, slot, and state. Turn on Inspect and hover the
  preview to find the line that made each part. Hover a chip under the title, such as `Button`,
  to mark every place that creates one.
- **Playgrounds.** Button props and Input props have controls. Change one, and the preview and
  the code change together; the lines that changed flash.
- **Agent.** The Agent view installs a [`UiAgent`](./agents) over the story and lists each
  component with its ref, kind, value, and actions. Press an action, and it runs through the
  agent, as an MCP client's call would. JSON shows the text `UiAgentApi.snapshot` returns.

<UiGallery height="780px" />

The gallery is built with uni-ui itself. The sidebar is a `Tree` in a `FoldablePane`, the rail's
buttons show `Tooltip`s, the view switch is a `TabList`, the code sits in a second `FoldablePane` with a
`Splitter`, the search is a `CommandPalette`, and the theme switch is a `Menu` over
`ColorScheme`. Open it [on its own page](/gallery/index.html) to use the whole window.

## The code is the code that runs

Each story is one `inline def` between `// #region` markers in
[`examples/ui-gallery`](https://github.com/wvlet/uni/tree/main/examples/ui-gallery):

```scala skip
// #region controls/buttons
inline def buttons: RxElement =
  val clicks = Rx.variable(0)
  div(
    Button("Primary").primary.onClick(() => clicks.update(_ + 1)),
    p(clicks.map(n => s"Clicked ${n} times"))
  )
// #endregion
```

At build time, `project/StoryRegions.scala` copies each block into the app, so the code panel
can't drift from the demo. At compile time, the gallery's `SourceTag` macro finds each uni-ui
component the story creates and tags it with its name and line (`data-src-kind`,
`data-src-line`), which is how the preview and the code find each other. The `def` is `inline`
so the macro can see its code; copied into an app, it works as a plain `def`. The `def` also compiles with the rest of the build. The file name
above the code links to those lines on GitHub. Each story assumes the imports from
[Setup](./#setup).

## Run it locally

```bash
./sbt exampleUiGallery/gallerySite   # stages examples/ui-gallery/target/site
python3 -m http.server -d examples/ui-gallery/target/site 8080
```

The URL holds the gallery's state: `#/overlays/menu` opens a story, `?view=code` (or `preview`,
`split`) picks the view, `?theme=dark` forces a color scheme, and `?embed` shows only the story
card, for an iframe. The catalog pages embed single stories this way, with
`<UiGallery story="overlays/menu" embed height="380px" />`.
