# Testing with UniTest

`uni-test` runs on JVM, Scala.js, and Scala Native and needs no other test
library.

```scala sbt
// build.sbt
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "<version>" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

Full reference: https://wvlet.org/uni/core/unitest.md

## Test class

Import the whole package: matchers such as `defined` and `empty` live next to
`UniTest`.

```scala
import wvlet.uni.test.*

class CalculatorTest extends UniTest:
  test("add") {
    (1 + 2) shouldBe 3
  }

  test("nested") {
    val x = 10
    test("double") { (x * 2) shouldBe 20 }   // reported as "nested / double"
  }
```

## Assertions

Use only these; ScalaTest/MUnit matchers do not exist here.

```scala
x shouldBe y                       // deep equality for Seq, Array, case classes
x shouldNotBe y
(n >= 1) shouldBe true             // comparisons: not `n should be >= 1`
opt shouldBe defined               // Option / collection non-empty
opt shouldBe empty
seq shouldContain elem
str shouldContain "sub"
v shouldMatch { case Some(_: User) => }   // type/shape checks: not asInstanceOf
a shouldBeTheSameInstanceAs b
val e = intercept[IllegalArgumentException] { parse("bad") }
```

## Test control

```scala skip
test("jvm only") {
  if !isScalaJVM then skip("needs the JVM")   // also isScalaJS, isScalaNative
}
test("todo") { pending("not implemented yet") }
test("calls a remote service", Flaky) { ... }   // tag: failures become skipped
test("partly flaky") { flaky { ... } }
```

Mark flaky tests with the `Flaky` tag. There is no `flaky = true` parameter.

## Tags

Pass tags after the test name to group tests by layer, then select or skip a
group from sbt (see Running). Built-ins: `Integration`, `Smoke`, `Slow`,
`Flaky`, `UI`, and `Electron` (for Electron desktop apps).

```scala
case object Gpu extends TestTag("gpu")     // reusable custom tag

class LayeredTest extends UniTest:
  test("renders the toolbar", UI) {
    (1 + 1) shouldBe 2
  }
  test("hits the real database", Integration, Slow) {
    (1 + 1) shouldBe 2
  }
  test("trains on the GPU", Gpu, TestTag("nightly")) {
    (1 + 1) shouldBe 2
  }
```

Filters use the tag's name (`ui`, `slow`, `gpu`). Repeated `--tags` are ANDed,
and `--exclude-tags` wins over `--tags`. Tags apply to top-level tests; nested
tests run with their parent.

## Wiring with Design

Build the object under test from a `Design`; override bindings with test
doubles (in-memory implementations, not mocks).

```scala
import wvlet.uni.design.Design

class SignupServiceTest extends UniTest:
  private val design = Design.newDesign
    .bindImpl[UserRepository, InMemoryUserRepository]
    .bindSingleton[SignupService]

  test("sign up") {
    design.build[SignupService] { service =>
      service.signup("alice").name shouldBe "alice"
    }
  }
```

## Testing an HTTP or RPC server

Start the server on a free port inside the test and call it with the real
client. The block form stops the server afterwards.

```scala test
import wvlet.uni.http.Http
import wvlet.uni.http.netty.{NettyServer, RPCHandler}
import wvlet.uni.http.rpc.RPCRouter

test("RPC round trip") {
  NettyServer
    .withPort(0)
    .withRxHandler(RPCHandler(RPCRouter.of[UserService](UserServiceImpl())))
    .start { server =>
      val http = Http.client.withBaseUri(s"http://localhost:${server.localPort}").newSyncClient
      val client = UserServiceClient.SyncClient(http) // generated, see rpc.md
      client.getUser(1).name shouldBe "alice"
    }
}
```

## Property-based tests

```scala test
import wvlet.uni.test.check.Gen

test("reverse twice") {
  forAll { (xs: List[Int]) => xs.reverse.reverse shouldBe xs }
}
test("positive") {
  forAll(Gen.posNum[Int]) { n => (n > 0) shouldBe true }
}
```

## Running

```bash
sbt test
sbt "testOnly *SignupServiceTest"
sbt "testOnly *SignupServiceTest -- -t:sign"     # filter by test name
sbt "testOnly * -- -l debug"                    # debug logging
sbt "test -- --tags integration"                 # only tagged tests
sbt "test -- --tags ui --tags slow"              # tagged both ui AND slow
sbt "test -- --exclude-tags slow"                # everything except slow
sbt "test -- --exclude-tags flaky"               # skip known-flaky tests
```
