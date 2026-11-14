---
description: uni-ui's components for Scala.js apps — controls, overlays, virtualized collections, and a dock — with ARIA, keyboard handling, and styling by CSS variables.
---

# Components

`uni-ui` is a set of ready-made components for [RxElement](./rxelement) apps on Scala.js: buttons,
text fields, tabs, foldable side panes, splitters, overlays (popovers, tooltips, menus, dialogs,
selects, and toasts), virtualized lists, trees, and tables, and a dock. The same components work in
a browser and in an [Electron](../http/electron) app. See [Setup](./#setup) to add the artifact and
load `uni-ui.css`.

Components keep their behavior and their look apart. Scala code sets ARIA roles, focus, keyboard
handling, `data-state` attributes, and a `data-slot` name on every part. `uni-ui.css` styles those with `--uni-*` CSS variables, so an
app restyles a component by setting variables, not by changing Scala code.

To build a widget uni-ui doesn't have, start from the [behaviors](./behaviors) the components are
made of: keyboard navigation, typeahead, selection, focus handling, and stick-to-bottom scrolling.

## Conventions

Each component is an `RxElement` that takes the `Rx` values it reads. Options are `withXXX` methods.
A component takes one parameter list of the arguments it cannot work without. Name the functions,
triggers, and multi-value arguments at the call site, so each one says what it is (`key = _.id`,
`render = ...`, `trigger = ...`). An argument that holds several values, such as menu entries or
dialog content, is a `Seq`: `entries = Seq(...)`.
Pass extra attributes as modifiers, and use `cls +=` so the component's own classes stay.

- **State you own.** Components write user input into the `RxVar`s you pass them, and
  [`onChange`](./changes) sees each such change first and can cancel it.
- **Accessible by default.** Components set ARIA roles and keyboard handling following the WAI-ARIA
  Authoring Practices, mirror ArrowLeft and ArrowRight in right-to-left layouts, and pass
  [`checkPattern`](./testing#checking-aria-patterns) in uni-ui's own tests.
- **Readable by agents.** [`UiAgent`](./agents) lets an AI agent read and operate the components on
  screen.

## The components

| Page | Components |
|------|------------|
| [Controls](./controls) | `Button`, `IconButton`, `Input`, `Textarea`, `TabList`/`TabPanel`, `Badge`, `Kbd` |
| [Overlays](./overlays) | `Popover`, `Tooltip`, `Menu`, `ContextMenu`, `Dialog`, `AlertDialog`, `Select`, `Toaster` |
| [Collections](./collections) | `VirtualList`, `Tree`, `DataTable`, `CommandPalette`; multi-select and reordering |
| [Layout & Dock](./layout) | `FoldablePane`, `Splitter`, `StatusBar`, `Dock` |

[`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness) builds a full
workspace shell with these components and Tailwind CSS.
