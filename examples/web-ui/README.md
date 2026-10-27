# Web UI: a todo list with RxElement

A browser todo list written in Scala.js with `wvlet.uni.dom`. There is no bundler: Scala.js emits
an ES module and a static `index.html` loads it.

## What it shows

- **`RxElement` components**: `TodoApp` overrides `render` and builds markup with `div`, `form`,
  `input`, `ul`, and friends from `wvlet.uni.dom.all.*`.
- **Reactive state**: `Rx.variable` holds the item list. Embedding `items.map(...)` in the markup
  re-renders just that part when the list changes, with no manual DOM updates.
- **Event handlers and two-way binding**: `onclick`, `onchange`, `onsubmit`, and
  `value.bind(newItem)` for the text input.
- **Mounting**: `TodoApp().renderTo("app")` from a Scala.js `main`.

Source: [`TodoApp.scala`](src/main/scala/example/webui/TodoApp.scala) and
[`index.html`](index.html).

## Run

```bash
./sbt exampleWebUi/fastLinkJS                 # writes examples/web-ui/target/js/main.js
cd examples/web-ui && python3 -m http.server 8000
# open http://localhost:8000
```

Browsers load ES modules only over HTTP, so open the page through a static server, not `file://`.
CI compiles this example; it does not run it in a browser.

## Use it in your own build

```scala
// project/plugins.sbt
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

// build.sbt
enablePlugins(ScalaJSPlugin)
scalaJSUseMainModuleInitializer := true
scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
libraryDependencies += "org.wvlet.uni" %% "uni" % "<version>"
```

For a dev server with hot reload, a production bundle, or Tailwind, use Vite with
`vite-plugin-scalajs`, as in the [`electron-app`](../electron-app/) example.
