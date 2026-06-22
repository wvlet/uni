# UniTest

UniTest is a lightweight testing framework for Scala 3. It provides a simple, expressive API for writing tests with minimal boilerplate while supporting cross-platform development (JVM, Scala.js, Scala Native).

## Quick Start

Add the dependency to your build:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "__UNI_VERSION__" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

Write your first test:

```scala
import wvlet.uni.test.UniTest

class MyTest extends UniTest:
  test("addition works") {
    (1 + 1) shouldBe 2
  }
```

Run tests:

```bash
sbt test
```

## Assertions in UniTest

| Syntax | Meaning |
|:-------|:--------|
| `assert(x == y)` | Check x equals y |
| `assertEquals(a, b, delta)` | Check Float/Double equality with tolerance |
| `intercept[E] { ... }` | Catch an exception of type E |
| `x shouldBe y` | Check x == y (supports deep equality for Array, Seq) |
| `x shouldNotBe y` | Check x != y |
| `x shouldBe null` | Check x is null |
| `x shouldNotBe null` | Check x is not null |
| `x shouldBe defined` | Check x.isDefined == true (Option, Seq, String) |
| `x shouldBe empty` | Check x.isEmpty == true (Option, Seq, String) |
| `x shouldBeTheSameInstanceAs y` | Check x eq y (reference equality) |
| `x shouldNotBeTheSameInstanceAs y` | Check x ne y (reference inequality) |
| `x shouldMatch { case .. => }` | Check x matches the pattern |
| `x shouldContain y` | Check x contains y |
| `x shouldNotContain y` | Check x does not contain y |
| `fail("reason")` | Fail the test |
| `ignore("reason")` | Ignore the test |
| `cancel("reason")` | Cancel the test (e.g., setup failure) |
| `pending("reason")` | Mark test as pending |
| `pendingUntil("reason")` | Pending until condition is fixed |
| `skip("reason")` | Skip the test (e.g., platform-specific) |

## Writing Tests

### Basic Test Structure

```scala
import wvlet.uni.test.UniTest

class CalculatorTest extends UniTest:
  test("add two numbers") {
    val result = 2 + 3
    result shouldBe 5
  }

  test("subtract two numbers") {
    val result = 5 - 3
    result shouldBe 2
  }
```

### Nested Tests

Tests can be nested for better organization:

```scala
class MathTest extends UniTest:
  test("arithmetic operations") {
    val x = 10

    test("addition") {
      (x + 5) shouldBe 15
    }

    test("multiplication") {
      (x * 2) shouldBe 20
    }
  }
```

Nested test names include their parent context: `"arithmetic operations / addition"`.

## Assertions

### Equality

| Syntax | Description |
|--------|-------------|
| `a shouldBe b` | Assert `a` equals `b` |
| `a shouldNotBe b` | Assert `a` does not equal `b` |

```scala
test("equality assertions") {
  1 + 1 shouldBe 2
  "hello" shouldBe "hello"
  List(1, 2, 3) shouldBe List(1, 2, 3)

  1 shouldNotBe 2
  "hello" shouldNotBe "world"
}
```

### Null Checks

```scala
test("null checks") {
  val value: String = null
  value shouldBe null

  val nonNull = "hello"
  nonNull shouldNotBe null
}
```

### Defined and Empty

| Syntax | Description |
|--------|-------------|
| `a shouldBe defined` | Assert Option is Some, collection/string is non-empty |
| `a shouldBe empty` | Assert Option is None, collection/string is empty |

```scala
test("defined and empty") {
  val some: Option[Int] = Some(42)
  val none: Option[Int] = None

  some shouldBe defined
  none shouldBe empty
  some shouldNotBe empty
  none shouldNotBe defined

  List(1, 2, 3) shouldBe defined
  List.empty[Int] shouldBe empty
  "hello" shouldBe defined
  "" shouldBe empty
}
```

### Collection Assertions

| Syntax | Description |
|--------|-------------|
| `collection shouldContain element` | Assert collection contains element |
| `collection shouldNotContain element` | Assert collection does not contain element |

```scala
test("collection assertions") {
  val list = List(1, 2, 3)
  list shouldContain 2
  list shouldNotContain 5
}
```

### String Assertions

```scala
test("string assertions") {
  val str = "Hello, World!"
  str shouldContain "World"
  str shouldNotContain "Goodbye"

  str.startsWith("Hello") shouldBe true
  str.endsWith("!") shouldBe true
}
```

