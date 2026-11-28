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
package wvlet.uni.test.result

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import wvlet.uni.test.AssertionFailure
import wvlet.uni.test.TestSource

/** How [[ResultCompare]] treats the order of rows. */
enum ResultOrder:
  /** Any order: the same rows with the same multiplicities. */
  case Bag

  /** The same rows in the same order. */
  case Ordered

  /**
    * Rows in the order of a sort key, as under ORDER BY: consecutive rows with equal keys (ties)
    * compare as a bag.
    */
  case OrderedBy(key: Any => Any)

  /** Only the number of rows, for results whose rows are not determined (LIMIT, sampling). */
  case RowCount

/**
  * Rules for comparing results with [[ResultCompare]].
  *
  * @param order
  *   how the order of rows counts
  * @param absTolerance
  *   two numbers, at least one floating, are equal when they differ by at most this
  * @param relTolerance
  *   or by at most this fraction of the larger magnitude
  * @param normalize
  *   applied to every value, nested ones included, before comparison: maps engine-specific values
  *   (a day count, a `java.sql.Date`) to a common form
  * @param maxShownRows
  *   rows listed for each side of a difference
  */
final case class ResultCompareConfig(
    order: ResultOrder = ResultOrder.Bag,
    absTolerance: Double = 1e-12,
    relTolerance: Double = 1e-9,
    normalize: Any => Any = identity,
    maxShownRows: Int = 10
):
  def withBag: ResultCompareConfig          = copy(order = ResultOrder.Bag)
  def withOrdered: ResultCompareConfig      = copy(order = ResultOrder.Ordered)
  def withRowCountOnly: ResultCompareConfig = copy(order = ResultOrder.RowCount)

  /** Rows must come in the order of `key`; rows with equal keys compare as a bag. */
  def withOrderedBy[A](key: A => Any): ResultCompareConfig = copy(order =
    ResultOrder.OrderedBy(row => key(row.asInstanceOf[A]))
  )

  def withAbsTolerance(t: Double): ResultCompareConfig = copy(absTolerance = math.max(0.0, t))
  def withRelTolerance(t: Double): ResultCompareConfig = copy(relTolerance = math.max(0.0, t))

  /** Compare floating numbers exactly. NaN still equals NaN. */
  def noFloatTolerance: ResultCompareConfig = copy(absTolerance = 0.0, relTolerance = 0.0)

  def withNormalize(f: Any => Any): ResultCompareConfig = copy(normalize = f)
  def noNormalize: ResultCompareConfig                  = copy(normalize = identity)
  def withMaxShownRows(n: Int): ResultCompareConfig     = copy(maxShownRows = math.max(0, n))

object ResultCompareConfig:
  val default: ResultCompareConfig = ResultCompareConfig()

/**
  * Thrown when two results differ. Its own class, so fuzz reduction keeps to this kind of failure.
  */
class ResultMismatch(message: String, source: TestSource) extends AssertionFailure(message, source)

/**
  * Compares two results, such as the rows of a query on two engines or a dataset read back, with
  * the rules query results need:
  *
  *   - Rows match in any order by default, with the same multiplicities. See [[ResultOrder]] for
  *     ordered results.
  *   - NULL (`null` or `None`) equals NULL, and `Some(x)` equals `x`. NaN equals NaN, and `-0.0`
  *     equals `0.0`.
  *   - Numbers compare by value across types: `1`, `1L`, and `BigDecimal(1)` are equal. When either
  *     side is a `Float` or `Double`, they are equal within the configured tolerance. A `Float`
  *     compares through its decimal form, so `0.1f` equals `0.1` (except on Scala.js, where a
  *     `Float` is a `Double` at run time).
  *   - Nested values (`Seq`, `Array`, `Map`, case classes and tuples of the same type) compare
  *     element-wise with the same rules.
  *
  * Rows are any values: case classes, `Seq[Any]`, or `Map[String, Any]`.
  *
  * {{{
  *   ResultCompare.assertSame(engineRows, duckdbRows, ResultCompareConfig().withRelTolerance(1e-6))
  * }}}
  */
