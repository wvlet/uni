# UniTest

UniTest is a lightweight testing framework for Scala 3. It provides a simple, expressive API for writing tests with minimal boilerplate while supporting cross-platform development (JVM, Scala.js, Scala Native).

## Quick Start

Add the dependency to your build:

```scala sbt
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
| `x shouldMatch { case .. => }` | Check x matches the pattern, then run the assertions in the case |
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

```scala test
test("equality assertions") {
  1 + 1 shouldBe 2
  "hello" shouldBe "hello"
  List(1, 2, 3) shouldBe List(1, 2, 3)

  1 shouldNotBe 2
  "hello" shouldNotBe "world"
}
```

### Null Checks

```scala test
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

`defined` and `empty` are objects in the `wvlet.uni.test` package, so import
`wvlet.uni.test.*` (not only `UniTest`) to use them.

```scala test
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

```scala test
test("collection assertions") {
  val list = List(1, 2, 3)
  list shouldContain 2
  list shouldNotContain 5
}
```

### String Assertions

```scala test
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

```scala test
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

```scala test
test("reference equality") {
  val a = List(1, 2, 3)
  val b = a
  val c = List(1, 2, 3)

  a shouldBeTheSameInstanceAs b
  a shouldNotBeTheSameInstanceAs c
}
```

### Floating Point Comparisons

```scala test
test("floating point with delta") {
  assertEquals(0.1 + 0.2, 0.3, 0.0001)
  assertEquals(3.14159f, 3.14f, 0.01f)
}
```

### Exception Testing

```scala test
test("exception handling") {
  val e = intercept[IllegalArgumentException] {
    require(false, "invalid argument")
  }
  e.getMessage shouldContain "invalid argument"
}
```

### Boolean Assertions

For comparisons, use boolean expressions with `shouldBe true`:

```scala test
test("comparisons") {
  val value = 42

  (value > 10) shouldBe true
  (value < 100) shouldBe true
  (value >= 42) shouldBe true
  (value <= 42) shouldBe true
  (value >= 0 && value <= 100) shouldBe true
}
```

### Rx Subscription Leaks

`assertNoRxLeaks` fails when its block leaves an Rx subscription active, one
that was neither cancelled nor ended with `OnError` / `OnCompletion`:

```scala test
test("cancels its binding") {
  val counter = wvlet.uni.rx.Rx.variable(0)
  assertNoRxLeaks {
    val c = counter.subscribe(v => println(v))
    c.cancel
  }
}
```

It tracks the subscriptions the block starts on the test thread. See
[Finding Leaked Subscriptions](../rx/operators#finding-leaked-subscriptions).

## Testing Error Paths

A test that makes code fail on purpose, such as a handler that throws, makes the code log a warning with a stack trace. Wrap the code in `captureLogs` to keep those records out of the test output and to check that they were logged:

```scala test
import wvlet.uni.log.LogSupport

class Importer extends LogSupport:
  def parse(line: String): Option[Int] =
    val n = line.toIntOption
    if n.isEmpty then
      warn(s"Skipping an invalid line: ${line}")
    n

test("skip invalid lines with a warning") {
  val logs = captureLogs {
    Importer().parse("abc") shouldBe None
  }
  logs.warnings shouldContain "Skipping an invalid line: abc"
}
```

`captureLogs` returns a `CapturedLogs`:

| Member | Content |
|--------|---------|
| `warnings` | Messages of WARN records, one per line |
| `errors` | Messages of ERROR records, one per line |
| `messages` | Messages of all records, one per line |
| `records` | The `LogRecord`s, for checks on level, logger name, or cause |

A record with an exception adds a line with the exception, so `logs.warnings shouldContain "boom"` also matches the exception message.

What it captures:

- Records from the thread running the block, and from threads that run no test suite, such as a server's worker threads and `Future` pools. A test that starts a server and sends it a failing request captures the server's warnings.
- Not records from threads running another suite, so suites running in parallel keep their own output.
- `captureLogs[A] { ... }` captures only the logger of `A` and its children. Use it for long blocks, so that records of unrelated code stay visible.
- Log levels are not changed. A record below its logger's level is not emitted, so it is not captured either.
- If the block throws, the captured records are printed, so the failure stays debuggable.
- The block must be synchronous: one that returns a `Future` or `Rx` fails, because the async code would run after the capture ends.

`captureStdout` and `captureStderr` return what the block printed to `Console.out` and `Console.err`:

```scala test
test("print a greeting") {
  val out = captureStdout {
    println("hello")
  }
  out shouldBe s"hello${System.lineSeparator()}"
}
```

They redirect Scala's `Console` for the current thread and threads it starts, not the process-wide `System.out`/`System.err`, so they capture `println` but not output that Java code writes to `System.out` directly.

## Test Control

### Skip Tests

```scala test
test("platform-specific test") {
  if isScalaJS then
    skip("Not supported on Scala.js")

  // JVM-only code, e.g., accessing a system property
  System.getProperty("java.version") shouldNotBe null
}
```

### Pending Tests

```scala test
test("future feature") {
  pending("Implementation not complete yet")
}