### Pattern Matching

Use `shouldMatch` for type-safe pattern matching assertions:

```scala
case class User(name: String, age: Int)

test("pattern matching") {
  val result: Any = User("Alice", 30)

  result shouldMatch { case User(name, age) =>
    name shouldBe "Alice"
    age shouldBe 30
  }

  // Type checking
  result shouldMatch { case _: User => }
}
```

Prefer `shouldMatch` over `asInstanceOf` for type-safe assertions.

### Reference Equality

```scala
test("reference equality") {
  val a = List(1, 2, 3)
  val b = a
  val c = List(1, 2, 3)

  a shouldBeTheSameInstanceAs b
  a shouldNotBeTheSameInstanceAs c
}
```

### Floating Point Comparisons

```scala
test("floating point with delta") {
  assertEquals(0.1 + 0.2, 0.3, 0.0001)
  assertEquals(3.14159f, 3.14f, 0.01f)
}
```

### Exception Testing

```scala
test("exception handling") {
  val e = intercept[IllegalArgumentException] {
    require(false, "invalid argument")
  }
  e.getMessage shouldContain "invalid argument"
}
```

### Boolean Assertions

For comparisons, use boolean expressions with `shouldBe true`:

```scala
test("comparisons") {
  val value = 42

  (value > 10) shouldBe true
  (value < 100) shouldBe true
  (value >= 42) shouldBe true
  (value <= 42) shouldBe true
  (value >= 0 && value <= 100) shouldBe true
}
```

## Test Control

### Skip Tests

```scala
test("platform-specific test") {
  if isScalaJS then
    skip("Not supported on Scala.js")

  // JVM-only code, e.g., accessing a system property
  System.getProperty("java.version") shouldNotBe null
}
```

### Pending Tests

```scala
test("future feature") {
  pending("Implementation not complete yet")
}

test("blocked by issue") {
  pendingUntil("Issue #123 is fixed")
}
```

### Flaky Tests

Mark tests as flaky to convert failures to skipped status:

```scala
test("network test", flaky = true) {
  // Failures become skipped instead of failed
  fetchFromNetwork() shouldBe "expected"
}

// Or wrap specific code
test("partially flaky") {
  val stableResult = computeLocally()
  stableResult shouldBe 42

  flaky {
    val unstableResult = fetchFromNetwork()
    unstableResult shouldBe "expected"
  }
}
```

### Cancel and Ignore

```scala
test("requires setup") {
  if !setupComplete then
    cancel("Setup failed, cannot run test")

  runTest()
}

test("deprecated test") {
  ignore("This test is no longer relevant")
}
```

### Platform Detection

```scala
test("platform-specific behavior") {
  if isScalaJVM then
    // JVM-specific assertions
    System.getProperty("os.name") shouldBe defined

  if isScalaJS then
    // Scala.js-specific assertions
    skip("JVM-only test")

  if isScalaNative then
    // Scala Native-specific assertions
    skip("JVM-only test")
}
```

## Design Integration

Use Design for object wiring in tests:

```scala
import wvlet.uni.test.UniTest
import wvlet.uni.design.Design

class UserServiceTest extends UniTest:
  val testDesign = Design.newDesign
    .bindImpl[UserRepository, InMemoryUserRepository]
    .bindImpl[UserService, UserServiceImpl]

  test("create user") {
    testDesign.build[UserService] { service =>
      val user = service.createUser("Alice")
      user.name shouldBe "Alice"
    }
  }

  test("find user") {
    testDesign.build[UserService] { service =>
      service.createUser("Bob")
      val found = service.findByName("Bob")
      found shouldBe defined
    }
  }
```

## Property-Based Testing

UniTest ships a self-contained property-based testing toolkit — no external
dependency — and it's part of the base `UniTest` trait. Any test class
gets `forAll` automatically. Call it with a body that holds for every
randomly generated input; a failing run is automatically *shrunk* to a
minimal counter-example, and the seed is reported so the run can be
replayed.

```scala
import wvlet.uni.test.UniTest

class ListLawsTest extends UniTest:
  test("addition is commutative") {
    forAll { (a: Int, b: Int) =>
      (a + b) shouldBe (b + a)
    }
  }

  test("reverse is an involution") {
    forAll { (xs: List[Int]) =>
      xs.reverse.reverse shouldBe xs
    }
  }
```

