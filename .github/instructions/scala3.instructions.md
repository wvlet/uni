---
applyTo: "**/*.scala"
---

# Scala 3 Coding Conventions for uni

## Syntax Rules

- Use Scala 3 syntax exclusively (no Scala 2 syntax)
- Omit `new` keyword: `StringBuilder()` not `new StringBuilder()`
- String interpolation: always use brackets `${...}` even for simple variables
- Use `end` markers for classes/objects longer than 30 lines (enforced by scalafmt)
- Indent with 2 spaces

## Patterns

- Config classes: `withXXX(...)` methods for all fields, `noXXX()` for optional fields
- Avoid `Try[A]` return types; prefer direct exceptions or `Either[Error, A]`
- Avoid mocks in tests; use real implementations with Design for DI
- Use `shouldBe`, `shouldNotBe`, `shouldContain`, `shouldMatch` for assertions
- Comparison operators: `(value >= 1) shouldBe true` not `value should be >= 1`
- Type checking: `result shouldMatch { case x: ExpectedType => }` not `.asInstanceOf[X]`

## Common Pitfalls to Avoid

- Do NOT use `new` keyword (scalafmt will convert but avoid it in the first place)
- Do NOT add JVM-only imports in cross-platform modules (`uni-core`, `uni`, `uni-test`)
- Do NOT use `scala.util.Try` as a return type from public APIs
- Do NOT use `should be >= 1` syntax; UniTest doesn't support it
- Do NOT forget to run `./sbt scalafmtAll` before committing
