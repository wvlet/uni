# uni-test

Lightweight testing framework. Cross-platform: JVM, Scala.js, Scala Native.

## Constraints

- Depends on `uni-core` only
- Cross-platform: no JVM-only APIs in shared source
- This module IS the test framework; it uses its own framework for self-testing

## Key Classes

- `wvlet.uni.test.UniTest` — base class for all tests
- `wvlet.uni.test.Framework` — sbt test framework integration

## Assertion Reference

See `docs/dev/unitest-guide.md` for the full assertion syntax table and examples.

## Testing

```bash
./sbt testJVM/test
./sbt testJS/test
./sbt testNative/test
```
