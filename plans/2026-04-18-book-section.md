# The Uni Book — Documentation Section (Initial Scaffolding)

Date: 2026-04-18
Branch: `docs/book-section`

## Goal

Add a long-form, narrative **Book** section to the Uni documentation,
modeled after *[The Rust Programming Language](https://doc.rust-lang.org/book/)*
("The Rust Book"). Where the existing `/guide/` and module reference pages
answer "what does this API do?", the Book answers "how do I build real
applications with Scala 3 + Uni, and why?".

The Book should gradually build a reader's mental model:

1. **Set up tools** → 2. **Run a small program** → 3. **Explain the
   concepts behind it** → 4. **Apply the concepts to a real project**.

Each chapter should leave the reader with a concrete artifact (a file they
ran) *and* an internal model (why it is shaped that way).

## Why not extend `/guide/`?

The existing `/guide/` is reference-oriented (module → APIs). The Book is
task-oriented (goal → techniques). Keeping them separate avoids two
failure modes:

- Reference docs swelling into tutorials and becoming hard to scan.
- Tutorials drifting into incomplete reference material.

The Book links *into* reference pages. Reference pages do not need to link
back.

## Scope of this PR

This PR establishes **scaffolding + writing guide**, not a completed
book. Specifically:

- `docs/book/CLAUDE.md` — a contributor guide (audience: future authors,
  including Claude) describing tone, structure, and how each chapter
  should be shaped.
- `docs/book/index.md` — book landing page with the full table of
  contents.
- `docs/book/foreword.md` — short foreword explaining who the book is for
  and how to read it.
- **Part I — Getting Started** (drafted):
  - `ch01-00-getting-started.md` (part intro)
  - `ch01-01-installation.md` — prerequisites (JDK, sbt, Scala 3), adding
    Uni to a project, and a minimal `build.sbt` for JVM/JS/Native.
  - `ch01-02-hello-uni.md` — first program with Uni logging & `Design`.
- **Part II — Building a CLI Application** (drafted skeleton):
  - `ch02-00-cli-app.md` — a walkthrough building a small CLI tool
    (e.g. a URL fetcher) that introduces `Design`, `Logger`, `HttpClient`,
    `Launcher`, and `UniTest`. This mirrors the Rust book's Chapter 2
    "Guessing Game" pattern: one end-to-end program before deep theory.
- **Stubs for later parts** (short placeholder pages describing intent):
  - Part III — Core Concepts (Design, Logging, Surface, JSON/MessagePack)
  - Part IV — Async & Control Flow (Rx, Retry, Circuit Breaker, Resource)
  - Part V — HTTP & RPC
  - Part VI — Cross-Platform (JVM / Scala.js / Scala Native)
  - Part VII — Agent Framework (uni-agent)
  - Part VIII — Testing with UniTest
  - Appendices (cheat sheets, interop, glossary)
- VitePress sidebar + top-nav entry for `/book/`.

Stubs carry a short "what this chapter will cover" paragraph and are
explicitly marked *Coming soon* so the ToC reads as a real outline rather
than dead links.

## Writing philosophy (carried in `docs/book/CLAUDE.md`)

Mirrors the Rust book's pedagogy:

1. **Show, then name.** Introduce a shape in code before naming the
   concept. Readers bind the name to something they have already seen
   working.
2. **Small programs before big concepts.** Chapter 2 is a full working
   program using Uni primitives; theory comes after.
3. **One new idea per section.** Each section adds exactly one concept on
   top of what the reader already has.
4. **Explain *why*, not just *how*.** Every non-obvious choice ("why
   `Design` instead of passing constructor args?", "why avoid `Try[A]`?")
   gets a short rationale paragraph.
5. **Cross-platform by default.** Examples that work unchanged on JVM,
   Scala.js, and Scala Native use a single snippet. Platform-specific
   detours are called out with a box.
6. **Runnable, not decorative.** Every code block is meant to actually
   compile against the current Uni version. Snippets use Scala 3 syntax
   (indentation-based, `new` omitted, `${...}` interpolation).

## Structure conventions

- File names: `chNN-MM-slug.md`. `NN` = part number, `MM` = section
  within part. `00` is reserved for the part's intro page.
- Each chapter ends with a short *"What you have, what comes next"*
  recap.
- Deep-dive reference links go in the body (inline), not as a footer
  dump.
- Snippets are full enough to run. If a snippet depends on earlier
  context, it references the file/section where that context was
  introduced.

## Non-goals for this PR

- Writing the full body of Parts III–VIII. Those are stubs here and will
  be expanded in follow-up PRs, one part per PR, to keep reviews
  tractable.
- Restructuring `/guide/`, `/core/`, etc. — they stay as reference docs.
- Automated snippet testing. A future PR can wire `mdoc`-style
  verification; for now snippets are reviewed by hand.

## Rollout

1. Land this PR with scaffolding + Parts I & II drafted.
2. Open a tracking issue listing remaining parts.
3. Each subsequent part ships as its own PR referencing that issue, so
   reviewers see one narrative at a time.

## Validation

- `npm run docs:build` succeeds (no broken links within the book).
- `npm run docs:dev` renders the new section in the sidebar.
- Sample snippets compile against the current Uni version (manual check).
