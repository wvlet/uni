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
package wvlet.uni.rx

import wvlet.uni.test.UniTest

import scala.collection.mutable.ArrayBuffer

class RxScanSplitTest extends UniTest:

  case class Item(id: Int, text: String)

  // Collect the values a synchronous Rx emits (Rx.toSeq isn't available on Scala.js)
  private def values[A](rx: Rx[A]): Seq[A] =
    val b = ArrayBuffer.empty[A]
    RxRunner
      .run(rx) {
        case OnNext(v) =>
          b += v.asInstanceOf[A]
        case _ =>
      }
      .cancel
    b.toSeq

  test("scan emits each accumulated value, without init") {
    values(Rx.fromSeq(Seq(1, 2, 3)).scan(0)(_ + _)) shouldBe Seq(1, 3, 6)
  }

  test("scan with startWith emits init first") {
    values(Rx.fromSeq(Seq(1, 2)).scan(10)(_ + _).startWith(10)) shouldBe Seq(10, 11, 13)
  }

  test("scan keeps an accumulator per subscription") {
    val v      = Rx.variable(1)
    val sums   = v.scan(0)(_ + _)
    val first  = ArrayBuffer.empty[Int]
    val second = ArrayBuffer.empty[Int]
    val c1     = sums.subscribe(first += _)
    v := 2
    val c2 = sums.subscribe(second += _)
    v := 3
    c1.cancel
    c2.cancel
    first.toSeq shouldBe Seq(1, 3, 6)
    second.toSeq shouldBe Seq(2, 5)
  }

  test("scan turns an exception in f into an error") {
    val events = ArrayBuffer.empty[RxEvent]
    RxRunner.run(Rx.single(1).scan(0)((_, _) => throw IllegalStateException("boom")))(events += _)
    events.head shouldMatch { case OnError(_: IllegalStateException) =>
    }
  }

  test("split keeps one Rx per key and updates it in place") {
    val items  = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val lists  = ArrayBuffer.empty[Seq[(Int, Rx[Item])]]
    val c      = items.split(_.id).subscribe(lists += _)
    val row1   = lists.last(0)._2
    val values = ArrayBuffer.empty[Item]
    val rc     = row1.subscribe(values += _)

    // A changed value under the same keys reaches only the row
    items := Seq(Item(1, "a2"), Item(2, "b"))
    lists.size shouldBe 1
    values.toSeq shouldBe Seq(Item(1, "a"), Item(1, "a2"))

    // An equal value doesn't emit
    items := Seq(Item(1, "a2"), Item(2, "b"))
    values.size shouldBe 2

    rc.cancel
    c.cancel
  }

  test("split emits the list when keys are added, removed, or reordered") {
    val items = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val lists = ArrayBuffer.empty[Seq[(Int, Rx[Item])]]
    val c     = items.split(_.id).subscribe(lists += _)
    val row1  = lists.last(0)._2

    items := Seq(Item(2, "b"), Item(1, "a"))
    lists.last.map(_._1) shouldBe Seq(2, 1)
    // The same instance follows the key, so the pairs stay equal
    (lists.last(1)._2 eq row1) shouldBe true
    lists.last(1) shouldBe lists.head(0)

    items := Seq(Item(2, "b"), Item(1, "a"), Item(3, "c"))
    lists.last.map(_._1) shouldBe Seq(2, 1, 3)
    lists.size shouldBe 3
    c.cancel
  }

  test("split completes the Rx of a key that leaves, and a returning key gets a new Rx") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val lists     = ArrayBuffer.empty[Seq[(Int, Rx[Item])]]
    val c         = items.split(_.id).subscribe(lists += _)
    val row2      = lists.last(1)._2
    var completed = false
    val rc        =
      RxRunner.runContinuously(row2) {
        case OnCompletion =>
          completed = true
        case _ =>
      }

    items := Seq(Item(1, "a"))
    completed shouldBe true
    lists.last.map(_._1) shouldBe Seq(1)

    items := Seq(Item(1, "a"), Item(2, "b"))
    (lists.last(1)._2 eq row2) shouldBe false
    rc.cancel
    c.cancel
  }

  test("split keeps the first item of a repeated key") {
    val lists = ArrayBuffer.empty[Seq[(Int, Rx[Item])]]
    val c     = Rx
      .variable(Seq(Item(1, "a"), Item(1, "dup"), Item(2, "b")))
      .split(_.id)
      .subscribe(lists += _)
    lists.last.map(_._1) shouldBe Seq(1, 2)
    values(lists.last(0)._2).head shouldBe Item(1, "a")
    c.cancel
  }

  test("split completes every row on cancel") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val lists     = ArrayBuffer.empty[Seq[(Int, Rx[Item])]]
    val c         = items.split(_.id).subscribe(lists += _)
    var completed = 0
    val rcs       = lists
      .last
      .map { (_, row) =>
        RxRunner.runContinuously(row) {
          case OnCompletion =>
            completed += 1
          case _ =>
        }
      }
    c.cancel
    completed shouldBe 2
    rcs.foreach(_.cancel)
  }

  test("split completes every row when the upstream completes") {
    val events    = ArrayBuffer.empty[RxEvent]
    var completed = 0
    RxRunner.run(Rx.single(Seq(Item(1, "a"), Item(2, "b"))).split(_.id)) {
      case ev @ OnNext(v) =>
        v.asInstanceOf[Seq[(Int, Rx[Item])]]
          .foreach { (_, row) =>
            RxRunner.runContinuously(row) {
              case OnCompletion =>
                completed += 1
              case _ =>
            }
          }
        events += ev
      case other =>
        events += other
    }
    events.last shouldBe OnCompletion
    completed shouldBe 2
  }

  test("split keeps a key table per subscription") {
    val items = Rx.variable(Seq(Item(1, "a")))
    val split = items.split(_.id)
    var a     = Seq.empty[(Int, Rx[Item])]
    var b     = Seq.empty[(Int, Rx[Item])]
    val c1    = split.subscribe(a = _)
    val c2    = split.subscribe(b = _)
    (a(0)._2 eq b(0)._2) shouldBe false

    // Cancelling one subscription leaves the other's rows alive
    c1.cancel
    val values = ArrayBuffer.empty[Item]
    val rc     = b(0)._2.subscribe(values += _)
    items := Seq(Item(1, "a2"))
    values.toSeq shouldBe Seq(Item(1, "a"), Item(1, "a2"))
    rc.cancel
    c2.cancel
  }

end RxScanSplitTest