object ResultCompare:

  /** A description of how `actual` differs from `expected`, or None when they match. */
  def diff(
      actual: Iterable[Any],
      expected: Iterable[Any],
      config: ResultCompareConfig = ResultCompareConfig.default
  ): Option[String] =
    val c = Comparator(config)
    val a = actual.toIndexedSeq
    val e = expected.toIndexedSeq
    config.order match
      case ResultOrder.RowCount =>
        Option.when(a.size != e.size)(s"Expected ${e.size} rows but got ${a.size}")
      case ResultOrder.Bag =>
        c.bagDiff(a, e).map(_.render(a.size, e.size, "", config.maxShownRows))
      case ResultOrder.Ordered =>
        c.orderedDiff(a, e)
      case ResultOrder.OrderedBy(key) =>
        c.keyedDiff(a, e, key)

  /** Whether `actual` matches `expected`. */
  def same(
      actual: Iterable[Any],
      expected: Iterable[Any],
      config: ResultCompareConfig = ResultCompareConfig.default
  ): Boolean = diff(actual, expected, config).isEmpty

  /** Throw a [[ResultMismatch]] describing the difference, if any. */
  def assertSame(
      actual: Iterable[Any],
      expected: Iterable[Any],
      config: ResultCompareConfig = ResultCompareConfig.default
  )(using source: TestSource): Unit = diff(actual, expected, config).foreach(msg =>
    throw ResultMismatch(msg, source)
  )

  /** Whether two values are equal under the rules of `config`. */
  def valueEquals(
      a: Any,
      b: Any,
      config: ResultCompareConfig = ResultCompareConfig.default
  ): Boolean = Comparator(config).equal(a, b)

  // Canonical forms used as hash keys for exact matching
  private case object NullKey
  private case object NaNKey
  private case object PosInfKey
  private case object NegInfKey
  private case object NumKey
  private final case class ProductKey(prefix: String, elems: Vector[Any])

  private final case class BagDiff(missing: Seq[Any], extra: Seq[Any]):
    def render(actualSize: Int, expectedSize: Int, prefix: String, maxShown: Int): String =
      def list(label: String, rows: Seq[Any]): String =
        if rows.isEmpty then
          ""
        else
          val shown = rows.take(maxShown).map(r => s"\n    ${show(r)}").mkString
          val more  =
            if rows.size > maxShown then
              s"\n    ... ${rows.size - maxShown} more"
            else
              ""
          s"\n  ${label} (${rows.size}):${shown}${more}"
      s"${prefix}Results differ: expected ${expectedSize} rows but got ${actualSize}, " +
        s"${missing.size} missing and ${extra.size} unexpected" + list("missing", missing) +
        list("unexpected", extra)

  private def show(v: Any): String =
    v match
      case null =>
        "null"
      case s: String =>
        s"\"${s}\""
      case a: Array[?] =>
        a.map(show).mkString("Array(", ", ", ")")
      case other =>
        other.toString

  private enum Num:
    case Exact(v: BigDecimal)
    case Floating(v: Double)

  private class Comparator(config: ResultCompareConfig):
    private def norm(v: Any): Any = config.normalize(v)

    // Pattern order matters on Scala.js, where any number matches Int when it is whole and
    // `Double` before `Float` (every JS number is both)
    private def number(v: Any): Option[Num] =
      v match
        case i: Int =>
          Some(Num.Exact(BigDecimal(i)))
        case l: Long =>
          Some(Num.Exact(BigDecimal(l)))
        case d: Double =>
          Some(Num.Floating(d))
        case f: Float =>
          // Through its shortest decimal form, so 0.1f equals 0.1
          Some(Num.Floating(java.lang.Double.parseDouble(f.toString)))
        case s: Short =>
          Some(Num.Exact(BigDecimal(s.toInt)))
        case b: Byte =>
          Some(Num.Exact(BigDecimal(b.toInt)))
        case b: BigInt =>
          Some(Num.Exact(BigDecimal(b)))
        case b: BigDecimal =>
          Some(Num.Exact(b))
        case b: java.math.BigDecimal =>
          Some(Num.Exact(BigDecimal(b)))
        case b: java.math.BigInteger =>
          Some(Num.Exact(BigDecimal(BigInt(b))))
        case _ =>
          None

    private def unwrap(v: Any): Any =
      norm(v) match
        case None =>
          null
        case Some(x) =>
          unwrap(x)
        case x =>
          x

    private def elements(v: Any): Option[IndexedSeq[Any]] =
      v match
        case a: Array[?] =>
          Some(a.toIndexedSeq)
        case s: scala.collection.Seq[?] =>
          Some(s.toIndexedSeq)
        case _ =>
          None

    private def numKey(n: Num): Any =
      n match
        case Num.Exact(b) =>
          b.bigDecimal.stripTrailingZeros
        case Num.Floating(d) =>
          if d.isNaN then
            NaNKey
          else if d == Double.PositiveInfinity then
            PosInfKey
          else if d == Double.NegativeInfinity then
            NegInfKey
          else
            BigDecimal(d).bigDecimal.stripTrailingZeros

    /** Canonical form for exact matching; numbers become NumKey when `skeleton` is set. */
    def key(v: Any, skeleton: Boolean): Any =
      val u = unwrap(v)
      if u == null then
        NullKey
      else
        number(u) match
          case Some(n) =>
            if skeleton then
              NumKey
            else
              numKey(n)
          case None =>
            elements(u) match
              case Some(xs) =>
                xs.map(key(_, skeleton)).toVector
              case None =>
                u match
                  case m: scala.collection.Map[?, ?] =>
                    m.iterator.map((k, x) => key(k, skeleton) -> key(x, skeleton)).toMap
                  case s: scala.collection.Set[?] =>
                    s.iterator.map(key(_, skeleton)).toSet
                  case p: Product if p.productArity > 0 =>
                    ProductKey(p.productPrefix, p.productIterator.map(key(_, skeleton)).toVector)
                  case other =>
                    other

    private def numEqual(a: Num, b: Num): Boolean =
      (a, b) match
        case (Num.Exact(x), Num.Exact(y)) =>
          x.compare(y) == 0
        case _ =>
          val x = toDouble(a)
          val y = toDouble(b)
          if x.isNaN || y.isNaN then
            x.isNaN && y.isNaN
          else if x == y then
            true
          else if x.isInfinite || y.isInfinite then
            false
          else
            val d = math.abs(x - y)
            d <= config.absTolerance ||
            d <= config.relTolerance * math.max(math.abs(x), math.abs(y))

    private def toDouble(n: Num): Double =
      n match
        case Num.Exact(b) =>
          b.toDouble
        case Num.Floating(d) =>
          d

    def equal(a: Any, b: Any): Boolean =
      val x = unwrap(a)
      val y = unwrap(b)
      if x == null || y == null then
        x == null && y == null
      else
        (number(x), number(y)) match
          case (Some(m), Some(n)) =>
            numEqual(m, n)
          case (Some(_), None) | (None, Some(_)) =>
            false
          case _ =>
            (elements(x), elements(y)) match
              case (Some(xs), Some(ys)) =>
                xs.size == ys.size && xs.indices.forall(i => equal(xs(i), ys(i)))
              case (Some(_), None) | (None, Some(_)) =>
                false
              case _ =>
                (x, y) match
                  case (m: scala.collection.Map[?, ?], n: scala.collection.Map[?, ?]) =>
                    m.size == n.size &&
                    m.forall { (k, v) =>
                      n.find((k2, _) => equal(k, k2)).exists((_, v2) => equal(v, v2))
                    }
                  case (s: scala.collection.Set[?], t: scala.collection.Set[?]) =>
                    bagDiff(s.toIndexedSeq, t.toIndexedSeq).isEmpty
                  case (p: Product, q: Product)
                      if p.productArity > 0 && p.productArity == q.productArity &&
                        p.productPrefix == q.productPrefix =>
                    p.productIterator.zip(q.productIterator).forall((u, v) => equal(u, v))
                  case _ =>
                    x == y

    end equal

    /**
      * Rows of `actual` and `expected` with no partner. Exact matches pair up by hash first; the
      * rest pair up within groups of the same shape (numbers left out) by a bipartite matching,
      * since tolerance is not transitive.
      */
    def bagDiff(actual: IndexedSeq[Any], expected: IndexedSeq[Any]): Option[BagDiff] =
      val pool = mutable.HashMap.empty[Any, ArrayBuffer[Int]]
      expected
        .indices
        .foreach(i => pool.getOrElseUpdate(key(expected(i), false), ArrayBuffer()) += i)
      val leftA = ArrayBuffer.empty[Int]
      actual
        .indices
        .foreach { i =>
          pool.get(key(actual(i), false)) match
            case Some(buf) if buf.nonEmpty =>
              buf.remove(buf.size - 1)
            case _ =>
              leftA += i
        }
      val leftE = pool.valuesIterator.flatten.toIndexedSeq.sorted
      if leftA.isEmpty && leftE.isEmpty then
        None
      else
        val groupsE = leftE.groupBy(i => key(expected(i), true))
        val missing = ArrayBuffer.empty[Int]
        val extra   = ArrayBuffer.empty[Int]
        val groupsA = leftA.toIndexedSeq.groupBy(i => key(actual(i), true))
        (groupsA.keySet ++ groupsE.keySet).foreach { k =>
          val as         = groupsA.getOrElse(k, IndexedSeq.empty)
          val es         = groupsE.getOrElse(k, IndexedSeq.empty)
          val (unA, unE) = matching(as, es, (i, j) => equal(actual(i), expected(j)))
          extra ++= unA
          missing ++= unE
        }
        if missing.isEmpty && extra.isEmpty then
          None
        else
          Some(BagDiff(missing.sorted.map(expected).toList, extra.sorted.map(actual).toList))

    end bagDiff

    /** Maximum bipartite matching (Kuhn); returns the unmatched elements of each side. */
    private def matching(
        as: IndexedSeq[Int],
        es: IndexedSeq[Int],
        eq: (Int, Int) => Boolean
    ): (Seq[Int], Seq[Int]) =
      if as.isEmpty || es.isEmpty then
        (as, es)
      else
        val adj    = as.map(a => es.indices.filter(j => eq(a, es(j))).toArray)
        val matchE = Array.fill(es.size)(-1)
        def augment(ai: Int, seen: Array[Boolean]): Boolean = adj(ai).exists { j =>
          !seen(j) && {
            seen(j) = true
            (matchE(j) < 0 || augment(matchE(j), seen)) && {
              matchE(j) = ai
              true
            }
          }
        }
        as.indices.foreach(ai => augment(ai, Array.fill(es.size)(false)))
        val matchedA = matchE.filter(_ >= 0).toSet
        (as.indices.filterNot(matchedA.contains).map(as), es.indices.filter(matchE(_) < 0).map(es))

    def orderedDiff(actual: IndexedSeq[Any], expected: IndexedSeq[Any]): Option[String] =
      actual.indices.find(i => i >= expected.size || !equal(actual(i), expected(i))) match
        case Some(i) if i < expected.size =>
          Some(
            s"Results differ at row ${i}: expected <${show(expected(i))}> but got <${show(
                actual(i)
              )}>"
          )
        case Some(i) =>
          Some(
            s"Expected ${expected.size} rows but got ${actual.size}: unexpected row ${i} <${show(
                actual(i)
              )}>"
          )
        case None if actual.size < expected.size =>
          Some(
            s"Expected ${expected.size} rows but got ${actual.size}: missing row ${actual
                .size} <${show(expected(actual.size))}>"
          )
        case None =>
          None

    private def segments(rows: IndexedSeq[Any], k: Any => Any): Vector[(Any, IndexedSeq[Any])] =
      val out = Vector.newBuilder[(Any, IndexedSeq[Any])]
      var i   = 0
      while i < rows.size do
        val ki = k(rows(i))
        var j  = i + 1
        while j < rows.size && equal(k(rows(j)), ki) do
          j += 1
        out += ki -> rows.slice(i, j)
        i = j
      out.result()

    def keyedDiff(
        actual: IndexedSeq[Any],
        expected: IndexedSeq[Any],
        k: Any => Any
    ): Option[String] =
      val sa = segments(actual, k)
      val se = segments(expected, k)
      val n  = math.min(sa.size, se.size)
      (0 until n)
        .iterator
        .map { i =>
          val (ka, ra) = sa(i)
          val (ke, re) = se(i)
          if !equal(ka, ke) then
            Some(
              s"Results differ in sort order: group ${i} has key <${show(ka)}> but expected <${show(
                  ke
                )}>"
            )
          else
            bagDiff(ra, re).map(
              _.render(ra.size, re.size, s"Rows with sort key <${show(ka)}>: ", config.maxShownRows)
            )
        }
        .collectFirst { case Some(msg) =>
          msg
        }
        .orElse {
          if sa.size == se.size then
            None
          else if sa.size > se.size then
            Some(s"Results differ: unexpected rows with sort key <${show(sa(n)._1)}>")
          else
            Some(s"Results differ: missing rows with sort key <${show(se(n)._1)}>")
        }

    end keyedDiff

  end Comparator

end ResultCompare
