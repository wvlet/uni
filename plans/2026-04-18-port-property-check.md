# Port Property-Based Testing into UniTest

## Motivation

UniTest currently depends on ScalaCheck (`org.scalacheck:scalacheck:1.19.0`) solely to back its `PropertyCheck` trait. This is the only runtime dependency uni-test carries beyond sbt/JUnit test interfaces.

Goals of uni-test:
- Minimal dependencies (per project CLAUDE.md guidance for uni)
- Be a self-contained lightweight test framework

The ScalaCheck surface actually used by the project is small, but we want enough parity with ScalaCheck that future tests in uni and in consumer projects don't feel cramped. The user asked for a broad port in one pass.

## Scope

**Ported into uni-test**:

1. **Generators (`wvlet.uni.test.check.Gen`)**
   - Core: `map`, `flatMap`, `filter`/`withFilter`, `zip`
   - Factories: `const`, `oneOf`, `chooseNum[T]`, `posNum[T]`
   - Size-aware: `sized`, `resize`
   - Weighted: `frequency`
   - Containers: `listOf`, `listOfN`, `nonEmptyListOf`, `setOf`, `mapOf`, `option`
   - Characters: `alphaChar`, `alphaLowerChar`, `alphaUpperChar`, `numChar`, `alphaNumChar`, `asciiChar`, `asciiPrintableChar`
   - Strings: `stringOf`, `stringOfN`, `alphaStr`, `numStr`, `alphaNumStr`, `asciiStr`, `identifier`
2. **Arbitrary typeclass (`wvlet.uni.test.check.Arbitrary`)**
   - Defaults for `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Char`, `String`, `Array[Byte]`, `List[A]`, `Option[A]`, `Tuple2..5`
   - Scala 3 `Mirror`-based derivation for product and sum types (opt-in via `Arbitrary.derived`)
3. **Shrink typeclass (`wvlet.uni.test.check.Shrink`)**
   - Defaults for numeric primitives, `String`, `List[A]`, `Option[A]`, `Tuple2..5`
   - Runner minimises the counter-example on failure (breadth-first shrink with bounded iterations)
4. **Property runner (`wvlet.uni.test.PropertyCheck`)**
   - Backward-compatible `forAll` API (1–5 parameters, implicit `Arbitrary` or explicit `Gen`)
   - `PropertyConfig` with `minSuccessfulTests`, `maxDiscarded`, `minSize`, `maxSize`, optional `seed`
   - Per-test override: `propertyConfig`
   - Seeded, reproducible runs: failure messages include the seed so failing runs can be replayed
   - `==>` implication / discard support
   - `classify(cond, label, fallback?)` and `collect(labels)` for result bucketing, reported on test success
5. **Removal of the ScalaCheck dependency** from `build.sbt`.

**Explicitly out of scope for this PR**:
- ScalaCheck's stateful `Commands` framework (model-based testing). It's rarely used and would be several days of additional work; port on demand.
- Full ScalaCheck `Prop` / `Properties` combinator tree (`prop1 && prop2`, `exists`, `==>` at `Prop` level). We support the common case via imperative test bodies; richer combinators can be layered on later.

## Design

### Layout

```
uni-test/src/main/scala/wvlet/uni/test/
├── PropertyCheck.scala                  # public forAll API + runner + classify/discard
└── check/
    ├── Gen.scala                        # Gen[A], Params, Choose, PosNum, combinators
    ├── Arbitrary.scala                  # typeclass, default givens, derivation
    ├── Shrink.scala                     # typeclass, default givens, minimiser
    └── PropertyConfig.scala             # config + discard exception
```

### Gen threads size + RNG

`Gen[A]` is a function from `Params` to `A`, where `Params` holds a `Random` and a current size:

```scala
final case class Params(rng: Random, size: Int)
final class Gen[+A](val run: Params => A)
```

`sized(f: Int => Gen[A])` reads `Params.size`; `resize(n)(g)` substitutes it. Containers default to `sized(n => Gen.chooseNum(0, n).flatMap(listOfN(_, g)))` so the generated shape grows with size.

