# 13. Bundling with Vite

Your facade from [Chapter 12](./ch12-00-npm-facades) says
`@JSImport("marked", "marked")`, and Scala.js dutifully emits
`import { marked } from "marked"` in its output. But nothing has installed
`marked`, and a browser has no idea what the bare name `"marked"` resolves
to. Something has to fetch the package, follow its imports, and produce
JavaScript a browser can load. That something is a **bundler**, and
[Vite](https://vitejs.dev/) is a good one.

This chapter wires the two worlds together: Scala.js on one side emitting
ES module imports, Vite on the other resolving them against npm.

## Step 1: emit ES modules

For `@JSImport` to become a real `import` a bundler can follow, Scala.js
must emit ES modules. Set the module kind once in `build.sbt`:

```scala
import org.scalajs.linker.interface.ModuleKind

lazy val app = project
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )
```

This is the one setting that makes the whole chain work. Without it,
Scala.js can't emit the `import { marked } from "marked"` that Vite needs
to see. (Uni's own JS builds use exactly this setting.)

`sbt app/fastLinkJS` then produces a folder of ES modules under
`target/scala-3.x/app-fastopt/`, with `main.js` as the entry. The
production command is `app/fullLinkJS`, which emits an optimized build
under `app-opt/`.

## Step 2: install the npm package

Vite resolves `"marked"` the npm way — from `node_modules`. So install it,
along with Vite itself:

```bash
$ npm install marked
$ npm install --save-dev vite
```

This is the half of the contract Scala can't fulfill: the facade *names*
the package, and `npm install` *provides* it.

## Step 3: a Vite entry point

Vite serves an `index.html` that loads one JavaScript entry module:

```html
<!-- index.html -->
<body>
  <script type="module" src="/main.js"></script>
</body>
```

`main.js` is the seam between the npm world and the Scala.js world. It
imports your styles and any npm modules you want to set up, then pulls in
the Scala.js output to start your app:

```javascript
// main.js
import './style.css'

// Start the Scala.js application (the fastLinkJS output entry module)
import('./target/scala-3.x/app-fastopt/main.js')
```

When Scala.js code runs and hits your `Marked.parse(...)` facade, the
emitted `import { marked } from "marked"` resolves through Vite to the
installed package. The facade, the linker, and the bundler are now one
pipeline: Scala types on top, npm code underneath.

## Step 4: run it

Two processes, one for each compiler:

```bash
$ sbt "~app/fastLinkJS"   # recompile Scala.js on change
$ npx vite                # dev server with hot reload
```

`vite` serves the app with instant hot-module reload; the `~` keeps
Scala.js relinking as you edit. For production, `sbt app/fullLinkJS`
followed by `npx vite build` emits a minified bundle to `dist/`.

A minimal `vite.config.js` needs almost nothing:

```javascript
import { defineConfig } from "vite"

export default defineConfig({
  base: "./",
})
```

## When a module won't bundle cleanly

Most npm packages bundle without fuss. Two situations need a nudge, and
both have a one-line fix.

**A library that's easier to load as a global.** Some packages (editors,
WASM modules) are simplest to load in `main.js` and hand to Scala.js
through the `window` object. Expose it:

```javascript
// main.js
import { marked } from 'marked'
window.marked = marked
```

and facade it with `@JSGlobal` instead of `@JSImport`:

```scala
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal("marked")
object Marked extends js.Object:
  def parse(markdown: String): String = js.native
```

This `window` bridge is how the
[Wvlet playground](https://github.com/wvlet/wvlet/tree/main/wvlet-ui-playground)
wires its Monaco editor — `main.js` sets `window.MonacoEditor`, and Scala
reads it via `@JSGlobal`.

**A package with a dependency the browser can't use.** A library may pull
in a Node-only module the bundler can't resolve. Point Vite at a
browser-safe replacement with `resolve.alias`:

```javascript
export default defineConfig({
  resolve: {
    alias: {
      // swap a Node-only dep for a browser stub
      "node-only-pkg": "/stubs/browser-stub.js",
    },
  },
})
```

The playground does this for `koffi` (a native-binding library its DuckDB
backend needs on Node but never on the web) — aliasing it to a stub that
throws if anything ever calls it. Reach for `alias` only when a transitive
dependency fights the browser; you won't need it for ordinary UI packages.

## What you have, what comes next

You can now ship a Scala.js app that uses the npm ecosystem:

- **`ModuleKind.ESModule`** makes Scala.js emit imports a bundler can
  follow.
- **`npm install`** provides the package your facade named.
- A **`main.js` entry** imports the Scala.js output and any npm setup;
  Vite resolves the bare imports and serves the app.
- **`@JSGlobal` + `window`** bridges stubborn modules, and
  **`resolve.alias`** swaps out browser-hostile dependencies.

That completes Part VIII — Scala.js is now a full participant in the
JavaScript ecosystem, with types on your side of the boundary and npm on
the other.

Next, [Part IX](./ch14-00-calling-c) does the mirror image for the other
end of the platform spectrum: Scala Native and the C/Rust world — calling
C libraries from Scala, and exposing your Scala as a library that C, C++,
and Rust can call.

[← 12. Calling NPM Modules from Scala.js](./ch12-00-npm-facades) | [Next → 14. Calling C from Scala Native](./ch14-00-calling-c)
