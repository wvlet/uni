# 11. Testing with UniTest

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Tests in a Uni codebase favor the real implementation with narrow
substitutions, rather than deep mocking. This chapter covers the
shape of a UniTest suite and how Design makes substitutions clean.

Concepts introduced:

- The `UniTest` base class and the assertion vocabulary (`shouldBe`,
  `shouldContain`, `shouldMatch`).
- Why the project avoids mocks, and what to do instead: narrow
  overrides via `Design` and in-process fakes.
- Property-based testing primitives.
- Async tests with `Rx` and `Future`.
- Cross-platform tests: writing one suite that runs under JVM,
  Scala.js, and Scala Native.
- Speed: keeping a Uni test suite under a second, and why that matters.

## Reference you can read now

- [UniTest (module reference)](/core/unitest)

[← 10. One Codebase, Three Runtimes](./ch10-00-cross-platform) | [Next → Appendix A: Scala 3 Syntax Notes](./appendix-a-scala3)
