/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.test

import scala.concurrent.Future

/**
  * Self-test for UniTest framework
  */
class UniTestSelfTest extends UniTest:

  test("basic shouldBe assertion") {
    1 + 1 shouldBe 2
    "hello" shouldBe "hello"
    true shouldBe true
  }

  test("shouldNotBe assertion") {
    1 shouldNotBe 2
    "hello" shouldNotBe "world"
  }

  test("collection assertions") {
    val list = List(1, 2, 3)

    list shouldBe List(1, 2, 3)
    list shouldNotBe List(1, 2)
    list shouldContain 2
    list shouldNotContain 5
  }

  test("option assertions") {
    val some: Option[Int] = Some(42)
    val none: Option[Int] = None

    some shouldBe defined
    some shouldNotBe empty
    none shouldBe empty
    none shouldNotBe defined
  }

  test("string containment") {
    val str = "Hello, UniTest!"

    str shouldContain "UniTest"
    str shouldNotContain "AirSpec"
  }

  test("pattern matching") {
    val result: Any = ("hello", 42)

    result shouldMatch { case (s: String, n: Int) =>
    }
  }

  test("instance equality") {
    val a = "hello"
    val b = a
    val c = String("hello")

    a shouldBeTheSameInstanceAs b
    // Note: String interning may make a and c the same instance
  }

  test("floating point comparison") {
    assertEquals(0.1 + 0.2, 0.3, 0.0001)
    assertEquals(3.14f, 3.14f, 0.001f)
  }

  test("intercept exception") {
    val e = intercept[IllegalArgumentException] {
      throw IllegalArgumentException("test error")
    }
    e.getMessage shouldContain "test error"
  }

  test("nested tests") {
    val data = List(1, 2, 3)

    test("first element") {
      data.head shouldBe 1
    }

    test("last element") {
      data.last shouldBe 3
    }

    test("size") {
      data.size shouldBe 3
    }
  }

  test("logging works") {
    // Suppress log output during this test to keep test output clean
    logger.suppressLogs {
      debug("This is a debug message")
      trace("This is a trace message")
      info("This is an info message")
    }
    1 shouldBe 1
  }

  test("assert condition") {
    assert(1 + 1 == 2)
    assert(true)
    assert("hello".nonEmpty)
  }

  test("assert with message") {
    assert(1 + 1 == 2, "Math should work")
    assert(true, "True should be true")
  }

  test("assert failure shows message") {
    val e = intercept[AssertionFailure] {
      assert(false, "Custom failure message")
    }
    e.getMessage shouldContain "Custom failure message"
  }

  // Flaky test that passes - should succeed normally
  test("flaky test that passes", flaky = true) {
    1 + 1 shouldBe 2
  }

  test("flaky test converts failure to skipped") {
    // Create a flaky test that always fails
    val flakyTest = TestDef(
      "failing-flaky",
      () => throw RuntimeException("intentional"),
      Nil,
      isFlaky = true
    )
    val result = executeTest(flakyTest)
    result shouldMatch {
      case TestResult.Skipped(_, msg) if msg.contains("[flaky]") =>
    }
  }

  test("non-flaky test reports failure") {
    val normalTest = TestDef(
      "failing-normal",
      () => throw RuntimeException("intentional"),
      Nil,
      isFlaky = false
    )
    val result = executeTest(normalTest)
    result shouldMatch { case TestResult.Error(_, _, _) =>
    }
  }

  test("shouldNotBe defined for collections") {
    val emptyList: List[Int] = Nil
    val nonEmptyList         = List(1, 2, 3)

    emptyList shouldNotBe defined
    nonEmptyList shouldNotBe empty
  }

  test("shouldNotBe empty for strings") {
    val str      = "hello"
    val emptyStr = ""

    str shouldNotBe empty
    emptyStr shouldBe empty
  }

  test("null matchers") {
    val nullValue: String    = null
    val nonNullValue: String = "hello"

    nullValue shouldBe `null`
    nonNullValue shouldNotBe `null`
  }

  test("null matcher failure messages") {
    val e1 = intercept[AssertionFailure] {
      "hello" shouldBe `null`
    }
    e1.getMessage shouldContain "Expected null"

    val e2 = intercept[AssertionFailure] {
      val x: String = null
      x shouldNotBe `null`
    }
    e2.getMessage shouldContain "Expected not null"
  }

  test("Future test support") {
    test("successful Future is auto-awaited") {
      val result = Future.successful(42)
      result
    }

    test("Future returning a string") {
      Future.successful("hello")
    }

    test("failed Future surfaces exception") {
      val failingTest = TestDef(
        "failing-future",
        () => Future.failed(RuntimeException("future error")),
        Nil,
        isFlaky = false
      )
      val result = executeTest(failingTest)
      result shouldMatch { case TestResult.Error(_, msg, _) =>
        msg shouldContain "future error"
      }
    }
  }

  test("source snippet is captured") {
    val e = intercept[AssertionFailure] {
      1 shouldBe 2
    }
    // Verify the source location is captured
    e.source.fileName shouldBe "UniTestSelfTest.scala"
    e.source.line shouldBe 226
    // Verify the source line content is captured
    e.source.sourceLine shouldContain "shouldBe"
    // Verify formatSnippet works
    val snippet = e.source.formatSnippet
    snippet shouldBe defined
    snippet.get shouldContain "1 shouldBe 2"
    snippet.get shouldContain "^"
  }

end UniTestSelfTest