test("blocked by issue") {
  pendingUntil("Issue #123 is fixed")
}
```

### Flaky Tests

Mark tests as flaky to convert failures to skipped status:

```scala test
test("network test", Flaky) {
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

```scala test
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

```scala test
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

## Test Tags

Pass one or more tags after the test name to group tests, then run or skip a
group from sbt. A single suite can then hold unit, integration, and UI tests
and still run one testing layer at a time, as VSCode does with its separate
unit, integration, and UI test commands.

```scala test
test("renders the toolbar", UI) {
  // ...
}

test("hits the real database", Integration, Slow) {
  // ...
}

test("end-to-end happy path", Smoke) {
  // ...
}

test("retried under load", Flaky) {
  // ...
}
```

Built-in tags are in scope in any `UniTest`:

| Tag | Name | Meaning |
|-----|------|---------|
| `Integration` | `integration` | Uses real external systems |
| `Smoke` | `smoke` | Quick sanity check, run as a fast pre-merge subset |
| `Slow` | `slow` | Usually excluded from the inner-loop run |
| `Flaky` | `flaky` | A failure is reported as skipped instead of failing the build |
| `UI` | `ui` | UI-layer test |
| `Electron` | `electron` | Electron-layer test, for desktop apps built on Uni's Electron support |

Define your own tags with `TestTag("name")`, or as an object when you reuse
one across suites:

```scala test
import wvlet.uni.test.TestTag

case object Gpu extends TestTag("gpu")

test("trains on the GPU", Gpu, Slow) {
  // ...
}

test("legacy path", TestTag("legacy")) {
  // ...
}
```

A bare string also works, as in `test("x", "legacy")`, with
`import scala.language.implicitConversions` at the call site.

### Selecting Tests by Tag

Pass `--tags` and `--exclude-tags` after `--`. Both refer to a tag's name:

```bash
sbt "test -- --tags ui"                  # only tests tagged ui
sbt "test -- --tags ui --tags slow"      # tests tagged both ui and slow
sbt "test -- --exclude-tags slow"        # everything except slow tests
sbt "test -- --exclude-tags flaky"       # skip known-flaky tests
```

- Repeating `--tags` narrows the selection: a test must carry every listed tag
  (AND), like GitHub label filters.
- `--exclude-tags` drops tests carrying any listed tag, and wins over `--tags`.
- With no `--tags`, every test is included.
- Tags apply to top-level tests. Nested tests run with their parent.

## Design Integration

A test whose body takes typed parameters gets them from the suite's
[Design](./design). Add bindings with `addDesign`, and list what the test needs
as the parameters of its body:

```scala
import wvlet.uni.test.*

class UserServiceTest extends UniTest:
  addDesign(
    _.bindImpl[UserRepository, InMemoryUserRepository]
      .bindImpl[UserService, UserServiceImpl]
  )

  test("create user") { (service: UserService) =>
    val user = service.createUser("Alice")
    user.name shouldBe "Alice"
  }

  test("find user") { (service: UserService, repo: UserRepository) =>
    service.createUser("Bob")
    repo.findByName("Bob") shouldBe defined
  }
```

- **Shared or per test:** objects bound with `addDesign` are built once and
  shared by the tests of the suite. Objects bound with `addTestDesign`, and
  objects that are not bound at all, are built for each test and closed after
  it.
- **Sessions:** the suite's Design session starts when the first such test
  runs, and shuts down after `afterAll`. Each test gets a child session, which
  shuts down after `after`, once a returned `Future` or `Rx` completes. Shutting
  a session down closes the objects it built (`AutoCloseable` or `onShutdown`)
  in reverse creation order.
- **Arguments:** a test can take up to as many arguments as a Scala function
  literal allows, and can take `(session: Session)` to get the session itself.
- **Plain tests:** a body without parameters is a plain test and opens no
  session.

## Lifecycle Hooks

Override these protected hooks to run code around tests:

| Hook | Runs | If it throws |
|------|------|--------------|
| `beforeAll` | Once, before the first test | The suite fails and no test runs; `afterAll` still runs |
| `afterAll` | Once, after the last test, also after a failed `beforeAll` | Reported as a suite error |
| `before` | Before each test, including each nested test | The test fails and its body does not run; `after` still runs |
| `after` | After each test, once its `Future` or `Rx` result completes, also when the test or `before` fails | The test fails, unless it already failed |

```scala
class CacheTest extends UniTest:
  private val cache = scala.collection.mutable.Map.empty[String, Int]

  override protected def after: Unit = cache.clear()

  test("put") {
    cache("a") = 1
    cache.size shouldBe 1
  }

  test("starts empty") {
    cache.isEmpty shouldBe true
  }
```

Tests in a suite run one at a time, in the order they are registered. Nested
tests are queued after the tests already registered and run only when their
parent passed, each with its own `before` and `after`.

The hooks are synchronous. To open and close resources such as servers or
clients, prefer binding them in a Design (see
[Reusable Fixtures](#reusable-fixtures)) over writing hooks.

## Reusable Fixtures

A fixture is a trait that adds bindings for resources such as a server, a
client, or a temp directory, so that many suites can mix it in. Each fixture
calls `addDesign` (or `addTestDesign`) from its body; the calls compose in trait
initialization order, and a later binding of the same type wins:

```scala
import wvlet.uni.test.*
import wvlet.uni.http.{Http, HttpHandler, HttpServer, HttpSyncClient, Request, Response}
import wvlet.uni.http.Http

// Starts a server on a free port for the HttpHandler that each suite binds
trait HttpServerFixture extends UniTest:
  addDesign(
    _.bindProvider[HttpHandler, HttpServer](handler =>
      Http.server.withPort(0).withHandler(handler).start()
    )
  )

// Depends on the server, so the session closes the client before it stops the server
trait HttpClientFixture extends HttpServerFixture:
  addDesign(
    _.bindProvider[HttpServer, HttpSyncClient](server =>
      Http.client.withBaseUri(s"http://localhost:${server.localPort}").newSyncClient
    )
  )

class GreetingApiTest extends HttpClientFixture:
  addDesign(_.bindInstance[HttpHandler](request => Response.ok(s"hello ${request.path}")))

  test("GET /ping") { (client: HttpSyncClient) =>
    client.send(Request.get("/ping")).contentAsString shouldBe Some("hello /ping")
  }

  test("the client is shared by the tests of the suite") { (client: HttpSyncClient) =>
    client.send(Request.get("/again")).contentAsString shouldBe Some("hello /again")
  }
```

- **Shutdown order:** the session closes objects in reverse creation order, so
  dependencies decide the order. If one `close()` throws, the session still
  closes the rest and the error is reported.
- **Startup failures:** an object that fails to build fails the test that needs
  it; the objects already built are still closed.
- **When to call:** call `addDesign` and `addTestDesign` from the body of the
  test class or a fixture trait. Calling them once the tests run throws.

These fixtures, with tests that check startup, reuse, and shutdown against a
real server, are in
[`HttpClientFixtureTest.scala`](https://github.com/wvlet/uni/blob/main/uni-netty/src/test/scala/wvlet/uni/http/netty/HttpClientFixtureTest.scala).

### Per-Test Resources

Bind what each test needs fresh with `addTestDesign`:

```scala
import java.nio.file.{Files, LinkOption, Path}

class TempDir extends AutoCloseable:
  val path: Path = Files.createTempDirectory("test")

  override def close(): Unit = deleteRecursively(path)

  private def deleteRecursively(p: Path): Unit =
    // Do not follow symlinks out of the temp directory
    if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then
      val children = Files.list(p)
      try
        children.forEach(deleteRecursively)
      finally
        children.close()
    Files.deleteIfExists(p)

trait TempDirFixture extends UniTest:
  addTestDesign(_.bindSingleton[TempDir])
```

- **Async tests:** a test's objects stay open until its `Future` or `Rx`
  completes. A loan helper such as `withTempDir { dir => ... }` closes them as
  soon as the body returns, which is too early when the body returns a
  `Future`.
- **Nested tests:** each nested test that takes arguments gets its own session.
  Take the arguments in the nested test; do not capture the parent's.

### Combining Fixtures

Mix fixtures with `extends A with B`. Their bindings are added in trait
initialization order: a trait's after those of the traits it extends, and B's
after A's.

```scala
class UploadApiTest extends HttpClientFixture with TempDirFixture:
  addDesign(_.bindInstance[HttpHandler](request => Response.ok(s"uploaded ${request.path}")))

  test("upload a file") { (client: HttpSyncClient, dir: TempDir) =>
    val file = Files.writeString(dir.path.resolve("a.txt"), "hello")
    // ... send it with `client`
  }
```

The fixtures in
[`DesignTestTest.scala`](https://github.com/wvlet/uni/blob/main/uni-test/src/test/scala/wvlet/uni/test/DesignTestTest.scala)
run on all platforms. They and
[`DesignLifecycleTest.scala`](https://github.com/wvlet/uni/blob/main/uni-test/.jvm/src/test/scala/wvlet/uni/test/DesignLifecycleTest.scala)
check the behavior above with the sbt runner and the JUnit engine used by IDEs.

### Hooks in Fixtures

When a fixture also overrides a lifecycle hook, write `override` and call
`super`, so that the hooks of the other fixtures still run:

```scala
trait AuditFixture extends UniTest:
  override protected def afterAll: Unit =
    try info("suite finished")
    finally super.afterAll
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

```scala test
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
| `Gen.recursive(leaf)(child => branch)` | Finite trees and ASTs (see below) |
| `Gen.lzy(g)` | Defers `g`, for generators that refer to each other |

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

Recursive data such as trees and ASTs needs a generator that stops.
`Gen.recursive` takes a leaf generator and builds a branch from a generator
of its children:

```scala
enum Expr:
  case Num(v: Int)
  case Neg(e: Expr)
  case Add(args: List[Expr])

val expr: Gen[Expr] = Gen.recursive(Gen.chooseNum(0, 9).map(Expr.Num(_))) { child =>
  Gen.oneOfGen(child.map(Expr.Neg(_)), Gen.listOf(child).map(Expr.Add(_)))
}
```

A value has at most `size` branch nodes and is at most `maxDepth` (default 8)
branches deep, and children get half their parent's size, so lists of
subtrees stay small. Leaves are the first choice, so with
[`fuzzGen`](#typed-values-with-fuzzgen) a failing tree reduces toward leaves:
a planted bug in negating a sum reduces to `Neg(Add(List(Num(0))))`.

### Arbitrary and derivation

Implicit `Arbitrary[T]` instances drive the argument-less `forAll` overload.
Defaults ship for primitives, `String`, `Array[Byte]`, `List`, `Vector`,
`Option`, and small tuples. For your own types, use Scala 3's `derives`:

```scala test
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

```scala test
test("sum is never negative for non-negative inputs") {
  forAll { (xs: List[Int]) =>
    // Sum as Long: an Int sum of large inputs overflows
    xs.filter(_ >= 0).map(_.toLong).sum >= 0 shouldBe true
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

```scala test
test("division by non-zero is self-inverse") {
  forAll { (a: Int, b: Int) =>
    (b != 0) ==> {
      // Multiply as Long: a * b can overflow Int
      ((a.toLong * b) / b) shouldBe a.toLong
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

```scala test
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

## Fuzz Testing

`forAll` draws fresh, well-formed values from generators. Fuzzing starts from
real inputs and edits them byte by byte, so a parser sees input that is almost
valid: a truncated string, a length field near `Int.MaxValue`, a reserved
byte. Every `UniTest` gets `fuzz` (bytes) and `fuzzString` (UTF-8 text).

A fuzz target must not fail on any input. It may throw only the exceptions
that `withExpectedCases` accepts as rejections of invalid input; any other
exception, or a failed assertion in the body, is a bug.

```scala
import wvlet.uni.json.JSON
import wvlet.uni.json.JSONParseException
import wvlet.uni.test.fuzz.FuzzConfig

class JSONFuzzTest extends UniTest:
  test("JSON parser rejects invalid input cleanly") {
    fuzzString(
      "json-parse",
      FuzzConfig()
        .withSeeds("""{"a":[1,true,null]}""", "\"text\"")
        .withDictionary("{", "}", "[", "]", "\"", "\\u", "true", "null")
        .withCorpusDir("src/test/resources/fuzz")
        .withExpectedCases { case _: JSONParseException => }
    ) { s =>
      JSON.parseAny(s)
    }
  }
```

A run has two phases:

1. **Replay.** Every seed and every file in `<corpusDir>/<target>/` runs
   first, on every test run, so a fixed bug stays fixed.
2. **Mutate.** Bit flips, boundary integers (1, 2, 4, or 8 bytes in either
   byte order), inserts, deletes, duplicated ranges, splices of two inputs,
   and dictionary tokens, stacked 1 to 4 at a time.

Now and then a mutated input is replaced with fresh random bytes, so a target
without seeds still gets varied input.

On a failure, the input is reduced while it still fails with the same
exception class (delta debugging, then lowering each byte to the smallest
value that still fails), written to `target/fuzz/<target>/crash-<hash>` with a
`.report` file, and reported with the seed:

```text
Fuzz target json-parse failed on input 20 with java.lang.ArrayIndexOutOfBoundsException: ...
  input (1 bytes, reduced from 6): 22
  as text: """
  seed=733437378479625 (replay with UNI_FUZZ_SEED=733437378479625)
  crash_id: java.lang.ArrayIndexOutOfBoundsException@wvlet.uni.json.JSONScanner.scanString:412
  saved to target/fuzz/json-parse/crash-af639f4c860184e5
  keep it as a regression case: mkdir -p src/test/resources/fuzz/json-parse && cp target/fuzz/json-parse/crash-af639f4c860184e5 src/test/resources/fuzz/json-parse/
```

Run the printed command, fix the bug, and the replay phase checks it from then
on. Corpus files are read where a file system is
available (JVM, Scala Native, Scala.js on Node); seeds in code run
everywhere.

### Typed values with `fuzzGen`

`fuzzGen` fuzzes a target over the values of a [generator](#property-based-testing),
the same `Gen` that `forAll` uses. It suits writers and round trips, where
the input is a value rather than bytes:

```scala
import wvlet.uni.test.check.Gen
import wvlet.uni.text.csv.CSV

class CSVFuzzTest extends UniTest:
  test("written records read back the same") {
    val records = Gen.listOf(Gen.nonEmptyListOf(Gen.asciiStr).map(_.toIndexedSeq))
    fuzzGen("csv-write", records) { rs =>
      CSV.parse(CSV.writeRecords(rs)) shouldBe rs
    }
  }
```

The generator draws its random choices from the input bytes, so the runner
still mutates and reduces bytes, and the report shows the generated value
(with control and surrogate characters escaped). When the bytes run out,
every draw is 0, which generators map to their first or smallest choice, so
reducing the bytes shrinks the value: shorter lists, smaller numbers. Inputs
for which the generator throws, such as a `filter` that never matches, are
skipped. A saved crash file replays as long as the generator stays the same.
For a type with an `Arbitrary`, pass `Arbitrary.arbitrary[A]`.

`forAll` and `fuzzGen` complement each other: `forAll` samples a fixed
number of values and shrinks with `Shrink`; `fuzzGen` keeps going for a time
budget (`UNI_FUZZ_SECONDS`) and keeps failures as corpus files.

### Feature guidance

Without coverage instrumentation, the runner still learns which inputs are
worth mutating further: an input that shows a *feature* no earlier input
showed joins the pool, and half of the mutations build on the latest such
finds. Two kinds of features count:

- **Rejections.** Each expected exception class and message, with digits
  removed, is a feature. A parser that fails with `expected ':'` on one input
  and `unterminated string` on another tells the runner the second got
  further, with no extra code.
- **`fuzzFeature(label)`.** A target reports a state it reached, such as a
  branch of a parser or a header it recognized:

```scala skip
fuzz("archive") { bytes =>
  if bytes.startsWith("PK".getBytes) then
    fuzzFeature("zip")
  Archive.read(bytes)
}
```

On a keyword that must match byte by byte, guidance finds a 4-byte match
within 5,000 to 100,000 inputs, depending on the seed; unguided mutation
misses it in 200,000. Failure
reports end with `explored: N features, M inputs kept`.
`noFeatureGuidance` turns it off.

### Slow inputs

`withSlowInputMillis(ms)` fails on an input that runs longer than the limit,
which catches inputs that make a parser quadratic. A slow input is run again
before it counts, so a JIT compilation or GC pause does not fail the test. An
input that never finishes still blocks the test.

### Longer runs

A normal test run mutates 1,000 inputs per target. Environment variables
raise the budget without code changes, for a local session or a nightly job:

```bash
UNI_FUZZ_SECONDS=300 ./sbt "uniJVM/testOnly *FuzzTest"   # 5 minutes per target
UNI_FUZZ_ITERATIONS=100000 ./sbt "uniJVM/testOnly *FuzzTest"
UNI_FUZZ_SEED=733437378479625 ./sbt "uniJVM/testOnly *FuzzTest"  # replay a failure
```

| Knob | Method | Default |
|------|--------|---------|
| Mutated inputs | `withIterations(n)` | 1000 |
| Time budget | `withTimeBudgetMillis(ms)` | none |
| Input length cap | `withMaxLength(n)` | 4096 |
| Seed inputs | `withSeeds(...)` / `withSeedBytes(...)` | none |
| Dictionary | `withDictionary(...)` / `withDictionaryBytes(...)` | none |
| Corpus directory | `withCorpusDir(dir)` | none |
| Crash directory | `withCrashDir(dir)` | `target/fuzz` |
| Expected rejections | `withExpectedCases { case ... => }` | none |
| RNG seed | `withSeed(s)` | `System.nanoTime()` |
| Ignore `UNI_FUZZ_*` | `noEnvOverrides` | overrides on |
| Generator size (`fuzzGen`) | `withGenSize(n)` | 32 |
| Slow-input limit | `withSlowInputMillis(ms)` / `noSlowInputMillis` | none |
| Feature guidance | `withFeatureGuidance` / `noFeatureGuidance` | on |

Override `fuzzConfig` on the test class to share settings across targets.
There is no code-coverage feedback; features stand in for it.

In the Uni repository, the [Fuzz workflow](https://github.com/wvlet/uni/actions/workflows/fuzz.yml)
runs the targets under `uni/src/test/scala/wvlet/uni/fuzz/` for two minutes
each every night, with a fresh seed, and uploads crash files as an artifact.

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

# Filter tests by tag (see Test Tags)
sbt "test -- --tags integration"
sbt "test -- --exclude-tags slow"
```

::: tip Scala.js tests in a real browser
To run Scala.js tests in a real browser (headless Chromium/Firefox/WebKit) with
ES module support — for DOM, web, and Electron code — see the
[sbt-uni-playwright Plugin](/build/sbt-uni-playwright).
:::

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

A suite that skips or cancels itself in its constructor or `beforeAll` is not counted as failed.

In CI (when the `CI` environment variable is set), UniTest on the JVM writes log messages from the thread running a suite (its constructor, hooks, and test bodies) into sbt's output for that suite, so they appear right above the result of the test that logged them. sbt may hold a suite's output until the suite ends, so local runs keep logs going straight to the console, where they show up live. Logs from other threads, such as a server's worker pool or a `Future` on the global pool, always go to the console. Override the default with a test argument:

```scala sbt
Test / testOptions += Tests.Argument("--log-capture")    // or "--no-log-capture"
```

## Best Practices

1. **Avoid mocks** - Use real implementations or in-memory versions
2. **Use Design for wiring** - Bind test objects with `addDesign` / `addTestDesign` and take them as test arguments; share setup through fixture traits that extend `UniTest`. See [Reusable Fixtures](#reusable-fixtures)
3. **Prefer `shouldMatch`** - Use pattern matching for type checks instead of `asInstanceOf`
4. **One behavior per test** - Keep tests focused on a single behavior
5. **Descriptive names** - Test names should describe the expected behavior
6. **Use boolean expressions** - For comparisons: `(x > 5) shouldBe true`
7. **Capture expected errors** - Wrap code that fails on purpose in `captureLogs` and assert on the warning, instead of lowering log levels. See [Testing Error Paths](#testing-error-paths)

## Cross-Platform Support

UniTest works across all Scala 3 platforms:

| Feature | JVM | Scala.js | Scala Native |
|---------|:---:|:--------:|:------------:|
| Basic assertions | ✓ | ✓ | ✓ |
| Nested tests | ✓ | ✓ | ✓ |
| Flaky tests | ✓ | ✓ | ✓ |
| Property-based testing | ✓ | ✓ | ✓ |
| `captureLogs` from other threads | ✓ | - | ✓ |
| IDE integration | ✓ | - | - |

## Package

```scala
import wvlet.uni.test.UniTest
// Property-based testing is part of UniTest — nothing else to import.
// For custom generators / shrinkers:
import wvlet.uni.test.check.{Arbitrary, Gen, Shrink, PropertyConfig}
```
