# 12. Calling NPM Modules from Scala.js

You're building a browser app with Uni ([Chapter 11](./ch11-00-cross-platform)),
and you need something the JavaScript world already solved: a Markdown
renderer, a charting library, a date picker. It's on npm, written in
JavaScript, with no Scala types. How do you call it from Scala 3 without
giving up type safety — and without dragging in a code generator?

The answer is a **facade**: a few lines of Scala that declare the shape
of the JavaScript you actually use. You write it by hand, and it is
smaller than you expect.

## A facade is a typed declaration

Say you want [`marked`](https://www.npmjs.com/package/marked), which turns
Markdown into HTML. In JavaScript you'd write:

```javascript
import { marked } from 'marked'
marked.parse('# Hello')   // "<h1>Hello</h1>"
```

The Scala.js facade for exactly that is:

```scala
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("marked", "marked")
object Marked extends js.Object:
  def parse(markdown: String): String = js.native
```

And you call it like any Scala object:

```scala
val html = Marked.parse("# Hello")   // "<h1>Hello</h1>"
```

That's the whole thing. Four pieces are doing the work:

- **`@JSImport("marked", "marked")`** says where the value comes from: the
  named export `marked` from the npm package `marked`. Scala.js turns this
  into a real `import { marked } from "marked"` in its JavaScript output.
- **`@js.native`** marks this as a declaration, not an implementation —
  the code lives on the JavaScript side.
- **`extends js.Object`** types it as a JavaScript object.
- **`= js.native`** is the method body you don't write, because the real
  one is in the npm package.

You declared *only* `parse`, because that's the only method you call. A
facade describes your usage, not the whole library — if you later need
`marked.lexer`, you add one line.

## Default exports and other shapes

Packages export values in a few shapes, and `@JSImport` has a form for
each. A **named** export uses its name (as above). A **default** export
uses `JSImport.Default`:

```scala
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// JavaScript:  import confetti from 'canvas-confetti'; confetti()
@js.native
@JSImport("canvas-confetti", JSImport.Default)
object Confetti extends js.Object:
  def apply(): Unit = js.native
```

A method that *returns* a JavaScript object gets its own `@js.native`
trait — no `@JSImport`, because you don't import it, you receive it:

```scala
@js.native
@JSImport("some-db", JSImport.Default)
object DB extends js.Object:
  def open(path: String): Connection = js.native

@js.native
trait Connection extends js.Object:
  def query(sql: String): js.Array[js.Any] = js.native
```

JavaScript values map to `js`-prefixed types: `js.Object`, `js.Array[A]`,
`js.Function`, `js.Dictionary[A]` for string-keyed maps, and `js.Any`
when you genuinely don't care. When even a typed facade is more ceremony
than a one-off call deserves, `js.Dynamic` is the escape hatch — fully
untyped, method calls resolved at runtime:

```scala
import scala.scalajs.js
val mod = js.Dynamic.global.someGlobal
mod.doThing("arg")   // no types, no facade — use sparingly
```

Reach for `js.Dynamic` to prototype; promote to a real facade once you
know which three methods you actually call.

## Why not ScalablyTyped?

[ScalablyTyped](https://scalablytyped.org/) generates Scala facades
automatically from a package's TypeScript definitions. It is impressive,
and for this book's purposes it is the wrong default.

The cost is out of proportion to the need. A typical npm package ships
thousands of lines of `.d.ts`; ScalablyTyped turns that into a large
generated Scala library that your build must convert and compile, every
clean build, whether or not you call more than three of its functions.
That's slower builds, a heavier toolchain, and generated names you didn't
choose leaking into your code.

What you actually need from `marked` was one method. The hand-written
facade for it is four lines you can read, that compile instantly, and
that say exactly what your program depends on. The trade is real — you
write the declarations yourself instead of having them generated — but
for the handful of calls a typical app makes into a JS library, four
honest lines beat ten thousand generated ones. Write the facade.

## What you have, what comes next

You can now call JavaScript libraries from Scala with types you control:

- A **facade** is a `@js.native @JSImport(...)` declaration of just the
  parts of a package you use.
- **`JSImport.Default`** for default exports, **`"name"`** for named ones;
  returned objects get their own `@js.native` trait.
- **`js.Dynamic`** is the untyped escape hatch for prototyping.
- Skip **ScalablyTyped** — a few hand-written lines beat a generated
  library for the calls a real app makes.

But there's a missing piece. Your facade says `@JSImport("marked", …)`,
and Scala.js faithfully emits `import { marked } from "marked"` — yet
nothing has *installed* `marked` or resolved that import for the browser.
That is the bundler's job. Next, [Chapter 13](./ch13-00-vite) wires it all
together with Vite.

[← 11. One Codebase, Three Runtimes](./ch11-00-cross-platform) | [Next → 13. Bundling with Vite](./ch13-00-vite)
