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

import scala.collection.mutable
import scala.util.Random
import wvlet.uni.test.check.Arbitrary
import wvlet.uni.test.check.DiscardException
import wvlet.uni.test.check.Gen
import wvlet.uni.test.check.Params
import wvlet.uni.test.check.PropertyConfig
import wvlet.uni.test.check.Shrink

/**
  * Property-based testing support, self-contained within uni-test (no external dependency).
  *
  * Already mixed into [[UniTest]], so any test class picks up [[forAll]] automatically. Mixing it
  * in explicitly is harmless and kept for backward compatibility.
  *
  * Each property runs [[PropertyConfig.minSuccessfulTests]] random samples; a failing sample is
  * shrunk to a minimal counter-example (see [[Shrink]]), and the failing input plus the RNG seed
  * are reported so the run can be replayed by overriding [[propertyConfig]] with `withSeed(...)`.
  *
  * Example:
  * {{{
  *   class MyTest extends UniTest:
  *     test("property test") {
  *       forAll { (a: Int, b: Int) =>
  *         (a + b) shouldBe (b + a)
  *       }
  *     }
  * }}}
  */
trait PropertyCheck:

  /**
    * Number of successful samples required for a property to pass. Overriding this method is the
    * simplest way to tighten or loosen an individual test; for finer control (seed, size range,
    * discard budget) override [[propertyConfig]] instead.
    */
  protected def minSuccessfulTests: Int = PropertyConfig.default.minSuccessfulTests

  /**
    * Override to customise the property runner (seed, size range, discard budget, shrink budget,
    * etc.). The default honours [[minSuccessfulTests]] so that overriding just the sample count
    * continues to work.
    */
  protected def propertyConfig: PropertyConfig = PropertyConfig
    .default
    .withMinSuccessful(minSuccessfulTests)

  // ---- Classify/collect support ---------------------------------------------------------------

  // Per-thread stack of stats collectors. Using a ThreadLocal keeps `classify`/`collect` correct
  // when two threads in the same suite run property checks concurrently.
  private val statsStack =
    new ThreadLocal[mutable.ArrayDeque[mutable.LinkedHashMap[String, Int]]]:
      override def initialValue(): mutable.ArrayDeque[mutable.LinkedHashMap[String, Int]] =
        mutable.ArrayDeque.empty

  private def currentStats: Option[mutable.LinkedHashMap[String, Int]] =
    val s = statsStack.get()
    if s.isEmpty then
      None
    else
      Some(s.last)

  /**
    * Record a classification label within the current property. Only active while a `forAll` is
    * running; counts are surfaced in the final success message.
    */
  protected def classify(cond: Boolean, ifTrue: String, ifFalse: String = ""): Unit = currentStats
    .foreach { stats =>
      val label =
        if cond then
          ifTrue
        else
          ifFalse
      if label.nonEmpty then
        stats.update(label, stats.getOrElse(label, 0) + 1)
    }

  /**
    * Record a classification label for the current property. Typical use:
    * `collect(s"bucket=${bucket(n)}")`.
    */
  protected def collect(label: String): Unit = currentStats.foreach(stats =>
    stats.update(label, stats.getOrElse(label, 0) + 1)
  )

  // ---- ==> operator ---------------------------------------------------------------------------

  extension (cond: Boolean)
    /**
      * Discard the current sample if `cond` is false. The runner retries with a new sample, failing
      * the property if too many discards accumulate. Reads naturally as "precondition implies
      * property".
      *
      * {{{
      *   forAll { (n: Int) =>
      *     (n != 0) ==> {
      *       ((n * 0) == 0) shouldBe true
      *     }
      *   }
      * }}}
      */
    inline def ==>[A](inline body: => A): A =
      if cond then
        body
      else
        throw DiscardException()

    /**
      * English-language alias for [[==>]]: `cond implies body`. Same discard semantics.
      */
    inline def implies[A](inline body: => A): A =
      if cond then
        body
      else
        throw DiscardException()

  // ---- Runner ---------------------------------------------------------------------------------

  private def runnerSeed(): Long = propertyConfig.seed.getOrElse(System.nanoTime())

  private def mkParams(rng: Random, config: PropertyConfig, sampleIdx: Int, total: Int): Params =
    val span = math.max(1, config.maxSize - config.minSize)
    // Linearly grow size with sample index so early samples stay small (helpful for shrinking).
    // Use a Long intermediate: for large `maxSize * minSuccessfulTests`, the Int product overflows.
    val size = config.minSize + ((sampleIdx.toLong * span) / math.max(1, total)).toInt
    Params(rng, math.min(config.maxSize, size))

  /**
    * Core runner used by every `forAll` overload. `sample` produces the next tuple of inputs from
    * the current `Params`; `describe` formats them for error messages; `body` is the property; and
    * `shrinkInput` returns a lazy list of simpler candidate inputs.
    */
  private def runProperty[I](
      sample: Params => I,
      describe: I => String,
      body: I => Any,
      shrinkInput: I => LazyList[I]
  ): Unit =
    val config = propertyConfig
    val seed   = runnerSeed()
    val rng    = Random(seed)
    val stats  = mutable.LinkedHashMap.empty[String, Int]
    val stack  = statsStack.get()
    stack += stats
    var succeeded = 0
    var discarded = 0
    try
      while succeeded < config.minSuccessfulTests do
        val params  = mkParams(rng, config, succeeded + discarded, config.minSuccessfulTests)
        val input   = sample(params)
        val outcome =
          try
            body(input)
            Outcome.Passed
          catch
            case _: DiscardException =>
              Outcome.Discarded
            case af: AssertionFailure =>
              Outcome.Failed(af)
            case e: Throwable =>
              Outcome.Failed(e)
        outcome match
          case Outcome.Passed =>
            succeeded += 1
          case Outcome.Discarded =>
            discarded += 1
            if discarded > config.maxDiscarded then
              throw AssertionFailure(
                s"Property check gave up after ${discarded} discards (seed=${seed})",
                TestSource.generate
              )
          case Outcome.Failed(e) =>
            val minimal = minimise(input, body, shrinkInput, config.maxShrinks)
            throw AssertionFailure(
              buildFailureMessage(describe(minimal), e, seed),
              TestSource.generate
            )
    finally stack.removeLast()
    end try

    if stats.nonEmpty then
      val formatted = stats
        .toSeq
        .sortBy { case (_, n) =>
          -n
        }
        .map { case (l, n) =>
          s"${n} × ${l}"
        }
        .mkString(", ")
      println(s"[property] ${formatted}")

  end runProperty

  private def buildFailureMessage(desc: String, e: Throwable, seed: Long): String =
    val prefix =
      e match
        case af: AssertionFailure =>
          af.getMessage
        case other =>
          Option(other.getMessage).getOrElse(other.getClass.getSimpleName)
    s"${prefix} (property input: ${desc}, seed=${seed})"

  /**
    * Greedy shrink: given a failing input, try each candidate from `shrinkInput(current)` and keep
    * the first that also fails. Recurse until no candidate still fails, up to `maxSteps`.
    */
  private def minimise[I](
      initial: I,
      body: I => Any,
      shrinkInput: I => LazyList[I],
      maxSteps: Int
  ): I =
    var current = initial
    var steps   = 0
    var done    = false
    while !done && steps < maxSteps do
      val next = shrinkInput(current).find { candidate =>
        steps += 1
        if steps > maxSteps then
          false
        else
          try
            body(candidate)
            false
          catch
            case _: DiscardException =>
              false
            case _: Throwable =>
              true
      }
      next match
        case Some(smaller) =>
          current = smaller
        case None =>
          done = true
    current

  end minimise

  private enum Outcome:
    case Passed
    case Discarded
    case Failed(cause: Throwable)

  /**
    * Pretty-print a generated value for error messages. Expands arrays since their default
    * `toString` is the unreadable `[B@…` form.
    */
  private def display(a: Any): String =
    a match
      case null =>
        "null"
      case ba: Array[Byte] =>
        // Cap long arrays so a failing large sample can't blow up the failure message.
        if ba.length > 32 then
          ba.take(32).mkString("Array(", ", ", s", …+${ba.length - 32} bytes)")
        else
          ba.mkString("Array(", ", ", ")")
      case s: String =>
        s"\"${s}\""
      case other =>
        String.valueOf(other)

  // ---- forAll with implicit Arbitrary generators --------------------------------------------

  def forAll[A](body: A => Any)(using arb: Arbitrary[A], sa: Shrink[A]): Unit = runProperty[A](
    arb.gen.apply,
    display,
    body,
    sa.shrink
  )

  def forAll[A, B](body: (A, B) => Any)(using Arbitrary[(A, B)], Shrink[(A, B)]): Unit =
    forAll[(A, B)](t => body(t._1, t._2))

  def forAll[A, B, C](body: (A, B, C) => Any)(using Arbitrary[(A, B, C)], Shrink[(A, B, C)]): Unit =
    forAll[(A, B, C)](t => body(t._1, t._2, t._3))

  def forAll[A, B, C, D](body: (A, B, C, D) => Any)(using
      Arbitrary[(A, B, C, D)],
      Shrink[(A, B, C, D)]
  ): Unit = forAll[(A, B, C, D)](t => body(t._1, t._2, t._3, t._4))

  def forAll[A, B, C, D, E](body: (A, B, C, D, E) => Any)(using
      Arbitrary[(A, B, C, D, E)],
      Shrink[(A, B, C, D, E)]
  ): Unit = forAll[(A, B, C, D, E)](t => body(t._1, t._2, t._3, t._4, t._5))

  // ---- forAll with explicit Gen (no shrinking) ----------------------------------------------

  def forAll[A](gen: Gen[A])(body: A => Any): Unit = runProperty[A](
    gen.apply,
    display,
    body,
    _ => LazyList.empty
  )

  def forAll[A, B](genA: Gen[A], genB: Gen[B])(body: (A, B) => Any): Unit =
    forAll(genA.zip(genB))(t => body(t._1, t._2))

  def forAll[A, B, C](genA: Gen[A], genB: Gen[B], genC: Gen[C])(body: (A, B, C) => Any): Unit =
    forAll(
      genA
        .zip(genB)
        .zip(genC)
        .map { case ((a, b), c) =>
          (a, b, c)
        }
    ) { t =>
      body(t._1, t._2, t._3)
    }

  def forAll[A, B, C, D](genA: Gen[A], genB: Gen[B], genC: Gen[C], genD: Gen[D])(
      body: (A, B, C, D) => Any
  ): Unit =
    forAll(
      genA
        .zip(genB)
        .zip(genC)
        .zip(genD)
        .map { case (((a, b), c), d) =>
          (a, b, c, d)
        }
    ) { t =>
      body(t._1, t._2, t._3, t._4)
    }

end PropertyCheck
