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

import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.deriving.Mirror

/**
  * Default random-value generator for a given type, used by [[wvlet.uni.test.PropertyCheck.forAll]]
  * when no explicit [[Gen]] is provided.
  */
trait Arbitrary[A]:
  def gen: Gen[A]

object Arbitrary:

  /**
    * Summon the generator for a type. Mirrors ScalaCheck's `Arbitrary.arbitrary[T]` helper.
    */
  def arbitrary[A](using arb: Arbitrary[A]): Gen[A] = arb.gen

  def apply[A](g: Gen[A]): Arbitrary[A] =
    new Arbitrary[A]:
      def gen: Gen[A] = g

  // ---- Primitive givens ----

  given Arbitrary[Boolean] = Arbitrary(Gen(_.rng.nextBoolean()))

  given Arbitrary[Byte]  = Arbitrary(Gen(p => p.rng.nextInt().toByte))
  given Arbitrary[Short] = Arbitrary(Gen(p => p.rng.nextInt().toShort))
  given Arbitrary[Int]   = Arbitrary(Gen(_.rng.nextInt()))
  given Arbitrary[Long]  = Arbitrary(Gen(_.rng.nextLong()))

  // Special float/double values to sprinkle in alongside uniformly-random values. NaN is
  // intentionally excluded because `NaN != NaN`, which would break equality-based assertions.
  // Callers that need NaN can build an explicit Gen.
  private val floatSpecials: Seq[Float] = Seq(
    0f,
    -0f,
    1f,
    -1f,
    Float.MinValue,
    Float.MaxValue,
    Float.PositiveInfinity,
    Float.NegativeInfinity
  )

  private val doubleSpecials: Seq[Double] = Seq(
    0d,
    -0d,
    1d,
    -1d,
    Double.MinValue,
    Double.MaxValue,
    Double.PositiveInfinity,
    Double.NegativeInfinity
  )

  given Arbitrary[Float] = Arbitrary(
    Gen { p =>
      if p.rng.nextInt(10) == 0 then
        floatSpecials(p.rng.nextInt(floatSpecials.size))
      else
        var f = java.lang.Float.intBitsToFloat(p.rng.nextInt())
        while java.lang.Float.isNaN(f) do
          f = java.lang.Float.intBitsToFloat(p.rng.nextInt())
        f
    }
  )

  given Arbitrary[Double] = Arbitrary(
    Gen { p =>
      if p.rng.nextInt(10) == 0 then
        doubleSpecials(p.rng.nextInt(doubleSpecials.size))
      else
        var d = java.lang.Double.longBitsToDouble(p.rng.nextLong())
        while java.lang.Double.isNaN(d) do
          d = java.lang.Double.longBitsToDouble(p.rng.nextLong())
        d
    }
  )

  given Arbitrary[Char] = Arbitrary(
    Gen { p =>
      // Sample from the Basic Multilingual Plane, skipping the surrogate range. Full unicode
      // including surrogate pairs is delegated to the String generator.
      var c = p.rng.nextInt(0x10000)
      while c >= 0xd800 && c <= 0xdfff do
        c = p.rng.nextInt(0x10000)
      c.toChar
    }
  )

  given Arbitrary[String] = Arbitrary(
    Gen { p =>
      val target = p.rng.nextInt(math.max(1, p.size + 1)) // 0..size
      val sb     = StringBuilder()
      var count  = 0
      while count < target do
        // 10% chance to emit a supplementary-plane code point (a surrogate pair) to exercise
        // unicode handling; otherwise a BMP character (skipping the surrogate range).
        if p.rng.nextInt(10) == 0 && count + 1 < target then
          val cp = 0x10000 + p.rng.nextInt(0x100000)
          sb.appendAll(Character.toChars(cp))
          count += 2
        else
          var c = p.rng.nextInt(0x10000)
          while c >= 0xd800 && c <= 0xdfff do
            c = p.rng.nextInt(0x10000)
          sb.append(c.toChar)
          count += 1
      sb.result()
    }
  )

  given Arbitrary[Array[Byte]] = Arbitrary(
    Gen { p =>
      val len = p.rng.nextInt(math.max(1, p.size + 1))
      val arr = new Array[Byte](len)
      p.rng.nextBytes(arr)
      arr
    }
  )

  // ---- Container givens ----

  given [A](using arb: Arbitrary[A]): Arbitrary[List[A]]   = Arbitrary(Gen.listOf(arb.gen))
  given [A](using arb: Arbitrary[A]): Arbitrary[Option[A]] = Arbitrary(Gen.option(arb.gen))
  given [A](using arb: Arbitrary[A]): Arbitrary[Vector[A]] = Arbitrary(
    Gen.listOf(arb.gen).map(_.toVector)
  )

  given [A, B](using arbA: Arbitrary[A], arbB: Arbitrary[B]): Arbitrary[(A, B)] = Arbitrary(
    arbA.gen.zip(arbB.gen)
  )

  given [A, B, C](using
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C]
  ): Arbitrary[(A, B, C)] = Arbitrary(
    for
      a <- arbA.gen
      b <- arbB.gen
      c <- arbC.gen
    yield (a, b, c)
  )

  // ---- Derivation (Scala 3 Mirror) ----

  /**
    * Derive an `Arbitrary[T]` for a product or sum type `T`. For products, the generator samples
    * each field independently. For sums (sealed hierarchies / enums), one subtype is picked
    * uniformly. Usage:
    * {{{
    *   case class Point(x: Int, y: Int)
    *   given Arbitrary[Point] = Arbitrary.derived
    * }}}
    */
  inline def derived[T](using m: Mirror.Of[T]): Arbitrary[T] =
    inline m match
      case p: Mirror.ProductOf[T] =>
        val elems = summonOrDeriveAll[p.MirroredElemTypes]
        productArbitrary[T](p, elems)
      case s: Mirror.SumOf[T] =>
        val alts = summonOrDeriveAll[s.MirroredElemTypes]
        sumArbitrary[T](alts)

  /**
    * Summon an `Arbitrary[T]` if one is in implicit scope, otherwise derive it via `Mirror.Of[T]`.
    * Enables `derives Arbitrary` on sealed hierarchies without requiring the user to hand-write an
    * `Arbitrary` for every case class in the tree.
    */
  private inline def summonOrDerive[T]: Arbitrary[T] = summonFrom {
    case existing: Arbitrary[T] =>
      existing
    case m: Mirror.Of[T] =>
      derived[T](using m)
  }

  private inline def summonOrDeriveAll[T <: Tuple]: List[Arbitrary[Any]] =
    inline erasedValue[T] match
      case _: EmptyTuple =>
        Nil
      case _: (h *: t) =>
        summonOrDerive[h].asInstanceOf[Arbitrary[Any]] :: summonOrDeriveAll[t]

  private def productArbitrary[T](
      m: Mirror.ProductOf[T],
      elems: List[Arbitrary[Any]]
  ): Arbitrary[T] =
    val arr = elems.toArray
    Arbitrary(
      Gen { p =>
        val values = new Array[AnyRef](arr.length)
        var i      = 0
        while i < arr.length do
          values(i) = arr(i).gen.apply(p).asInstanceOf[AnyRef]
          i += 1
        m.fromProduct(Tuple.fromArray(values))
      }
    )

  private def sumArbitrary[T](alts: List[Arbitrary[Any]]): Arbitrary[T] =
    require(alts.nonEmpty, "derived Arbitrary: sum type has no alternatives")
    Arbitrary(
      Gen { p =>
        val picked = alts(p.rng.nextInt(alts.size))
        picked.gen.apply(p).asInstanceOf[T]
      }
    )

end Arbitrary
