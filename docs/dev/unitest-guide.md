# UniTest Guide

Generate test cases in Scala 3 syntax using the UniTest testing framework (`wvlet.uni.test.UniTest`).

## Basic Syntax

- Tests are defined in a `class` that extends `wvlet.uni.test.UniTest`
- Use `test("...") { ... }` syntax for writing test cases
- Tests can be nested

## Assertion Syntax

| syntax                       | meaning                                                                              |
| :--------------------------- | :----------------------------------------------------------------------------------- |
| `assert(x == y)`             | check x equals to y                                                                  |
| `assertEquals(a, b, delta)`  | check the equality of Float (or Double) values by allowing some delta difference     |
| `intercept[E] { ... }`       | Catch an exception of type `E` to check an expected exception is thrown              |
| `x shouldBe y`               | check x == y. Supports matching collections like Seq, Array (with deepEqual)         |
| `x shouldNotBe y`            | check x != y                                                                         |
| `x shouldNotBe null`         | null check                                                                           |
| `x shouldBe defined`         | check x.isDefined == true, when x is Option or Seq                                   |
| `x shouldBe empty`           | check x.isEmpty == true, when x is Option or Seq                                     |
| `x shouldBeTheSameInstanceAs y` | check x eq y (same instance)                                                      |
| `x shouldNotBeTheSameInstanceAs y` | check x ne y (different instances)                                              |
| `x shouldMatch { case .. => }` | check x matches given patterns                                                     |
| `x shouldContain y`          | check x contains given value y                                                       |
| `x shouldNotContain y`       | check x doesn't contain a given value y                                              |
| `fail("reason")`             | fail the test if this code path should not be reached                                |
| `pending("reason")`          | pending the test execution                                                           |
| `skip("reason")`             | skip test (e.g., tests that cannot be supported in Scala.js)                        |

## Example

```scala
import wvlet.uni.test.UniTest

class MyTest extends UniTest:
  test("assertion examples") {
    1 shouldBe 1
    1 shouldNotBe 2
    Option("hello") shouldBe defined
    Seq(1, 2) shouldContain 1
    "hello world" shouldNotContain "!!"

    Seq(1, 2) shouldMatch {
      case Seq(1, _) => // ok
    }
  }
```

## Debug Logging

Use `debug` and `trace` in tests. Enable with: `testOnly * -- -l debug`

## Design Integration

Use Design to wire test dependencies:

```scala
class ServiceSpec extends UniTest:
  val testDesign = Design.newDesign
    .bindInstance[ServiceConfig](ServiceConfig(port = 8080))
    .bindSingleton[Service]

  test("test with design") {
    testDesign.build[Service] { service =>
      service.config.port shouldBe 8080
    }
  }
```
