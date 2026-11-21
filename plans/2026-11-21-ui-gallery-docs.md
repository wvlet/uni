# A live component gallery for the uni-ui docs

## Goal

Readers of `docs/dom/` see only code today. Embed a running gallery, built with uni-ui itself in the
style of uni-harness, that shows a component next to the Scala code that produced it. A reader
should be able to point at a part of the UI and find the line that made it, or point at a line and
see what it makes.

## What other frameworks do

| Framework | Gallery | Most striking part | What to take |
|---|---|---|---|
| Storybook | sidebar tree of stories, canvas, Controls/Docs tabs | each story is a named export, so one URL per state; `iframe.html?id=...&singleStory=true` embeds one story in any doc site | one URL per story, embeddable in a page |
| WinUI 3 Gallery | NavigationView sidebar → sample page | every sample is live control + Options panel + expandable XAML/C# source; `Substitutions` write the option values into the shown source | an options panel that rewrites the code |
| Mantine docs | `<Demo>` blocks in MDX | "configurator" demos: toggling a prop updates the component and the JSX at the same time | the code always matches what's on screen |
| shadcn/ui, MUI, Chakra | Preview / Code tabs on each component page | short, copyable examples, one per variant | Preview/Code tabs for inline use |
| egui (egui.rs) | the whole demo app running as WASM, with windows chosen from a side panel | the gallery is built with the toolkit itself, and every window links to its source | gallery built with Uni and linked to its source (our uni-harness angle) |
| GTK Demo / GNOME Workbench | sidebar list; Info / Source tabs; Workbench edits code and live-reloads | each demo is a small self-contained file you can copy | one small file per demo |
| Ark UI / Radix | an "Anatomy" section on each component page | names every `data-part` and shows where it sits in the component | **anatomy mode, which maps directly to Uni's `data-slot`** |
| Laminar (demo.laminar.dev) | sidebar of examples with source next to each | the Scala.js precedent: users of Scala UI libraries already expect live demos with their source | Scala.js users expect this |
| Histoire | Vite-native story explorer | works inside a Vite/VitePress setup | how to embed in VitePress (iframe + `<ClientOnly>`) |

## What would set Uni's gallery apart

Uni components already expose what a gallery needs:

1. **Anatomy overlay from `data-slot`.** Every part carries `data-slot` and `data-state`. When you
   hover a part, the gallery outlines it and labels it with its slot name and state
   (`trigger · data-state=open`). It also highlights the code line that rendered it, and hovering
   the code highlights the part. Ark UI and Radix draw a static picture of this; Uni can show it
   live with no extra metadata.
2. **The code shown is the code run.** Each story is a Scala `def` between `// #region name`
   markers. A build step pulls out that region, so the code panel cannot drift from the demo.
   `docSnippets` compiles our Markdown the same way.
3. **Controls write the code.** An options panel (variant, size, disabled, RTL, light/dark theme)
   is a set of `RxVar`s. The demo reads them, and the code panel re-renders the `withXXX(...)`
   calls from them, as WinUI's `Substitutions` and Mantine's configurator do.
4. **Agent view tab.** A third tab, next to Preview and Code, shows what `UiAgent` reads from the
   story. This makes the "Readable by agents" claim visible.
5. **A theme switch that only changes `--uni-*` variables.** The demo restyles while the Scala code
   stays the same, which shows the split between behavior and look.

## Proposed design

- **`examples/ui-gallery`**: a Scala.js app in the root build (same pattern as `exampleWebUi`, see
  `adr/2026-10-27-in-build-examples.md`). Its layout follows uni-harness: a `Tree` sidebar grouped
  by the catalog pages (Controls, Overlays, Collections, Layout & Dock), a canvas, a `Dock` with
  Code / Anatomy / Agent / Controls tabs, and a `CommandPalette` (⌘K) to jump to a story.
- **Stories**: `case class Story(id: String, title: String, render: StoryCtx => RxElement)`, one
  small file per component. The route is `#/overlays/menu/nested`.
