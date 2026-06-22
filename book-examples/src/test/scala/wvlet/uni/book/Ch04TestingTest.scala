/* Compile-checked (and run) examples from Book Chapter 4 — Testing with UniTest.
 * This is a real UniTest suite, so it both compiles and runs in CI. */
package wvlet.uni.book

import wvlet.uni.design.Design
import wvlet.uni.test.UniTest

class Ch04TestingTest extends UniTest:
  test("adds two numbers") {
    val result = 2 + 3
    result shouldBe 5
  }

  test("a list contains a value") {
    Seq("a", "b", "c") shouldContain "b"
  }

  // Substitute, don't mock: take the real wiring and override one binding.
  class Database:
    def lookup(id: String): Option[String] = None
  class FakeDatabase extends Database:
    override def lookup(id: String): Option[String] = Some("Alice")
  class UserService(db: Database):
    def findUser(id: String): Option[String] = db.lookup(id)

  test("looks up a user via a Design override") {
    val appDesign = Design.newDesign
      .bindSingleton[Database]
      .bindSingleton[UserService]

    val testDesign = appDesign +
      Design.newDesign.bindInstance[Database](FakeDatabase())

    testDesign.build[UserService] { users =>
      users.findUser("123") shouldBe Some("Alice")
    }
  }
