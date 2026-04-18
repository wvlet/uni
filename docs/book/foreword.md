# Foreword

Scala 3 is a small, sharp language. The standard library is careful,
the type system is expressive, and the compiler will happily tell you
when your ideas do not fit together. Those are the good parts.

The parts the standard library leaves to you are the ones every real
application still has to answer:

- How do my objects get wired together, and when are they cleaned up?
- How do I structure logging so that when a request goes wrong, I find
  the one line that mattered?
- How do I ship an HTTP client that retries the right errors, and no
  others?
- How do I write one piece of business logic that runs in the JVM, in
  a browser, and in a compiled binary?

**Uni** is the collection of small, opinionated libraries that Scala
applications reach for to answer those questions. It is a distillation
of code from the [Airframe](https://github.com/wvlet/airframe) project,
refined for Scala 3 and for a future where Scala.js and Scala Native
are first-class targets, not afterthoughts.

This book is the long-form counterpart to Uni's reference docs. The
reference answers *"what does this API do?"*. This book answers *"how
do I build real applications with these pieces, and why are the pieces
shaped this way?"*. By the end of it, you should be able to open an
empty directory and sketch, in your head, the file layout and the
dependency graph of a reasonable Scala 3 service — and have opinions
about each choice.

You will build two things as you read:

1. A small CLI application (Part II).
2. A single codebase that compiles to the JVM, to JavaScript, and to a
   native binary (Part VI).

Along the way the book will explain, honestly, when Uni is the right
tool and when a different tool would serve you better. No library
earns trust by hiding its edges.

Thank you for spending time with this. Let's start.

— *The Uni team*

[Next → Chapter 1: Getting Started](./ch01-00-getting-started)
