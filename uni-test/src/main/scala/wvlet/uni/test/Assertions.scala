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

import scala.reflect.ClassTag

/**
  * Marker object for `shouldBe defined` check
  */
sealed trait DefinedMatcher
object defined extends DefinedMatcher

/**
  * Marker object for `shouldBe empty` check
  */
sealed trait EmptyMatcher
object empty extends EmptyMatcher

/**
  * Marker object for `shouldBe null` check
  */
sealed trait NullMatcher
object `null` extends NullMatcher

/**
  * Test result type used by matchers
  */
private[test] sealed trait MatchResult
private[test] case object Ok     extends MatchResult
private[test] case object Failed extends MatchResult

private[test] object MatchResult:
  def check(cond: Boolean): MatchResult =
    if cond then
      Ok
    else
      Failed

/**
  * Provides assertion methods for testing. Import or mix in this trait to use shouldBe,
  * shouldNotBe, etc.
  */
trait Assertions:

  import MatchResult.check

  /**
    * PartialFunction matcher for arrays, Iterable, and Product. Based on airspec's
    * arrayDeepEqualMatcher.
    */
  private def arrayDeepEqualMatcher: PartialFunction[(Any, Any), MatchResult] =
    case (a: Array[Int], b: Array[Int]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Short], b: Array[Short]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Byte], b: Array[Byte]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Char], b: Array[Char]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Long], b: Array[Long]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Boolean], b: Array[Boolean]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Float], b: Array[Float]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[Double], b: Array[Double]) =>
      check(java.util.Arrays.equals(a, b))
    case (a: Array[AnyRef], b: Array[AnyRef]) =>
      check(
        java
          .util
          .Arrays
          .deepEquals(
            a.asInstanceOf[Array[java.lang.Object]],
            b.asInstanceOf[Array[java.lang.Object]]
          )
      )
    case (a: Iterable[?], b: Iterable[?]) =>
      check(a == b)
    case (a: Product, b: Product) =>
      check(a == b)

  end arrayDeepEqualMatcher

  /**
    * Test equality using PartialFunction chaining (like airspec). Order: 1. arrayDeepEqualMatcher
    * (arrays, Iterable, Product) 2. platformSpecificMatcher (js.Object on Scala.js) 3. Default
    * fallback (value == expected)
    */
  private def test(value: Any, expected: Any): MatchResult = arrayDeepEqualMatcher
    .orElse(compat.platformSpecificMatcher)
    .orElse[(Any, Any), MatchResult] { case _ =>
      check(value == expected)
    }
    .apply((value, expected))

  extension [A](actual: A)
    /**
      * Assert that actual equals expected. Uses Any type for expected to match airspec behavior.
      */
    inline infix def shouldBe(expected: Any)(using source: TestSource): Unit =
      test(actual, expected) match
        case Ok =>
          ()
        case Failed =>
          throw AssertionFailure(s"Expected <${expected}> but got <${actual}>", source)

    /**
      * Assert that actual does not equal expected
      */
    inline infix def shouldNotBe(expected: Any)(using source: TestSource): Unit =
      test(actual, expected) match
        case Ok =>
          throw AssertionFailure(s"Expected not <${expected}> but got the same value", source)
        case Failed =>
          ()

    /**
      * Assert that value is null (using literal null)
      */
    inline infix def shouldBe(expected: Null)(using source: TestSource): Unit =
      if actual != null then
        throw AssertionFailure(s"Expected null but got <${actual}>", source)

    /**
      * Assert that value is not null (using literal null)
      */
    inline infix def shouldNotBe(expected: Null)(using source: TestSource): Unit =
      if actual == null then
        throw AssertionFailure("Expected not null but got null", source)

    /**
      * Assert that option/collection is defined (non-empty)
      */
    inline infix def shouldBe(matcher: DefinedMatcher)(using source: TestSource): Unit =
      if !Assertions.isDefinedValue(actual) then
        throw AssertionFailure(s"Expected defined but got <${actual}>", source)

    /**
      * Assert that option/collection is empty
      */
    inline infix def shouldBe(matcher: EmptyMatcher)(using source: TestSource): Unit =
      if !Assertions.isEmptyValue(actual) then
        throw AssertionFailure(s"Expected empty but got <${actual}>", source)

    /**
      * Assert that value is null
      */
    inline infix def shouldBe(matcher: NullMatcher)(using source: TestSource): Unit =
      if actual != null then
        throw AssertionFailure(s"Expected null but got <${actual}>", source)

    /**
      * Assert that value is not null
      */
    inline infix def shouldNotBe(matcher: NullMatcher)(using source: TestSource): Unit =
      if actual == null then
        throw AssertionFailure("Expected not null but got null", source)

    /**
      * Assert that actual is the same instance as expected (reference equality)
      */
    inline infix def shouldBeTheSameInstanceAs(expected: A)(using source: TestSource): Unit =
      if !(actual.asInstanceOf[AnyRef] eq expected.asInstanceOf[AnyRef]) then
        throw AssertionFailure(
          s"Expected same instance as <${expected}> but got different instance <${actual}>",
          source
        )

    /**
      * Assert that actual is not the same instance as expected
      */
    inline infix def shouldNotBeTheSameInstanceAs(expected: A)(using source: TestSource): Unit =
      if actual.asInstanceOf[AnyRef] eq expected.asInstanceOf[AnyRef] then
        throw AssertionFailure(
          s"Expected different instance but got the same instance <${actual}>",
          source
        )

    /**
      * Assert that actual matches the given partial function, then run the matching case, so
      * assertions inside it are checked too
      */
    inline infix def shouldMatch(pf: PartialFunction[A, Any])(using source: TestSource): Unit = pf
      .applyOrElse(
        actual,
        _ => throw AssertionFailure(s"Value <${actual}> did not match the expected pattern", source)
      )

    /**
      * Assert that option/collection is not defined (is empty)
      */
    inline infix def shouldNotBe(matcher: DefinedMatcher)(using source: TestSource): Unit =
      if Assertions.isDefinedValue(actual) then
        throw AssertionFailure(s"Expected not defined but got <${actual}>", source)

    /**
      * Assert that option/collection is not empty
      */
    inline infix def shouldNotBe(matcher: EmptyMatcher)(using source: TestSource): Unit =
      if Assertions.isEmptyValue(actual) then
        throw AssertionFailure(s"Expected not empty but got <${actual}>", source)

  end extension

  extension [A](actual: Iterable[A])
    /**
      * Assert that collection contains the element
      */
    inline infix def shouldContain(element: A)(using source: TestSource): Unit =
      if !actual.exists(_ == element) then
        throw AssertionFailure(s"Expected <${actual}> to contain <${element}>", source)

    /**
      * Assert that collection does not contain the element
      */
    inline infix def shouldNotContain(element: A)(using source: TestSource): Unit =
      if actual.exists(_ == element) then
        throw AssertionFailure(s"Expected <${actual}> not to contain <${element}>", source)

  extension (actual: String)
    /**
      * Assert that string contains the substring
      */
    inline infix def shouldContain(substring: String)(using source: TestSource): Unit =
      if !actual.contains(substring) then
        throw AssertionFailure(s"Expected <${actual}> to contain <${substring}>", source)

    /**
      * Assert that string does not contain the substring
      */
    inline infix def shouldNotContain(substring: String)(using source: TestSource): Unit =
      if actual.contains(substring) then
        throw AssertionFailure(s"Expected <${actual}> not to contain <${substring}>", source)

  /**
    * Assert that the given block throws an exception of type E
    */
  inline def intercept[E <: Throwable](body: => Any)(using ct: ClassTag[E], source: TestSource): E =
    try
      body
      throw AssertionFailure(
        s"Expected exception of type ${ct.runtimeClass.getName} but no exception was thrown",
        source
      )
    catch
      case e: E if ct.runtimeClass.isInstance(e) =>
        e
      case e: AssertionFailure =>
        throw e
      case e: Throwable =>
        throw AssertionFailure(
          s"Expected exception of type ${ct.runtimeClass.getName} but got ${e.getClass.getName}: ${e
              .getMessage}",
          source
        )

  /**
    * Assert equality of floating point numbers with a delta tolerance
    */
  inline def assertEquals(actual: Float, expected: Float, delta: Float)(using
      source: TestSource
  ): Unit =
    if Math.abs(actual - expected) > delta then
      throw AssertionFailure(s"Expected <${expected}> +/- ${delta} but got <${actual}>", source)

  /**
    * Assert equality of double numbers with a delta tolerance
    */
  inline def assertEquals(actual: Double, expected: Double, delta: Double)(using
      source: TestSource
  ): Unit =
    if Math.abs(actual - expected) > delta then
      throw AssertionFailure(s"Expected <${expected}> +/- ${delta} but got <${actual}>", source)

  /**
    * Assert that a condition is true
    */
  inline def assert(cond: => Boolean)(using source: TestSource): Unit =
    if !cond then
      throw AssertionFailure("Assertion failed", source)

  /**
    * Assert that a condition is true with a custom message
    */
  inline def assert(cond: => Boolean, message: => String)(using source: TestSource): Unit =
    if !cond then
      throw AssertionFailure(message, source)

  /**
    * Run `body` and fail if Rx subscriptions it started on this thread are still active afterwards,
    * i.e., neither cancelled nor ended with `OnError` / `OnCompletion`. Returns the result of
    * `body`.
    *
    * {{{
    *   test("closes its subscriptions") {
    *     assertNoRxLeaks {
    *       val c = counter.subscribe(render)
    *       c.cancel
    *     }
    *   }
    * }}}
    */
  inline def assertNoRxLeaks[A](body: => A)(using source: TestSource): A =
    val tracker = wvlet.uni.rx.RxTracker()
    val result  = tracker.trace(body)
    val leaks   = tracker.activeSubscriptions
    if leaks.nonEmpty then
      throw AssertionFailure(
        s"${leaks.size} Rx subscription(s) still active:\n${leaks.mkString("  ", "\n  ", "")}",
        source
      )
    result

end Assertions

object Assertions:

  /**
    * Check if a value is considered "defined" (non-empty for Option/Iterable/String, non-null
    * otherwise)
    */
  def isDefinedValue(value: Any): Boolean =
    value match
      case opt: Option[?] =>
        opt.isDefined
      case seq: Iterable[?] =>
        seq.nonEmpty
      case str: String =>
        str.nonEmpty
      case null =>
        false
      case _ =>
        true

  /**
    * Check if a value is considered "empty" (empty for Option/Iterable/String, null)
    */
  def isEmptyValue(value: Any): Boolean =
    value match
      case opt: Option[?] =>
        opt.isEmpty
      case seq: Iterable[?] =>
        seq.isEmpty
      case str: String =>
        str.isEmpty
      case null =>
        true
      case _ =>
        false

end Assertions
