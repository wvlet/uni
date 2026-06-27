---
applyTo: "**/*Test.scala"
---

Generate test cases in Scala 3 syntaxes using the UniTest testing framework (`wvlet.uni.test.UniTest`).

## UniTest Basic Syntax Explanation:

- UniTest tests are typically defined within a Scala `object` or `class` that extends `wvlet.uni.test.UniTest`.
- UniTest uses `test("...") { ... }` syntax for writing test cases.

UniTest provides a rich set of assertion syntaxes for verifying test expectations. Here are some common ones:

### Assertion Syntax Table:

| syntax                       | meaning                                                                              |
| :--------------------------- | :----------------------------------------------------------------------------------- |
| `assert(x == y)`             | check x equals to y                                                                  |
| `assertEquals(a, b, delta)`  | check the equality of Float (or Double) values by allowing some delta difference     |
| `intercept[E] { ... }`       | Catch an exception of type `E` to check an expected exception is thrown              |
| `x shouldBe y`               | check x == y. This supports matching collections like Seq, Array (with deepEqual)    |
| `x shouldNotBe y`            | check x != y                                                                         |
| `x shouldNotBe null`         | `shouldBe`, `shouldNotBe` supports null check                                        |
| `x shouldBe defined`         | check x.isDefined == true, when x is Option or Seq                                   |
| `x shouldBe empty`           | check x.isEmpty == true, when x is Option or Seq                                     |
| `x shouldBeTheSameInstanceAs y` | check x eq y; x and y are the same object instance                                   |
| `x shouldNotBeTheSameInstanceAs y` | check x ne y; x and y should not be the same instance                               |
| `x shouldMatch { case .. => }` | check x matches given patterns                                                       |
| `x shouldContain y`          | check x contains given value y                                                       |
| `x shouldNotContain y`       | check x doesn't contain a given value y                                              |
| `fail("reason")`             | fail the test if this code path should not be reached                                |
| `ignore("reason")`           | ignore this test execution.                                                          |
| `cancel("reason")`           | cancel the test (e.g., due to set up failure)                                        |
| `pending("reason")`          | pending the test execution (e.g., when hitting an unknown issue)                     |
| `pendingUntil("reason")`     | pending until fixing some blocking issues                                            |
| `skip("reason")`             | Skipping unnecessary tests (e.g., tests that cannot be supported in Scala.js)      |

### Assertion Example:

```scala
import wvlet.uni.test.UniTest

class MyTest extends UniTest:
  test("assertion examples") {
    // checking the value equality with shouldBe, shouldNotBe:
    1 shouldBe 1
    1 shouldNotBe 2
    List().isEmpty shouldBe true

    // For optional values, shouldBe defined (or empty) can be used:
    Option("hello") shouldBe defined
    Option(null) shouldBe empty
    None shouldNotBe defined

    // null check
    val s: String = null
    s shouldBe null
    "s" shouldNotBe null

    // For Arrays, shouldBe checks the equality with deep equals
    Array(1, 2) shouldBe Array(1, 2)

    // Collection checker
    Seq(1) shouldBe defined
    Seq(1) shouldNotBe empty
    Seq(1, 2) shouldBe Seq(1, 2)
    (1, 'a') shouldBe (1, 'a')

    // Object equality checker
    val a = List(1, 2)
    val a1 = a
    val b = List(1, 2)
    a shouldBe a1
    a shouldBeTheSameInstanceAs a1
    a shouldBe b
    a shouldNotBeTheSameInstanceAs b

    // Pattern matcher
    Seq(1, 2) shouldMatch {
      case Seq(1, _) => // ok
    }

    // Containment check
    "hello world" shouldContain "world"
    Seq(1, 2, 3) shouldContain 1

    "hello world" shouldNotContain "!!"
    Seq(1, 2, 3) shouldNotContain 4
  }

  // You can nest test cases
  test("nested test examples") {
    test("nested test") {
      1 shouldBe 1
    }

    test("nested test with pending") {
      pending("this test is pending")
    }
  }
```

## Tagging tests for multi-layer runs

Tag a test to mark which testing layer it belongs to (unit, UI, electron, integration, slow,
…), then run or skip a layer at a time — the same way VSCode keeps separate unit/integration/UI
test commands.

```scala
test("renders the toolbar", tags = Seq("ui")) {
  // ...
}

test("hits the real database", tags = Seq("integration", "slow")) {
  // ...
}
```

Select layers from sbt (args after `--`):

```bash
./sbt "coreJVM/testOnly * -- -tag:ui"            # run only tests tagged `ui`
./sbt "coreJVM/testOnly * -- -tag:ui,electron"   # run tests tagged `ui` OR `electron`
./sbt "coreJVM/testOnly * -- -xtag:slow"         # run everything except `slow` tests
```

`-tag:` is an include filter (any listed tag matches); `-xtag:` is an exclude filter; exclusion
wins over inclusion. Tags apply to top-level tests; nested tests run with their parent.

## Logging

To add debug messages, use `debug` and `trace` methods.

```scala
test("my test") {
  debug("debug message")
  trace("trace message")
}
```

Debug logging can be enabled by setting the log level in `testOnly` command in sbt with `-l debug` or `-l trace`:
```scala
> testOnly * -- -l debug
```

## Design Integration

To set up commonly used resources, use Design to bind instances:

```scala
import wvlet.uni.test.UniTest
import wvlet.uni.design.Design

case class ServiceConfig(port: Int)
class Service(val config: ServiceConfig)

class ServiceSpec extends UniTest:
  val testDesign = Design.newDesign
    .bindInstance[ServiceConfig](ServiceConfig(port = 8080))
    .bindSingleton[Service]
    .onStart { x => info(s"Starting a server at ${x.config.port}") }
    .onShutdown { x => info(s"Stopping the server at ${x.config.port}") }

  test("test with design") {
    testDesign.build[Service] { service =>
      info(s"server id: ${service.hashCode}")
      service.config.port shouldBe 8080
    }
  }
```