### Generators

Generators live in `wvlet.uni.test.check.Gen`. Every `forAll` body receives
randomly generated values — by default drawn from the type's implicit
`Arbitrary` instance, or from a `Gen` you supply explicitly.

```scala
import wvlet.uni.test.check.Gen

test("positive integers") {
  forAll(Gen.posNum[Int]) { n =>
    (n > 0) shouldBe true
  }
}

test("bounded integers") {
  forAll(Gen.chooseNum(1, 100)) { n =>
    (n >= 1 && n <= 100) shouldBe true
  }
}
```

Common combinators:

| Factory | Produces |
|---------|----------|
| `Gen.const(x)` | Always `x` |
| `Gen.chooseNum[T](min, max)` | Uniform integer in `[min, max]` |
| `Gen.posNum[T]` | Positive, non-zero value |
| `Gen.oneOf(a, b, c)` | Uniform choice from values |
| `Gen.oneOfGen(g1, g2)` | Uniform choice from generators |
| `Gen.frequency(w1 -> g1, w2 -> g2)` | Weighted choice from generators |
| `Gen.listOf(g)` / `Gen.nonEmptyListOf(g)` | List of `g`, size-aware |
| `Gen.listOfN(n, g)` | List of exactly `n` elements |
| `Gen.setOf(g)` / `Gen.mapOf(gK, gV)` | Set / Map, size-aware |
| `Gen.option(g)` | `Some(g)` or `None` |
| `Gen.alphaChar` / `.numChar` / `.alphaNumChar` / `.asciiChar` | Single character |
| `Gen.alphaStr` / `.numStr` / `.alphaNumStr` / `.asciiStr` | Size-aware string |
| `Gen.identifier` | `[A-Za-z][A-Za-z0-9]*` |
| `Gen.sized(n => g)` / `Gen.resize(n, g)` | Read or override the current size |

Generators compose like `Option`s: `g.map`, `g.flatMap`, `g.filter`, `g.zip`,
plus for-comprehensions.

```scala
val evenPositive: Gen[Int] = Gen.posNum[Int].map(_ * 2)

val labeledInt: Gen[(String, Int)] =
  for
    label <- Gen.alphaStr
    value <- Gen.chooseNum(0, 100)
  yield (label, value)
```

### Arbitrary and derivation

Implicit `Arbitrary[T]` instances drive the argument-less `forAll` overload.
Defaults ship for primitives, `String`, `Array[Byte]`, `List`, `Vector`,
`Option`, and small tuples. For your own types, use Scala 3's `derives`:

```scala
import wvlet.uni.test.check.Arbitrary

case class Point(x: Int, y: Int) derives Arbitrary

sealed trait Shape derives Arbitrary
case class Circle(r: Int)                   extends Shape
case class Rect(w: Int, h: Int)             extends Shape
case class Triangle(a: Int, b: Int, c: Int) extends Shape

test("shapes round-trip through the renderer") {
  forAll { (s: Shape) =>
    Renderer.parse(Renderer.show(s)) shouldBe s
  }
}
```

Derivation is recursive: a `derives Arbitrary` on a sealed trait picks up
each case class automatically, and fields of user types that themselves have
an `Arbitrary` given (or a `Mirror`) are threaded through.

To summon a default generator explicitly, use `Arbitrary.arbitrary[T]`:

```scala
forAll(Arbitrary.arbitrary[String]) { s =>
  s.length >= 0 shouldBe true
}
```

### Shrinking

When a property fails, the runner shrinks the counter-example to a minimal
value. Default shrinkers ship for numeric primitives, `Char`, `String`,
`Array[Byte]`, `List`, `Option`, and tuples up to arity 5.

```scala
test("sum is never negative for non-negative inputs") {
  forAll { (xs: List[Int]) =>
    xs.filter(_ >= 0).sum >= 0 shouldBe true
  }
}
```

If this property were broken, the error message would point at a minimal
list — typically `List(...)` with one or two elements — rather than the raw
random failing input.

User types get an empty shrinker by default, so `forAll[MyCaseClass]`
compiles even without a shrink instance. To customize, provide a given:

```scala
import wvlet.uni.test.check.Shrink

given Shrink[Point] = Shrink { p =>
  LazyList(Point(0, 0), Point(p.x, 0), Point(0, p.y))
}
```

