# Compile-checking Markdown snippets in place (docSnippets)

## Context

The `uni` skill (`plugins/uni/skills/uni/`) teaches agents the API through Scala snippets, and AGENTS.md requires them to compile. They were verified once with a scratch test, so nothing caught drift. `book-examples` checks the book by hand-copying snippets into Scala files, but the copy can drift from the Markdown it mirrors.

## Decision

- The unpublished `docSnippets` project (`docs/snippets/`, in `projectJVM`) has a `Test / sourceGenerators` task, `project/MarkdownSnippets.scala`, that turns each Markdown file's `scala` fences into one Scala file. A snippet that stops compiling fails `projectJVM/test`.
- The fences of one file form a chain (mdoc semantics): each fence sees earlier fences. A chain is `object ChainN extends Assertions, TestControl, PropertyCheck`, not `UniTest`, because uni-test discovers `UniTest` objects (`UniTestModuleFingerprint`) and would run every top-level expression. Mixing in the assertion traits lets `shouldBe`/`skip`/`forAll` fragments compile.
- Modifiers go in the fence info string (`scala sbt|skip|test|reset`). GitHub, VitePress (markdown-it takes the first word as the language), and raw-reading agents ignore them. An unknown modifier fails the build, so a typo cannot silently skip a fence. VitePress options (`{1,3}`, `:line-numbers`) are ignored, so `docs/` pages can be added later.
- `scala test` fences become top-level `UniTest` classes emitted after the chains. They import the chain at that point, and their tests run. Emitting them after the chains keeps a test fence from splitting its chain. A `UniTest` class *defined inside* a regular fence is compiled but not run, because sbt does not discover classes nested in objects.
- A fence starting with `package` becomes its own file, since a package clause cannot be nested. The file's chains import that package.
- Free names come from hand-written stubs in the generated package `snippets.<prefix>.<path segments>`. Same-package definitions resolve without imports, and a fence's own definitions shadow them. Hidden setup blocks in the Markdown were rejected because skills are read raw and the setup would cost agent tokens.
- The generator reads files directly, so it is wrapped in `Def.uncached`; otherwise sbt 2 would reuse a cached result after a Markdown edit. It writes only changed files so incremental compilation stays cheap, deletes stale outputs, and fails when two Markdown files map to the same package.
- `plugins/uni/skills/**` is in the CI `src` path filter so a skill-only PR still runs the check.

## Consequences

- Compile errors point at the generated file. The `// <file>.md:<line>` comment above the failing code names the fence.
- A `test` fence that reads a chain value initializes the whole chain object, running its top-level expressions. Keep test fences to stubs and chain types.
- mdoc was not used: it has no sbt 2.x release and would add a build dependency.
- To cover `docs/` pages, add a `root -> package prefix` entry in `build.sbt` plus stubs.
