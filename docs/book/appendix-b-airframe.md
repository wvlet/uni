# Appendix B: Uni and Airframe — History and Relationship

If you've worked in the Scala ecosystem, the ideas in this book may feel
familiar. That is not an accident: Uni is a refinement of code that began
in the [Airframe](https://github.com/wvlet/airframe) project. This
appendix explains the relationship — useful background if you have
existing Airframe code, and reassurance that what you've learned
transfers.

## Where Uni comes from

Airframe is a broad Scala library and ecosystem built up over many years,
spanning dependency wiring, logging, serialization, RPC, and more, across
Scala 2 and Scala 3. Uni takes the most-used, most-foundational pieces of
that work and re-presents them as a single, cohesive library built **for
Scala 3 from the start**.

The mental models you've built reading this book — `Design` as object
wiring, `Rx` as a composable stream, `Surface` for compile-time type
information, codecs derived rather than hand-written — all originated in
Airframe. They carry over because they are the same ideas, sharpened.

## What changed

Uni is a refinement, and the refinements pull in one direction: smaller
and more modern.

- **Scala 3 only.** Uni drops Scala 2 cross-building, so its APIs can use
  `given`/`using`, `enum`, `derives`, and indentation syntax directly
  instead of working around their absence. The
  [syntax notes](./appendix-a-scala3) are the features this buys.
- **Minimal dependencies.** Uni keeps its dependency surface deliberately
  small, so adding it to a project doesn't pull in a framework.
- **Trimmed scope.** Pieces of Airframe that were niche, or better served
  by a dedicated tool, are left out rather than carried forward. Uni aims
  to be the *essential* set, not the exhaustive one.
- **A fresh package root.** Uni lives under `wvlet.uni.*`, distinct from
  Airframe's `wvlet.airframe.*`.

## If you have Airframe code

Because Uni and Airframe occupy different package roots, they are separate
libraries and can sit in the same build during a migration — you are not
forced to move everything at once. The conceptual translation is usually
direct: an Airframe `Design` and a Uni `Design` express the same wiring
idea, an Airframe codec and a Uni `Weaver` solve the same serialization
problem.

The exact mapping — which APIs are identical, which were renamed, which
were dropped — is a moving target better served by the project's
migration notes than by a printed table here. Start from the concept you
know in Airframe, find its chapter in this book, and you'll usually
recognize the Uni shape immediately.

[← Appendix A: Scala 3 Syntax Notes](./appendix-a-scala3) | [Next → Appendix C: Glossary](./appendix-c-glossary)