### Preconditions with `==>` / `implies`

Use `==>` (or its English alias `implies`) to discard samples that don't
satisfy a precondition. The runner retries with a new sample and fails the
test only if the discard budget (default: 500) runs out.

```scala
test("division by non-zero is self-inverse") {
  forAll { (a: Int, b: Int) =>
    (b != 0) ==> {
      ((a * b) / b) shouldBe a
    }
  }
}

test("reads the same way in prose") {
  forAll { (xs: List[Int]) =>
    xs.nonEmpty implies {
      xs.head shouldBe xs(0)
    }
  }
}
```

### Statistics: `classify` and `collect`

Break down what the generator is actually producing to make sure your
property is exercising the cases you care about. Counts print after the
run.

```scala
test("partition respects predicate") {
  forAll(Gen.listOf(Gen.chooseNum(-100, 100))) { xs =>
    classify(xs.isEmpty, "empty")
    classify(xs.size > 50, "large")
    collect(s"size=${xs.size / 10 * 10}")
    val (neg, nonNeg) = xs.partition(_ < 0)
    (neg ++ nonNeg).sorted shouldBe xs.sorted
  }
}
```

Output (example):
```
[property] 42 × size=10, 31 × size=0, 18 × large, 9 × size=20, 7 × empty
```

### Configuration

Override `propertyConfig` on the test class to change sample count, seed,
size range, discard limit, or shrink budget for every `forAll` in that
class. For a one-off tweak of the sample count, override
`minSuccessfulTests`.

```scala
import wvlet.uni.test.check.PropertyConfig

class HeavyPropertyTest extends UniTest:
  override protected def propertyConfig: PropertyConfig =
    PropertyConfig.default
      .withMinSuccessful(500)
      .withSize(0, 256)
      .withSeed(1_234L) // reproducible runs
```

| Knob | Method | Default |
|------|--------|---------|
| Samples | `withMinSuccessful(n)` | 100 |
| Discard limit | `withMaxDiscarded(n)` | 500 |
| Size range | `withSize(min, max)` | `0..100` |
| Shrink budget | `withMaxShrinks(n)` | 1000 |
| Seed | `withSeed(s)` | `System.nanoTime()` |

Every failure includes the seed. To reproduce a flaky failure, copy the
seed from the error message into `propertyConfig.withSeed(...)` and rerun
— the exact same samples are drawn.

## Running Tests

### sbt Commands

```bash
# Run all tests
sbt test

# Run tests in a specific module
sbt coreJVM/test

# Run a specific test class
sbt "my-project/testOnly *MyTest"

# Run with debug logging
sbt "coreJVM/testOnly * -- -l debug"

# Run with trace logging for specific package
sbt "test -- -l:wvlet.uni.*=trace"

# Filter tests by name
sbt "test -- -t:ChatSession"
```

### Test Output

Test results are displayed with status symbols:

| Symbol | Status | Description |
|--------|--------|-------------|
| `+` | Success | Test passed |
| `-` | Failure | Assertion failed |
| `x` | Error | Unexpected exception |
| `~` | Skipped | Test was skipped |
| `?` | Pending | Test not implemented |
| `!` | Cancelled | Setup failed |

## Best Practices

1. **Avoid mocks** - Use real implementations or in-memory versions
2. **Use Design for wiring** - Leverage object wiring for test isolation
3. **Prefer `shouldMatch`** - Use pattern matching for type checks instead of `asInstanceOf`
4. **One behavior per test** - Keep tests focused on a single behavior
5. **Descriptive names** - Test names should describe the expected behavior
6. **Use boolean expressions** - For comparisons: `(x > 5) shouldBe true`

## Cross-Platform Support

UniTest works across all Scala 3 platforms:

| Feature | JVM | Scala.js | Scala Native |
|---------|:---:|:--------:|:------------:|
| Basic assertions | ✓ | ✓ | ✓ |
| Nested tests | ✓ | ✓ | ✓ |
| Flaky tests | ✓ | ✓ | ✓ |
| Property-based testing | ✓ | ✓ | ✓ |
| IDE integration | ✓ | - | - |

## Package

```scala
import wvlet.uni.test.UniTest
// Property-based testing is part of UniTest — nothing else to import.
// For custom generators / shrinkers:
import wvlet.uni.test.check.{Arbitrary, Gen, Shrink, PropertyConfig}
```
