# uni-doc-snippets

Compile-checks the `scala` code fences of Markdown files in place, so a snippet
cannot drift from the API. Unlike [`book-examples`](../../book-examples/), nothing
is copied by hand: `project/MarkdownSnippets.scala` generates one Scala file per
Markdown file at compile time.

Covered today: the `uni` skill (`plugins/uni/skills/uni/**/*.md`),
`docs/guide/uni-vs-scala-toolkit.md`, and `docs/core/unitest.md`. A root is a directory or one Markdown file. To cover
more pages, add a root and its package prefix to `docSnippets` in `build.sbt`.

```bash
./sbt docSnippets/Test/compile   # compile every snippet
./sbt docSnippets/test           # also run the `scala test` fences
```

## How fences compile

- The fences of one Markdown file form a chain, emitted as
  `object ChainN extends Assertions, TestControl, PropertyCheck`. Each fence
  sees what earlier fences defined, top-level expressions and `@main` methods
  compile, and nothing in a chain runs.
- A fence that starts with `package` becomes its own source file, and the
  file's chains import that package.
- A compile error points at the generated file under
  `target/out/jvm/scala-<version>/uni-doc-snippets/src_managed/`; the
  `// <file>.md:<line>` comment above the failing code names the fence.

Info-string modifiers (after `scala`; GitHub and VitePress ignore them):

| Modifier | Effect |
|----------|--------|
| `sbt`    | Build definition; not compiled |
| `skip`   | Not compiled: `...` placeholders, Scala.js- or Native-only code |
| `test`   | Body goes in a generated `UniTest` class that imports the chain; its tests run |
| `reset`  | Start a new chain from this fence |

A `test` fence that reads a value of its chain initializes the whole chain
object, so keep test fences to what the stubs and the chain's types provide.

An unknown modifier fails the build. VitePress options (`{1,3}`, `:line-numbers`,
and a code-group title such as `[Uni]`) are ignored. A `UniTest` class defined *inside* a
regular fence is compiled but not run, because test discovery skips classes
nested in an object.

## Stubs

Names a snippet uses without defining (`token`, `handler`, the sbt-uni generated
`UserServiceClient`) are hand-written in `src/test/scala/`, in the package of the
Markdown file: `snippets.<prefix>.<path segments>`, lowercased with non-alphanumerics
replaced by `_` (`references/http-client.md` → `snippets.skill.references.http_client`).
Same-package definitions resolve without imports, and a fence's own definitions
shadow them.

This module is not published (`publish / skip := true`).
