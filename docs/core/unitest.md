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

Use Design for dependency injection in tests:

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

UniTest integrates with ScalaCheck for property-based testing:

```scala
import wvlet.uni.test.UniTest
import wvlet.uni.test.PropertyCheck
import org.scalacheck.Gen

class PropertyTest extends UniTest with PropertyCheck:
  test("addition is commutative") {
    forAll { (a: Int, b: Int) =>
      (a + b) shouldBe (b + a)
    }
  }

  test("list reverse") {
    forAll { (list: List[Int]) =>
      list.reverse.reverse shouldBe list
    }
  }

  test("custom generator") {
    forAll(Gen.posNum[Int]) { n =>
      (n > 0) shouldBe true
    }
  }
```

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
2. **Use Design for DI** - Leverage dependency injection for test isolation
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
import wvlet.uni.test.PropertyCheck  // For property-based testing
```
