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

/**
  * Typeclass describing how to reduce a value toward a simpler counter-example when a property
  * fails. The property runner (see [[wvlet.uni.test.PropertyCheck]]) consumes the returned
  * [[LazyList]] lazily and keeps the first candidate that still fails the property, recursing until
  * no further simplification is possible.
  */
trait Shrink[A]:
  def shrink(a: A): LazyList[A]

object Shrink extends LowPriorityShrinks:

  def apply[A](f: A => LazyList[A]): Shrink[A] =
    new Shrink[A]:
      def shrink(a: A): LazyList[A] = f(a)

  /**
    * Default: don't shrink. Returns an empty stream.
    */
  def none[A]: Shrink[A] = apply(_ => LazyList.empty)

  given Shrink[Boolean] = apply {
    case true =>
      LazyList(false)
    case false =>
      LazyList.empty
  }

  given Shrink[Int] = apply { i =>
    shrinkIntegral(i.toLong).filter(x => x.toInt == x).map(_.toInt)
  }

  given Shrink[Long]  = apply(i => shrinkIntegral(i))
  given Shrink[Short] = apply(i =>
    shrinkIntegral(i.toLong).filter(x => x.toShort == x).map(_.toShort)
  )

  given Shrink[Byte] = apply(i => shrinkIntegral(i.toLong).filter(x => x.toByte == x).map(_.toByte))

  given Shrink[Float] = apply { f =>
    shrinkFloating(f.toDouble).map(_.toFloat).filter(x => !java.lang.Float.isNaN(x) && x != f)
  }

  given Shrink[Double] = apply(d => shrinkFloating(d))

  given Shrink[Char] = apply { c =>
    // Shrink toward 'a' (for letters), 'A', or space. Keep it simple: step by halving.
    val candidates =
      if c == 'a' then
        LazyList.empty
      else if c.isLetter then
        LazyList('a', 'A')
      else if c.isDigit then
        LazyList('0')
      else
        LazyList('a', ' ')
    candidates.filter(_ != c)
  }

  given Shrink[String] = apply { s =>
    if s.isEmpty then
      LazyList.empty
    else
      // Shorten by half, then by dropping one char at a time
      val halves =
        if s.length > 1 then
          LazyList(s.substring(0, s.length / 2))
        else
          LazyList.empty
      val drops = LazyList.tabulate(s.length)(i => s.substring(0, i) + s.substring(i + 1))
      (LazyList("") #::: halves #::: drops).distinct
  }

  given Shrink[Array[Byte]] = apply { arr =>
    if arr.isEmpty then
      LazyList.empty
    else
      val halves =
        if arr.length > 1 then
          LazyList(arr.slice(0, arr.length / 2))
        else
          LazyList.empty
      val drops =
        LazyList.tabulate(arr.length) { i =>
          val copy = new Array[Byte](arr.length - 1)
          System.arraycopy(arr, 0, copy, 0, i)
          System.arraycopy(arr, i + 1, copy, i, arr.length - i - 1)
          copy
        }
      LazyList(Array.empty[Byte]) #::: halves #::: drops
  }

  given [A](using elem: Shrink[A]): Shrink[List[A]] = apply { xs =>
    if xs.isEmpty then
      LazyList.empty
    else
      val drops  = LazyList.tabulate(xs.size)(i => xs.take(i) ++ xs.drop(i + 1)).filter(_ != xs)
      val halves =
        if xs.size > 1 then
          LazyList(xs.take(xs.size / 2))
        else
          LazyList.empty
      val elementShrinks =
        LazyList
          .tabulate(xs.size) { i =>
            elem.shrink(xs(i)).map(replaced => xs.updated(i, replaced))
          }
          .flatten
      (LazyList(List.empty[A]) #::: halves #::: drops #::: elementShrinks).distinct
  }

  given [A](using elem: Shrink[A]): Shrink[Option[A]] = apply {
    case None =>
      LazyList.empty
    case Some(a) =>
      Some(a) #:: elem.shrink(a).map(Some(_)).prepended(None)
  }

  given [A, B](using sa: Shrink[A], sb: Shrink[B]): Shrink[(A, B)] = apply { case (a, b) =>
    sa.shrink(a).map(a2 => (a2, b)) #::: sb.shrink(b).map(b2 => (a, b2))
  }

  given [A, B, C](using sa: Shrink[A], sb: Shrink[B], sc: Shrink[C]): Shrink[(A, B, C)] = apply {
    case (a, b, c) =>
      sa.shrink(a).map(a2 => (a2, b, c)) #::: sb.shrink(b).map(b2 => (a, b2, c)) #:::
        sc.shrink(c).map(c2 => (a, b, c2))
  }

  // ---- integral shrinker ----

  /**
    * Shrink candidates for an integral value: 0, and a sequence halving toward 0 (positive), then
    * the negation for negative values. Based on ScalaCheck's classical strategy.
    */
  private def shrinkIntegral(n: Long): LazyList[Long] =
    if n == 0L then
      LazyList.empty
    else
      val zero = LazyList(0L)
      val neg  =
        if n < 0 then
          LazyList(-n)
        else
          LazyList.empty
      def halves(x: Long): LazyList[Long] =
        if x == 0L then
          LazyList.empty
        else
          val diff = n - x
          diff #:: halves(x / 2)
      val base = halves(n / 2).map(n - _)
      (zero #::: neg #::: base).distinct.filter(_ != n)

  // ---- Low-priority fallback ---------------------------------------------------------------

  private[check] def noShrink[A]: Shrink[A] = apply(_ => LazyList.empty)

  private def shrinkFloating(d: Double): LazyList[Double] =
    if d == 0d then
      LazyList.empty
    else if java.lang.Double.isNaN(d) || java.lang.Double.isInfinite(d) then
      LazyList(0d)
    else
      val base = LazyList(0d, d.toLong.toDouble, d / 2)
      base.filter(x => x != d && !java.lang.Double.isNaN(x))

end Shrink

/**
  * Low-priority fallback [[Shrink]] for arbitrary types. Extending this trait from the `Shrink`
  * companion ensures that specific givens (e.g. `Shrink[Int]`) win over this catch-all, while still
  * allowing `forAll[Point]` to compile without the caller having to define a shrinker.
  */
sealed trait LowPriorityShrinks:
  given fallbackShrink[A]: Shrink[A] = Shrink.noShrink