### Shrink: breadth-first bounded minimiser

```scala
trait Shrink[A]:
  def shrink(a: A): LazyList[A]
```

Primitive shrinks follow ScalaCheck-style halving-toward-zero. `List`/`String` shrink by shortening plus element-wise shrinks. The runner, upon failure, tries each candidate in order, keeps the first candidate that still fails, and recurses with a cap of ~1000 shrink steps to bound runtime.

### PropertyConfig

```scala
final case class PropertyConfig(
    minSuccessfulTests: Int = 100,
    maxDiscarded: Int = 500,
    minSize: Int = 0,
    maxSize: Int = 100,
    seed: Option[Long] = None
):
  def withMinSuccessful(n: Int): PropertyConfig
  def withMaxDiscarded(n: Int): PropertyConfig
  def withSize(min: Int, max: Int): PropertyConfig
  def withSeed(s: Long): PropertyConfig
```

`PropertyCheck` exposes `propertyConfig: PropertyConfig` that subclasses can override.

### Discard / implication

```scala
object PropertyCheck:
  extension (cond: Boolean)
    def ==>[A](body: => A): A = if cond then body else throw DiscardException
```

`DiscardException` is caught by the runner and counted toward `maxDiscarded`. Exhaustion throws a failure.

### classify / collect

A thread-local `StatsCollector` is active for the duration of a single `forAll` run. On successful completion, counts are logged via `info(...)` unless suppressed. Adds no fields to test bodies — works through effectful methods on the trait.

### Derivation

Scala 3 inline derivation from `scala.deriving.Mirror`:

```scala
inline def derived[T](using m: Mirror.Of[T]): Arbitrary[T]
```

For products: summon `Arbitrary` for each element type, produce a tuple, hand to `fromProduct`.
For sums: `Gen.oneOf` over the sub-type instances.

## Migration Plan

1. Add `check.Gen`, `check.Arbitrary`, `check.Shrink`, `check.PropertyConfig` to uni-test.
2. Rewrite `PropertyCheck.scala` to use them. Public `forAll(...)` signatures stay source-compatible with the ScalaCheck-backed version.
3. Update call sites in `uni`:
   - `org.scalacheck.Gen` → `wvlet.uni.test.check.Gen`
   - `org.scalacheck.Arbitrary.arbitrary` → `wvlet.uni.test.check.Arbitrary.arbitrary`
4. Drop `scalacheck` from `build.sbt`.
5. Add comprehensive self-tests in `uni-test/src/test/` covering generators, shrinking, discard, classify, derivation, and failure reporting.
6. Run the full JVM test suite; verify JS/Native compile.

## Risks

- **Shrinking correctness**: a broken shrinker can loop or report wrong counter-examples. Mitigation: bounded iterations; self-tests assert that a deliberate failing property reports the minimal expected input.
- **Derivation compile-time cost**: inline deriving of recursive ADTs can blow up; we only derive on explicit `Arbitrary.derived` and keep the derivation shallow.
- **String generator semantics**: must produce broad unicode (including surrogate pairs) to keep roundtrip coverage on par with ScalaCheck. Verified by `RoundTripTest.support String`.
- **Float/Double NaN**: equality-based assertions fail on NaN; our defaults exclude NaN to match the practical ScalaCheck behavior in existing tests. Callers that need NaN can build an explicit `Gen`.
- **Seed reproducibility**: the seed used by each property is logged in failure messages; callers can replay by overriding `propertyConfig.withSeed(seed)`.

## Testing

- Existing property tests continue to pass: `RoundTripTest`, `ValueTest`, `ULIDTest`, `CrockfordBase32Test`, `PrefixedULIDTest`.
- New `PropertyCheckTest` covers: chooseNum bounds, posNum, sized/resize, frequency, listOf/mapOf, char/string gens, discard, shrink minimisation, derivation, failure messages with seed.
- JS and Native compile.
