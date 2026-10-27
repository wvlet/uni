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

Bind objects with `addDesign`, then take them as typed parameters of the test
body. Override bindings with test doubles (in-memory implementations, not
mocks).

```scala
class SignupServiceTest extends UniTest:
  addDesign(
    _.bindImpl[UserRepository, InMemoryUserRepository]
      .bindSingleton[SignupService]
  )

  test("sign up") { (service: SignupService) =>
    service.signup("alice").name shouldBe "alice"
  }
```

- Objects bound with `addDesign` are shared by the suite. Objects bound with
  `addTestDesign`, or not bound at all, are built for each test and closed
  after it, once a returned `Future` or `Rx` completes.
- Take `(session: Session)` to get the test's Design session itself.
- A body without parameters is a plain test and opens no session.

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

## Reusable fixtures

Share servers, clients, or temp directories across suites with traits that call
`addDesign` (suite-wide) or `addTestDesign` (fresh in every test) from their
body. The calls compose in trait initialization order, a later binding of the
same type wins, and the Design session closes objects in reverse creation
order, so dependencies decide the shutdown order.

```scala
import wvlet.uni.http.{Http, HttpHandler, HttpServer, HttpSyncClient, Request, Response}
import wvlet.uni.http.netty.NettyServer

trait HttpServerFixture extends UniTest:
  addDesign(_.bindProvider[HttpHandler, HttpServer](h => NettyServer.withPort(0).withHandler(h).start()))

trait HttpClientFixture extends HttpServerFixture:
  addDesign(
    _.bindProvider[HttpServer, HttpSyncClient](s =>
      Http.client.withBaseUri(s"http://localhost:${s.localPort}").newSyncClient
    )
  )

class GreetingApiTest extends HttpClientFixture:
  addDesign(_.bindInstance[HttpHandler](r => Response.ok(s"hello ${r.path}")))

  test("GET /ping") { (client: HttpSyncClient) =>
    client.send(Request.get("/ping")).contentAsString shouldBe Some("hello /ping")
  }
```

- **Do not write `trait F { this: UniTest => ... }`.** It compiles, but when two
  such fixtures override a hook, only the last one runs. A fixture that
  overrides a hook extends `UniTest` and calls `super.beforeAll` (and so on).
- An object that fails to build fails the test that needs it; the objects
  already built are still closed.
- Use `addTestDesign` instead of a `withX { ... }` loan helper, which closes too
  early when the body returns a `Future`.

Full guide: https://wvlet.org/uni/core/unitest.md#reusable-fixtures

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
