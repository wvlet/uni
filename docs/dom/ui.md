# UI Components (uni-ui)

`uni-ui` is a set of ready-made components for [RxElement](./) apps on Scala.js: buttons, text
fields, tabs, foldable side panes, and splitters. The same components work in a browser and in an
[Electron](../http/electron) app. A plain CSS file, `uni-ui.css`, styles them with light and dark
themes.

Components keep their behavior and their look apart. Scala code sets ARIA roles, focus, keyboard
handling, and `data-state` attributes. `uni-ui.css` styles those with `--uni-*` CSS variables, so an
app restyles a component by setting variables, not by changing Scala code.

## Setup

Add the artifact to a Scala.js project:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-ui" % "<version>"
```

Load `uni-ui.css` once. It ships in the jar at `wvlet/uni/ui/uni-ui.css`. The
[sbt-uni plugin](../build/sbt-uni#uni-ui-stylesheet) copies it out of the jar on every compile, so
the file always matches the uni-ui version your app links:

```scala
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

## Components

Each component is an `RxElement` that takes the `Rx` values it reads. Options are `withXXX` methods.
Pass extra attributes as modifiers, and use `cls +=` so the component's own classes stay.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.ui.*

val draft = Rx.variable("")
val busy  = Rx.variable(false)

div(
  Textarea(draft).withRows(4).withPlaceholder("Write a message"),
  Button("Send").primary.withDisabled(busy).onClick(() => send(draft.get)),
  IconButton("Close", closeIcon).onClick(() => close())
)
```

| Component | What it does |
|-----------|--------------|
| `Button(content*)` | `<button type="button">`. Variants are `.primary`, `.ghost`, and `.danger`, plus `.small`; set the state with `.withDisabled(rx)` and the action with `.onClick(f)` |
| `IconButton(label, icon)` | A square ghost button; `label` is its accessible name |
| `Input(text)` / `Textarea(text)` | Bound both ways to an `RxVar[String]`. Use `.withPlaceholder`, `.withLabel`, `.withInvalid(rx)` (sets `aria-invalid`), and `.withDisabled` |
| `TabList(selected, label)(Tab(value, content*)*)` | Tabs bound to an `RxVar[A]`. Arrow keys, Home, and End move the selection |
| `TabPanel(tabs)(a => element)` | Renders the selected tab's content for the `TabList` `tabs`, linked to it with `aria-controls` and `aria-labelledby` |
| `FoldablePane(open, width, edge, splitter)(content*)` | A side pane that folds to zero width; its content never reflows |
| `Splitter(size, range, edge, label)` | A resize grip. Drag it, or press the arrow keys (Shift for bigger steps); double-click or Enter resets |
| `Badge(content*)`, `Badge.dot(label)` | A count or a status dot; `.withTone(BadgeTone.Accent)` |
| `Kbd(key)` | A key cap, `<kbd>` |
| `StatusBar(content*)` | A bar along the bottom; `StatusBar.spacer` pushes the rest to the end |

A resizable sidebar:

```scala
val open  = Rx.variable(true)
val width = Rx.variable(272)
val range = SizeRange(min = 208, default = 272, max = 480)

FoldablePane(open, width, Edge.Right, Some(Splitter(width, range, Edge.Right, "Resize sidebar")))(
  ChatList()
)
```

`Edge` names the pane's inner side, the one that faces the main content: `Edge.Right` for a left
sidebar, `Edge.Left` for a right-hand dock.

## Themes

`uni-ui.css` reads `data-theme` on `<html>`: `light` or `dark` forces a scheme, and no attribute
follows the OS setting. `ColorScheme` sets the attribute from an `Rx`:

```scala
val scheme = Storage.local("app.colorScheme", ColorScheme.System)
ColorScheme.bind(scheme)
scheme := ColorScheme.Dark
```

To change colors, set the variables after `uni-ui.css`:

```css
:root {
  --uni-accent: #6d28d9;
  --uni-accent-2: #8b5cf6;
  --uni-radius: 4px;
}
```

| Variable | Used for |
|----------|----------|
| `--uni-canvas`, `--uni-surface`, `--uni-panel`, `--uni-raised` | Backgrounds, from the window down to raised controls |
| `--uni-fg`, `--uni-muted`, `--uni-faint` | Text, from primary to placeholder |
| `--uni-line`, `--uni-hover` | Borders and hover fills |
| `--uni-accent`, `--uni-accent-2`, `--uni-accent-fg`, `--uni-accent-soft` | The primary button gradient, its text, and focus rings |
| `--uni-danger`, `--uni-danger-soft` | Danger buttons and invalid fields |
| `--uni-radius-sm`, `--uni-radius`, `--uni-radius-lg` | Corner radii |
| `--uni-font-sans`, `--uni-font-mono`, `--uni-font-size`, `--uni-font-size-sm` | Fonts |

## Testing

Components render into a real DOM with [uni-dom testing](./#testing-components):

```scala
class SendButtonTest extends DomTestSupport:
  test("send runs once per click"):
    var sent = 0
    val m    = mount(Button("Send").onClick(() => sent += 1))
    fireEvent.click(m.get("button"))
    sent shouldBe 1
```

[`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness) builds a full
workspace shell with these components and Tailwind CSS.
