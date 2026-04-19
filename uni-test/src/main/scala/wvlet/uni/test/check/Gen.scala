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
package wvlet.uni.test.check

import scala.util.Random

/**
  * Parameters threaded through a generator: the random source and the current `size` used by
  * size-aware combinators (e.g. [[Gen.listOf]], [[Gen.stringOf]], [[Gen.sized]]).
  */
final case class Params(rng: Random, size: Int)

/**
  * Random value generator for property-based testing.
  *
  * A [[Gen]] is a function from [[Params]] to a value. Generators compose via [[map]], [[flatMap]],
  * [[filter]], and [[zip]]. Size is threaded through automatically so that size-aware generators
  * (lists, strings, derived ADTs) scale with the property runner's configured size range.
  */
final class Gen[+A](private[check] val run: Params => A):
  def apply(p: Params): A                 = run(p)
  def map[B](f: A => B): Gen[B]           = Gen(p => f(run(p)))
  def flatMap[B](f: A => Gen[B]): Gen[B]  = Gen(p => f(run(p)).run(p))
  def withFilter(p: A => Boolean): Gen[A] = filter(p)

  /**
    * Keep drawing a value until it satisfies the predicate. Bounded retries prevent infinite loops
    * on impossible filters.
    */
  def filter(pred: A => Boolean): Gen[A] = Gen { p =>
    var attempts = 0
    var value    = run(p)
    while !pred(value) do
      attempts += 1
      if attempts > 1000 then
        throw RuntimeException(
          s"Gen.filter: unable to generate a value matching the predicate after 1000 attempts"
        )
      value = run(p)
    value
  }

  /**
    * Pair two generators into a tuple generator.
    */
  def zip[B](other: Gen[B]): Gen[(A, B)] = Gen(p => (run(p), other.run(p)))

end Gen

object Gen:
  def apply[A](f: Params => A): Gen[A] = new Gen[A](f)

  def const[A](a: A): Gen[A] = Gen(_ => a)

  // ---- Numeric ranges ----

  /**
    * Uniformly choose a numeric value in `[min, max]` (inclusive on both ends for integral types).
    * Mirrors ScalaCheck's `Gen.chooseNum[T]` signature so call sites remain source-compatible.
    */
  def chooseNum[T](min: T, max: T)(using c: Choose[T]): Gen[T] = c.choose(min, max)

  /**
    * Generate a positive, non-zero value of type `T`.
    */
  def posNum[T](using p: PosNum[T]): Gen[T] = p.gen

  // ---- Size-aware combinators ----

  /**
    * Build a generator that depends on the current [[Params.size]].
    */
  def sized[A](f: Int => Gen[A]): Gen[A] = Gen(p => f(p.size).run(p))

  /**
    * Override the size parameter for the wrapped generator.
    */
  def resize[A](size: Int, gen: Gen[A]): Gen[A] = Gen(p =>
    gen.run(p.copy(size = math.max(0, size)))
  )

  // ---- Choice ----

  /**
    * Pick one element uniformly at random from a non-empty sequence.
    */
  def oneOf[A](xs: Seq[A]): Gen[A] =
    require(xs.nonEmpty, "oneOf: sequence must be non-empty")
    Gen(p => xs(p.rng.nextInt(xs.size)))

  def oneOf[A](head: A, tail: A*): Gen[A] = oneOf(head +: tail)

  /**
    * Pick one of the supplied generators uniformly.
    */
  def oneOfGen[A](head: Gen[A], tail: Gen[A]*): Gen[A] =
    val all = head +: tail
    Gen(p => all(p.rng.nextInt(all.size)).run(p))

  /**
    * Weighted choice: each generator is selected with probability proportional to its weight.
    */
  def frequency[A](pairs: (Int, Gen[A])*): Gen[A] =
    require(pairs.nonEmpty, "frequency: requires at least one (weight, gen) pair")
    val positive = pairs.filter(_._1 > 0)
    require(positive.nonEmpty, "frequency: at least one weight must be positive")
    // Sum widened to Long: many large weights can overflow Int and then `nextLong(total)` would
    // throw on a negative bound.
    val total = positive.iterator.map(_._1.toLong).sum
    Gen { p =>
      val pick           = (p.rng.nextLong() & Long.MaxValue) % total
      var acc            = 0L
      val iter           = positive.iterator
      var result: Gen[A] = null
      while result == null && iter.hasNext do
        val (w, g) = iter.next()
        acc += w.toLong
        if pick < acc then
          result = g
      result.run(p)
    }

  // ---- Containers ----

  /**
    * Generate a list of exactly `n` elements.
    */
  def listOfN[A](n: Int, gen: Gen[A]): Gen[List[A]] =
    require(n >= 0, s"listOfN: n must be non-negative (was ${n})")
    Gen { p =>
      val buf = scala.collection.mutable.ListBuffer.empty[A]
      var i   = 0
      while i < n do
        buf += gen.run(p)
        i += 1
      buf.toList
    }

  /**
    * Generate a list whose size grows with the current `size` parameter (0..size).
    */
  def listOf[A](gen: Gen[A]): Gen[List[A]] = sized(s => chooseNum(0, s).flatMap(listOfN(_, gen)))

  /**
    * Generate a non-empty list whose size grows with `size` (1..max(1, size)).
    */
  def nonEmptyListOf[A](gen: Gen[A]): Gen[List[A]] = sized(s =>
    chooseNum(1, math.max(1, s)).flatMap(listOfN(_, gen))
  )

  /**
    * Generate a `Set` whose cardinality grows with `size`.
    */
  def setOf[A](gen: Gen[A]): Gen[Set[A]] = listOf(gen).map(_.toSet)

  /**
    * Generate a `Map` whose cardinality grows with `size`.
    */
  def mapOf[K, V](genK: Gen[K], genV: Gen[V]): Gen[Map[K, V]] = listOf(genK.zip(genV)).map(_.toMap)

  /**
    * Generate `None` occasionally and `Some(gen)` otherwise (1:3 ratio).
    */
  def option[A](gen: Gen[A]): Gen[Option[A]] = frequency(1 -> const(None), 3 -> gen.map(Some(_)))

  // ---- Characters ----

  def alphaLowerChar: Gen[Char] = chooseNum(0, 25).map(n => ('a' + n).toChar)
  def alphaUpperChar: Gen[Char] = chooseNum(0, 25).map(n => ('A' + n).toChar)
  def alphaChar: Gen[Char]      = oneOfGen(alphaLowerChar, alphaUpperChar)
  def numChar: Gen[Char]        = chooseNum(0, 9).map(n => ('0' + n).toChar)
  def alphaNumChar: Gen[Char]   = frequency(9 -> alphaChar, 1 -> numChar)

  /**
    * Any 7-bit ASCII character (0x00..0x7f).
    */
  def asciiChar: Gen[Char] = chooseNum(0, 0x7f).map(_.toChar)

  /**
    * Any printable ASCII character (0x20..0x7e).
    */
  def asciiPrintableChar: Gen[Char] = chooseNum(0x20, 0x7e).map(_.toChar)

  // ---- Strings ----

  def stringOfN(n: Int, charGen: Gen[Char]): Gen[String] = Gen { p =>
    val sb = StringBuilder()
    var i  = 0
    while i < n do
      sb.append(charGen.run(p))
      i += 1
    sb.result()
  }

  /**
    * Size-aware string generator: length ∈ [0, size].
    */
  def stringOf(charGen: Gen[Char]): Gen[String] = sized(s =>
    chooseNum(0, s).flatMap(stringOfN(_, charGen))
  )

  def alphaStr: Gen[String]    = stringOf(alphaChar)
  def numStr: Gen[String]      = stringOf(numChar)
  def alphaNumStr: Gen[String] = stringOf(alphaNumChar)
  def asciiStr: Gen[String]    = stringOf(asciiChar)

  /**
    * A non-empty identifier: starts with a letter, followed by alphanumerics.
    */
  def identifier: Gen[String] =
    for
      head <- alphaChar
      rest <- stringOf(alphaNumChar)
    yield s"${head}${rest}"