- **Two ways to embed it in the docs**
  - A full gallery page `docs/dom/gallery.md` that iframes the whole app (sidebar included). This is
    the showcase.
  - `<UiStory id="overlays/menu/nested" height="320" />`, a Vue component in
    `docs/.vitepress/theme/components/` that iframes `?story=...&embed=1` (canvas plus a Code tab,
    no sidebar) at the matching spot on `controls.md`, `overlays.md`, and the other pages. It uses
    `loading="lazy"` and passes VitePress's dark-mode class through to the frame.
- **Build**: `doc.yml` today runs only `pnpm docs:build`. Add a JDK + `./sbt exampleUiGallery/fullLinkJS`
  step that writes to `docs/public/gallery/`, and keep a `pnpm docs:dev` path that works without it:
  the placeholder says "run `./sbt ~exampleUiGallery/fastLinkJS`".
- **Tests**: a `PlaywrightJSEnv` test that mounts every story and runs `checkPattern`. A broken story
  then fails CI just as an example would.

## Phases

1. Gallery app with sidebar, canvas, and source tab (region extraction), with 6–8 stories (Button,
   Tabs, Menu, Dialog, Select, Tree, DataTable, Dock), plus the `gallery.md` page.
2. `<UiStory>` embeds on each catalog page, and the doc.yml build step.
3. Anatomy overlay with two-way code↔UI highlighting (needs a slot→source-line map: record the
   call-site line with an `inline` + `sourcecode`-style position macro, or match by slot name).
4. Controls panel that rewrites the code, Agent tab, and theme switcher.

## Decisions (2026-11-21)

- A new example, `examples/ui-gallery`, separate from uni-harness.
- Phase 3 can add a source-position macro to map parts to code lines exactly.
- The docs build sets up a JDK and links the gallery with sbt (`exampleUiGallery/galleryDocs`).

## Status

Phase 1 is done: 12 stories in 4 groups, region extraction (`project/StoryRegions.scala`),
`docs/dom/gallery.md` with `<UiGallery>`, and the doc.yml step. `<UiGallery story=... embed>`
already works, so phase 2 is mostly adding embeds to the catalog pages. Known gap: switching the
docs theme reloads the frame and goes back to the first story. Phase 2 should keep the story, for
example by sending the theme with `postMessage` instead of changing `src`.

Phase 2 (2026-11-21): a redesign after uni-harness (icon rail, aurora backdrop, glass card,
gradient title), a Preview / Code / Split view switch (`TabList`, with the code in a resizable
`FoldablePane`), component chips that outline a component in the preview and mark the code lines
that create it (an early, name-based version of phase 3's anatomy view), inline embeds on the
Controls, Overlays, Collections, and Layout pages, and theme switches by `postMessage`, so an
embedded story keeps its state. Phase 3's source-position macro would make the chip-to-line
mapping exact instead of by name.

Phase 3 (2026-11-21): the `SourceTag` macro (`adr/2026-11-21-ui-gallery-source-tags.md`) tags
each component with its source line, which drives exact two-way links: hover a line to outline
what it creates (with name, line, slot, and state), turn on Inspect and hover the preview to mark
the line, and chips from the macro's tag list. `Story.playground` adds controls for Button and
Input props whose values are written into the shown code, with changed lines flashing. An Agent
view installs a `UiAgent` over the story, lists its snapshot, and runs actions through it.

Phase 4, part 1 (2026-11-21): a page per component, after ui-sdk.dev's component docs. 16 pages
and 41 examples, with Controls (Button, Input, Textarea, Tabs, Badge & Kbd) and Overlays
(Popover, Tooltip, Menu, Dialog, Select, Toast) filled out; Collections and Layout keep one
example each for part 2. Each page has an on-this-page list, a card per example with a "what to
try" line and an event log of `onChange` changes with their source, then Parts (`data-slot` and
`data-state` read from the running page), API (listed at compile time by `ApiDoc`, with doc
comments from TASTy via `-Xread-docs`), and Accessibility (`checkPattern` on the live examples).
Routes are `#/<page>` and `#/<page>/<example>`; earlier story ids map to examples. The view
switch became a group of toggle buttons: as a `TabList` with no tab panels it failed the Tabs
pattern's `aria-controls` check, which the new accessibility section caught.

Part 2: more examples for Tree, Data table, Virtual list, Dock, and Foldable pane, plus pages for
Command palette, Splitter, and Status bar.

