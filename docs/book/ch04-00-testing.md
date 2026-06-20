# 4. Testing with UniTest

A service under test needs a database, a payment gateway, and the current
time. The reflex is to mock all three. Uni pushes you the other way: run
the *real* code, and substitute as narrowly as you can. This chapter is
about writing tests that way — and why it produces tests you can trust.

## A first test

Extend `UniTest`, declare cases with `test`, and assert with `shouldBe`:

```scala
import wvlet.uni.test.UniTest

class CalculatorTest extends UniTest:
  test("adds two numbers") {
    val result = 2 + 3
    result shouldBe 5
  }

  test("a list contains a value") {
    Seq("a", "b", "c") shouldContain "b"
  }
```

```bash
$ sbt test
[info] CalculatorTest:
[info]  - adds two numbers
[info]  - a list contains a value
```

`test("name") { … }` is a case; the block is the body. There is no
annotation, no separate runner to register. The assertion vocabulary is
small and reads as English: `shouldBe`, `shouldNotBe`, `shouldContain`,
and `shouldMatch { case … => }` for matching a shape rather than a value.
The [UniTest reference](/core/unitest) has the full list.

## Substitute, don't mock

The interesting question is the service with dependencies. You met the
mechanism already, in [Chapter 3](./ch03-00-design): a `Design` is a
value, and `+` overrides it. A test takes the real application wiring and
swaps in exactly one piece.

```scala
import wvlet.uni.design.Design
import wvlet.uni.test.UniTest

class UserServiceTest extends UniTest:
  test("looks up a user by id") {
    // appDesign is the real production wiring from Chapter 3.
    val testDesign = appDesign +
      Design.newDesign.bindInstance[Database](FakeDatabase())

    testDesign.build[UserService] { users =>
      users.findUser("123") shouldBe Some("Alice")
    }
  }
```

`UserService` is the real class, running its real logic. Only the
`Database` underneath it is a fake — an in-process stand-in with no
network. Every other binding stays production. The test exercises the
code that ships, not a hollowed-out copy of it.

## Why not mock?

A mock encodes *your belief* about how a dependency behaves: "when I call
`query`, it returns these rows." The test then checks your code against
that belief. If your belief is wrong — the real database orders results
differently, throws a different exception, paginates — the mock-based test
passes anyway, and the bug ships. You tested the mock, not the system.

Uni's stance is to keep the real objects and replace only what you must:
the genuinely external edge (a network, a clock, a payment processor)
with an in-process fake you control. Everything between your entry point
and that edge is real, so the test catches the integration mistakes a
mock would paper over. A fake `Database` that actually stores and returns
rows tells you more than a mock that returns a canned list.

This is why Uni ships no mocking framework and the guides steer you away
from one. The tool you need is `Design` override, which you already have.

## One suite, three platforms

Because `UniTest` is itself cross-platform, a test you write once runs
under the JVM, Scala.js, and Scala Native — the same `sbt test` story as
[Chapter 11](./ch11-00-cross-platform), now for your tests. A suite over
shared logic verifies all three builds at once, so "compiles on Native"
becomes "*passes* on Native" without a second test to write.

## Fast on purpose

Notice what these tests don't do: spin up a container, open a socket, hit
a real clock. A `Design`-substituted suite is in-process and
dependency-free, which is why it finishes in well under a second. That
speed is not a bonus — it is the point. A suite you can run on every save
gets run on every save; a slow one gets run before lunch, if then. Keeping
tests fast keeps them used, and the substitution approach is what keeps
them fast.

## What you have, what comes next

You can now test a Uni application the way it's meant to be tested:

- **`extends UniTest`** with `test("…") { … }` and a small assertion
  vocabulary — `shouldBe`, `shouldContain`, `shouldMatch`.
- **`Design` override (`+`)** substitutes one dependency and keeps the
  rest real — the alternative to mocking.
- One suite runs on **all three platforms**, and stays **fast** because
  it's in-process.

Testing comes this early in the book on purpose. With `Design` from
[Chapter 3](./ch03-00-design) in one hand and `UniTest` in the other, you
can now *prove* a service works — before you've even met logging. Every
feature the rest of the book introduces is something you can immediately
write a fast, dependency-free test for.

Next, [Chapter 5](./ch05-00-logging) adds the other thing every service
wants from the start: logging that tells you not just *what* happened but
*where*.

[← 3. Wiring with Design](./ch03-00-design) | [Next → 5. Logging That Finds You](./ch05-00-logging)