end Gen

/**
  * Typeclass describing how to uniformly choose a value in `[min, max]` for type `T`.
  */
trait Choose[T]:
  def choose(min: T, max: T): Gen[T]

object Choose:
  given Choose[Int] with
    def choose(min: Int, max: Int): Gen[Int] =
      require(min <= max, s"chooseNum: min (${min}) must be <= max (${max})")
      if min == max then
        Gen.const(min)
      else
        Gen { p =>
          val span = max.toLong - min.toLong + 1L
          (min.toLong + (p.rng.nextLong() & Long.MaxValue) % span).toInt
        }

  given Choose[Long] with
    def choose(min: Long, max: Long): Gen[Long] =
      require(min <= max, s"chooseNum: min (${min}) must be <= max (${max})")
      if min == max then
        Gen.const(min)
      else if min == Long.MinValue && max == Long.MaxValue then
        // Full range — every Long is a valid draw.
        Gen(_.rng.nextLong())
      else
        val width = max - min
        if width > 0 && width < Long.MaxValue then
          // Span fits in a positive, non-max Long; use modulo sampling.
          Gen { p =>
            val span = width + 1L
            min + (p.rng.nextLong() & Long.MaxValue) % span
          }
        else
          // Wide range where `max - min + 1` would overflow. Fall back to rejection sampling so
          // we never return a value outside `[min, max]`. For realistic wide ranges the rejection
          // rate stays below 50% per draw.
          Gen { p =>
            var v = p.rng.nextLong()
            while v < min || v > max do
              v = p.rng.nextLong()
            v
          }

  given Choose[Short] with
    def choose(min: Short, max: Short): Gen[Short] = summon[Choose[Int]]
      .choose(min.toInt, max.toInt)
      .map(_.toShort)

  given Choose[Byte] with
    def choose(min: Byte, max: Byte): Gen[Byte] = summon[Choose[Int]]
      .choose(min.toInt, max.toInt)
      .map(_.toByte)

  given Choose[Double] with
    def choose(min: Double, max: Double): Gen[Double] =
      require(min <= max, s"chooseNum: min (${min}) must be <= max (${max})")
      if min == max then
        Gen.const(min)
      else
        Gen(p => min + p.rng.nextDouble() * (max - min))

end Choose

/**
  * Typeclass witnessing that a numeric type has a positive-value generator. Mirrors ScalaCheck's
  * `Gen.posNum[T]` so existing call sites compile unchanged.
  */
trait PosNum[T]:
  def gen: Gen[T]

object PosNum:
  given PosNum[Byte] =
    new PosNum[Byte]:
      def gen: Gen[Byte] = Gen.chooseNum[Byte](1.toByte, Byte.MaxValue)

  given PosNum[Short] =
    new PosNum[Short]:
      def gen: Gen[Short] = Gen.chooseNum[Short](1.toShort, Short.MaxValue)

  given PosNum[Int] =
    new PosNum[Int]:
      def gen: Gen[Int] = Gen.chooseNum[Int](1, Int.MaxValue)

  given PosNum[Long] =
    new PosNum[Long]:
      def gen: Gen[Long] = Gen.chooseNum[Long](1L, Long.MaxValue)
