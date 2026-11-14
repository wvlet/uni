---
description: Build browser and desktop UIs in Scala.js with uni-dom (reactive HTML components driven by Rx) and uni-ui (ready-made, accessible components styled by one CSS file).
---

# Web UI

Uni builds browser UIs in Scala.js with two modules:

- **uni-dom** (`wvlet.uni.dom`), in the `uni` artifact: a reactive DOM toolkit. Components are
  plain Scala classes, the HTML/SVG DSL is type-checked, and any [`Rx`](../rx/) value embedded in
  the markup re-renders the affected DOM in place when it changes — no virtual DOM, no separate
  template language.
- **uni-ui** (`wvlet.uni.ui`), in the `uni-ui` artifact: ready-made components built on uni-dom —
  buttons, text fields, tabs, foldable side panes, splitters, overlays, virtualized lists, trees,
  tables, and a dock. A plain CSS file, `uni-ui.css`, styles them with light and dark themes.

The same code runs in a browser and in an [Electron](../http/electron) desktop app.

::: warning Scala.js only
These modules target the browser and have no JVM or Scala Native counterpart. (The uni-dom
rendering core ships in `uni-core`, which `uni` depends on, so `uni-test` can test components.)
:::

## Setup

Add the artifacts to a Scala.js project. `uni` brings uni-dom; add `uni-ui` for the components:

```scala sbt
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"    % "<version>",
  "org.wvlet.uni" %% "uni-ui" % "<version>"
)
```

Every example in these pages assumes these imports. The `given` import enables embedding
`String`, `Int`, `Rx`, `Seq`, and `Option` directly as children/attribute values;
`implicitConversions` is required by Scala 3 for those conversions. Drop `wvlet.uni.ui.*` if you
use uni-dom alone.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions
```

### Loading uni-ui.css

Load `uni-ui.css` once. It ships in the jar at `wvlet/uni/ui/uni-ui.css`. The
[sbt-uni plugin](../build/sbt-uni#uni-ui-stylesheet) copies it out of the jar on every compile, so
the file always matches the uni-ui version your app links:

```scala sbt
// project/plugins.sbt
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")

// build.sbt
lazy val app = project
  .enablePlugins(ScalaJSPlugin, UniPlugin)
  .settings(uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css")
```

With Vite and `@scala-js/vite-plugin-scalajs`, `vite dev` and `vite build` compile the Scala.js
project, so the copy stays fresh. Import it from your app's stylesheet:

```css
@import './uni-ui.css';
```

Every rule sits in the `uni` cascade layer, so unlayered app CSS always overrides it. If your app
uses other layers, declare their order once, before any import. With Tailwind CSS v4:

```css
@layer theme, base, uni, components, utilities;
@import 'tailwindcss';
@import './uni-ui.css';
```

Tailwind utilities then win over uni-ui's rules, so `Button("Save")(cls += "w-full")` works.

## Hello World

Extend `RxElement` and implement the single abstract method `def render`, then
mount it with the `renderTo` extension:

```scala
class Hello extends RxElement:
  override def render: RxElement = div("Hello, World!")

Hello().renderTo("app")   // mounts into <div id="app"> (created if absent)
```

`renderTo(nodeId)` returns an [`RxDomNode`](./rxelement#mounting-and-teardown). If no element
with that id exists, it creates a `<div id=nodeId>` and appends it to
`document.body`.

uni-ui components are `RxElement`s too, so they mix with plain HTML tags. A component takes the
`Rx` values it reads and writes user input back into them:

```scala
class Greeter extends RxElement:
  private val name = Rx.variable("")
  override def render: RxElement =
    div(
      Input(name).withLabel("Name").withPlaceholder("Your name"),
      p(name.map(n => s"Hello, ${if n.isEmpty then "World" else n}!"))
    )

Greeter().renderTo("app")
```

## Where to go next

| Page | What it covers |
|------|----------------|
| [RxElement & HTML DSL](./rxelement) | Components, tags and attributes, embedding `Rx`, keyed lists, form binding, mounting, and browser integrations (`Router`, `Storage`, `Keyboard`, ...) |
| [Components](./ui) | uni-ui's conventions, and the [controls](./controls), [overlays](./overlays), [collections](./collections), and [layout and dock](./layout) pages |
| [Behaviors](./behaviors) | The interaction logic the components are made of, for building your own widgets |
| [Changes & Parts](./changes) | `onChange` on every component, `ChangeSource` and `ChangeCause`, and `data-slot` part names |
| [Agents](./agents) | `UiAgent`: let an AI agent read and operate the screen, over MCP or WebMCP |
| [Theming](./theming) | Light and dark schemes and the `--uni-*` CSS variables |
| [Testing](./testing) | `DomTestSupport`, `fireEvent`, and WAI-ARIA pattern checks with `checkPattern` |

## Desktop apps

The same views run in [Electron](../http/electron): the renderer is a Scala.js app built with these
modules, and it calls the main process through typed RPC. The
[Electron tutorial](../http/electron-tutorial) builds one step by step, and
[`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness) is a full
workspace shell made with uni-ui and Tailwind CSS.
