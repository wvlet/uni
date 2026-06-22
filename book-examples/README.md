# uni-book-examples

Compile-checked code from [The Uni Book](../docs/book/). Each `ChNN*.scala`
file mirrors the runnable snippets of the corresponding chapter, so CI catches
API drift in the book as `uni` evolves — the book's authoring guide requires
every non-illustrative snippet to compile, and this module enforces it.

The examples are compiled, not run (HTTP/RPC servers are configured but never
started; clients are constructed but never connected). The one exception is
`Ch04TestingTest`, which is a real `UniTest` suite that also runs.

```bash
./sbt bookExamples/Test/compile   # compile every example
./sbt bookExamples/test           # also run the testing-chapter suite
```

Platform-specific chapters (12–13 Scala.js facades / Vite, 14–15 Scala Native
FFI) are not represented here — they require the JS and Native toolchains and
external bundlers/compilers, and are verified against the reference sources they
were drawn from instead.

This module is not published (`publish / skip := true`).
