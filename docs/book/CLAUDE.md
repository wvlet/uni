# Writing Guide — The Uni Book

> This file is for contributors (humans and Claude) writing chapters of
> `docs/book/`. It is **not** rendered as a book page. If you are reading
> it inside the site, you took a wrong turn — see `/book/` instead.

## What the Book is for

The Book is a **narrative, mental-model-first guide** to building
real applications with Scala 3 and Uni. It is modeled after
*[The Rust Programming Language](https://doc.rust-lang.org/book/)* ("the
Rust Book").

- `/guide/`, `/core/`, `/http/`, `/agent/`, etc. are **reference**:
  shaped around *APIs*.
- `/book/` is **narrative**: shaped around *tasks, concepts, and
  reasoning*.

A reader should finish the Book able to say, in their own words:
"I know how Uni organizes a Scala 3 application, and I know *why* it is
organized that way." That sentence is the bar every chapter is written
against.

## Audience

Pick one reader and write to them throughout:

- **Has:** working Scala knowledge (reads case classes and traits
  comfortably), sbt installed, a curious mind.
- **Does not have:** experience with Uni, Airframe, or cross-platform
  Scala (JVM / Scala.js / Scala Native).
- **Wants:** to build something that runs, and to *understand why the
  pieces fit the way they do*.

If a sentence requires a reader who is more senior than that, rewrite it.
If it requires a reader who is less, link out and keep moving.

## Core pedagogical principles

These are borrowed from the Rust Book, which is the benchmark.

### 1. Show, then name

Introduce a shape in code before naming the concept. The reader first
sees `Design.newDesign.bindSingleton[Service]` doing something
concrete, *then* we say "this is called a **design**, and here is why it
is shaped that way." Named things land better when the reader has
already seen them work.

### 2. Small programs before big theory

Every part opens with a working program, or extends a program the
reader already has. The reader should never be more than one chapter
away from something they have actually run. Chapter 2 of the Book is a
full end-to-end CLI app built with Uni, on purpose — it is the Uni
analogue of the Rust Book's Guessing Game.

### 3. One new idea per section

A section introduces exactly one concept on top of what the reader
already has. If a section teaches both `Design` *and* `Rx`, split it.

### 4. Say *why*, not just *how*

Uni makes opinionated choices (constructor injection, avoiding
`Try[A]`, `withXxx` config setters, Rx-first async, cross-platform
code). For each one, the Book explains what problem the choice solves,
with concrete failure modes it prevents. A reader who only memorizes
the *how* cannot generalize; a reader who understands the *why* can.

A quick sniff test: if a reader asked *"why not just pass it as a
constructor arg?"*, does the surrounding text answer them? If not,
answer it.

### 5. Cross-platform by default

Most Uni code runs unchanged on JVM, Scala.js, and Scala Native. Treat
that as the default in examples; only call out the platform when it
actually matters. Use callout blocks for platform detours:

```markdown
::: tip JVM-only
This example uses `java.nio.file.Path`. On Scala.js / Native, use the
[FileSystem](/core/filesystem) abstraction instead.
:::
```

### 6. Runnable, not decorative

Every non-illustrative code block must compile against the Uni version
in the repo. Treat snippets as small programs, not wallpaper. If a
snippet depends on earlier context, reference the file or section where
that context was introduced — don't silently rely on it.

For deliberately illustrative code (pseudocode, "here's what the API
*looks like* at a distance"), mark it clearly:

````markdown
```scala
// Sketch, not runnable — see ch02-00 for the real code.
val server = uni.serve(routes)
```
````

## Style

### Prose

- **Second person, active voice.** "You create a `Design`…", not "A
  `Design` is created…".
- **Present tense.** "`bindSingleton` registers…", not "`bindSingleton`
  will register…".
- **Short paragraphs.** Three sentences is a good median. If you hit
  five, split.
- **No filler.** Cut words like *simply*, *just*, *basically*, *of
  course*. They condescend, and they are never the reason a sentence
  worked.
- **No emoji** unless the user explicitly requests it for a page.

### Code

- Scala 3 only. Indentation-based syntax, `new` omitted, `${...}` for
  interpolation.
- Imports at the top of each snippet, unless the snippet is a direct
  continuation of the previous block (call that out).
- Prefer meaningful names (`userService`) over type-mirrored ones
  (`service`). The reader is learning concepts through examples — names
  should carry weight.
- Snippets should *show the output*. After a snippet that prints, show
  what the reader will see in their terminal.

### Structure of a chapter

Every chapter (not every section) should follow this shape:

1. **Opening question.** One or two lines framing what the reader is
   about to figure out. Example: "Your app has three services that need
   a database connection. How do you give it to them without passing it
   through every call site?"
2. **Minimal working example.** Something the reader can paste and run.
3. **Walk the code.** Name and explain the parts. This is where *why*
   lives.
4. **Extend it.** One variation that introduces the next idea or shows
   what changes under pressure (failure modes, alternate platform,
   etc.).
5. **"What you have, what comes next."** Two or three bullets. What
   abilities the reader now has, and what the next chapter adds.

The closing recap is load-bearing: it is what lets a reader navigate
the book by skimming.

## File conventions

- All book files live under `docs/book/`.
- File names: `chNN-MM-slug.md`.
  - `NN` = chapter number (zero-padded: `01`, `02`, … `12`).
  - `MM` = section within the chapter (`00` = the chapter's intro or
    main page).
  - `slug` = short kebab-case, ≤ 4 words.
  - Example: `ch01-02-hello-uni.md` is Chapter 1, Section 2.
- The chapter-intro page (`chNN-00-*.md`) either frames what the
  chapter is about and lists its sections (for multi-section chapters
  like Chapter 1) or is itself the entire chapter (for single-section
  chapters like Chapter 2).
- "Parts" in the book's outline are a grouping in the ToC and sidebar,
  not a file-naming concept.
- The book landing page is `docs/book/index.md`. Update it when adding
  or renaming chapters.
- `docs/book/CLAUDE.md` (this file) is the guide for authors — do **not**
  link to it from the rendered book.

## Sidebar

When you add a chapter, also update the `'/book/'` sidebar in
`docs/.vitepress/config.mts`. Order mirrors the file name order.

## Linking

- Link **into** reference docs liberally. The Book shows how to use the
  pieces; the reference documents the pieces exhaustively. A sentence
  like "see the full list of [retry
  policies](/control/retry)" is ideal.
- Reference docs should **not** link back to the Book. Reference stays
  reference.
- Use relative links inside `/book/` (`./ch02-00-cli-app`), absolute
  links (`/core/design`) elsewhere.

## Stubs

It is fine — expected, even — to land a chapter as a stub while earlier
chapters are still being written, so the ToC reads as an outline rather
than as dead ends. A stub must include:

- A one-paragraph "what this chapter will cover" summary.
- A bulleted list of concepts it will introduce.
- A VitePress warning block marking it as upcoming:

```markdown
::: warning Coming soon
This chapter is an outline. Follow the
[tracking issue](https://github.com/wvlet/uni/issues) for progress.
:::
```

A chapter graduates out of stub state when it has a runnable example
and matches the chapter-shape checklist above.

## Review checklist before merging a chapter

- [ ] Does the chapter open with a concrete question or problem?
- [ ] Is there code the reader can run within the first screen?
- [ ] Is each non-obvious design choice explained (the *why*)?
- [ ] Are snippets Scala 3, valid against the current Uni version?
- [ ] Is the "what you have, what comes next" recap present?
- [ ] Are links into reference docs used instead of restating them?
- [ ] Does the sidebar in `config.mts` include this file?
- [ ] Does `pnpm docs:build` succeed?
