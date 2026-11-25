package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class SelectionInner(a: Option[Int]) derives Weaver

case class SelectionRow(
    id: Long,
    i: Option[Int],
    l: Option[Long],
    d: Option[Double],
    s: Option[String],
    inner: Option[SelectionInner]
) derives Weaver

/**
  * Row filters evaluated through selection vectors must keep exactly the rows three-valued logic
  * finds true, for random combinations of conjuncts and disjuncts over nullable columns, in both
  * filter-first and decode-everything reads.
  */
class SelectionFilterTest extends UniTest:
  FileSystemInit.init()

  private val rnd = scala.util.Random(42)

  private val rows = (0 until 3000).map { n =>
    SelectionRow(
      id = n.toLong,
      i = Option.when(n % 7 != 0)(rnd.nextInt(100) - 50),
      l = Option.when(n % 11 != 0)(rnd.nextLong(1000) - 500),
      d =
        Option.when(n % 5 != 0)(
          if n % 13 == 0 then
            Double.NaN
          else
            rnd.nextInt(40).toDouble / 4
        ),
      s = Option.when(n % 3 != 0)(s"v${rnd.nextInt(12)}"),
      inner =
        if n % 9 == 0 then
          None
        else
          Some(SelectionInner(Option.when(n % 4 != 0)(rnd.nextInt(20))))
    )
  }

  private val files = Seq(true, false).map { dict =>
    Parquet.writeBytes(
      rows,
      ParquetWriteOptions
        .default
        .withRowGroupSize(32 * 1024)
        .withPageSize(1024)
        .withDictionaryEnabled(dict)
    )
  }

  private val ops = IndexedSeq(Op.Eq, Op.Lt, Op.LtEq, Op.Gt, Op.GtEq)

  private def cmp(op: Op, c: Int): Boolean =
    op match
      case Op.Eq =>
        c == 0
      case Op.Lt =>
        c < 0
      case Op.LtEq =>
        c <= 0
      case Op.Gt =>
        c > 0
      case Op.GtEq =>
        c >= 0

  /** A filter and its three-valued result for a row: None when unknown */
  private type Gen = (ParquetFilter, SelectionRow => Option[Boolean])

  private def leaf(): Gen =
    val op = ops(rnd.nextInt(ops.size))
    rnd.nextInt(9) match
      case 0 =>
        val k = rnd.nextInt(100) - 50
        (Compare("i", op, k), r => r.i.map(v => cmp(op, v.compare(k))))
      case 1 =>
        val k = rnd.nextLong(1000) - 500
        (Compare("l", op, k), r => r.l.map(v => cmp(op, v.compare(k))))
      case 2 =>
        val k = rnd.nextInt(40).toDouble / 4
        (
          Compare("d", op, k),
          r => r.d.map(v => !v.isNaN && cmp(op, java.lang.Double.compare(v, k).sign))
        )
      case 3 =>
        val k = s"v${rnd.nextInt(12)}"
        (Compare("s", op, k), r => r.s.map(v => cmp(op, v.compareTo(k))))
      case 4 =>
        val k = rnd.nextInt(20)
        (Compare("inner.a", op, k), r => r.inner.flatMap(_.a).map(v => cmp(op, v.compare(k))))
      case 5 =>
        (IsNull("s"), r => Some(r.s.isEmpty))
      case 6 =>
        (IsNotNull("inner.a"), r => Some(r.inner.flatMap(_.a).isDefined))
      case 7 =>
        val set = Seq.fill(5)(rnd.nextInt(100).toLong - 50)
        (In("i", IntegerSet.of(set)), r => r.i.map(v => set.contains(v.toLong)))
      case _ =>
        (IsNull("inner"), r => Some(r.inner.isEmpty))

  end leaf

  private def gen(depth: Int): Gen =
    if depth == 0 || rnd.nextInt(3) == 0 then
      leaf()
    else
      val (fa, ea) = gen(depth - 1)
      val (fb, eb) = gen(depth - 1)
      if rnd.nextBoolean() then
        (
          And(fa, fb),
          r =>
            (ea(r), eb(r)) match
              case (Some(false), _) | (_, Some(false)) =>
                Some(false)
              case (Some(true), Some(true)) =>
                Some(true)
              case _ =>
                None
        )
      else
        (
          Or(fa, fb),
          r =>
            (ea(r), eb(r)) match
              case (Some(true), _) | (_, Some(true)) =>
                Some(true)
              case (Some(false), Some(false)) =>
                Some(false)
              case _ =>
                None
        )

  test("keep exactly the rows three-valued logic finds true") {
    (0 until 150).foreach { round =>
      val (filter, expect) = gen(4)
      val expected         = rows.filter(r => expect(r).contains(true)).map(_.id)
      for
        bytes <- files
        late  <- Seq(true, false)
        batch <- Seq(None, Some(257))
      do
        val f = Parquet.open(bytes)
        try
          f.lateMaterialization = late
          val opts =
            batch.fold(ParquetReadOptions.default)(ParquetReadOptions.default.withBatchSize(_))
          val ids =
            f.readBatches(opts.withColumns("id").withFilter(filter))
              .flatMap(b => Iterator.range(0, b.numRows).map(b.columns.head.getValue(_)))
              .toSeq
          s"round ${round} late=${late} ${filter}: ${ids.size}" shouldBe
            s"round ${round} late=${late} ${filter}: ${expected.size}"
          ids shouldBe expected
        finally
          f.close()
      end for
    }
  }

end SelectionFilterTest
