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

import scala.collection.mutable

/**
  * merge, parFlatMap, and parMapN on every platform. Tests return Rx, so they also run on Scala.js.
  */
class RxMergeTest extends UniTest:

  // Collect every value; emits once the stream completes
  private def collect[A](rx: Rx[A]): Rx[Seq[A]] =
    val values = mutable.ArrayBuffer.empty[A]
    rx.tap(v => values.synchronized(values += v))
      .lastOption
      .toRx
      .map(_ => values.synchronized(values.toSeq))

  // The first error of a stream whose sources are all synchronous
  private def errorOf(rx: Rx[Int]): Option[String] =
    var error: Option[String] = None
    RxRunner.run(rx) {
      case OnError(e) if error.isEmpty =>
        error = Some(e.getMessage)
      case _ =>
    }
    error

  test("merge emits the values of every source and completes after all of them") {
    collect(Rx.fromSeq(Seq(1, 2, 3)).merge(Rx.fromSeq(Seq(4, 5)))).map { values =>
      values.sorted shouldBe Seq(1, 2, 3, 4, 5)
    }
  }

  test("RxParallel.merge of several sources") {
    collect(RxParallel.merge(Seq(Rx.single(1), Rx.empty[Int], Rx.fromSeq(Seq(2, 3))))).map {
      values =>
        values.sorted shouldBe Seq(1, 2, 3)
    }
  }

  test("merge reports the first error") {
    errorOf(Rx.fromSeq(Seq(1, 2)).merge(Rx.exception[Int](IllegalStateException("boom")))) shouldBe
      Some("boom")
  }

  test("take stops a merge of sources that never complete") {
    collect(Rx.variable(1).merge(Rx.variable(2)).take(2)).map { values =>
      values.sorted shouldBe Seq(1, 2)
    }
  }

  test("merge completes after the downstream recovers from an error") {
    collect(
      Rx.fromSeq(Seq(1, 2))
        .merge(Rx.exception[Int](IllegalStateException("boom")))
        .recover { case _: IllegalStateException =>
          0
        }
    ).map { values =>
      values.sorted shouldBe Seq(0, 1, 2)
    }
  }

  test("merge does not subscribe sources after it has stopped") {
    var subscribed = 0
    collect(
      Rx.single(1)
        .merge(
          Rx.single {
            subscribed += 1;
            2
          }
        )
        .take(1)
    ).map { values =>
      values shouldBe Seq(1)
      subscribed shouldBe 0
    }
  }

  test("merge keeps running after a transient error in continuous mode") {
    val a      = Rx.variable(1)
    val b      = Rx.variable(10)
    val events = mutable.ArrayBuffer.empty[RxEvent]
    val c      =
      RxRunner.runContinuously(
        a.merge(b)
          .map(x =>
            if x == 2 then
              throw IllegalStateException("once")
            else
              x
          )
      )(ev => events += ev)
    a := 2
    a := 3
    c.cancel
    events.collect { case OnNext(v) =>
      v
    } shouldBe Seq(1, 10, 3)
    events.count(_.isInstanceOf[OnError]) shouldBe 1
  }

  test("parFlatMap emits every nested value and completes") {
    collect(Rx.fromSeq(1 to 5).parFlatMap(2)(i => Rx.fromSeq(Seq(i, i * 10)))).map { values =>
      values.sorted shouldBe Seq(1, 2, 3, 4, 5, 10, 20, 30, 40, 50)
    }
  }

  test("parFlatMap of an empty stream completes") {
    Rx.empty[Int].parFlatMap(2)(i => Rx.single(i)).lastOption.toRx.map(_ shouldBe None)
  }

  test("parFlatMap reports an error from the function or a nested stream") {
    val fromF =
      Rx.fromSeq(1 to 3)
        .parFlatMap(2)(i =>
          if i == 2 then
            throw IllegalStateException("f")
          else
            Rx.single(i)
        )
    val fromNested =
      Rx.fromSeq(1 to 3)
        .parFlatMap(2)(i =>
          if i == 2 then
            Rx.exception[Int](IllegalStateException("nested"))
          else
            Rx.single(i)
        )
    errorOf(fromF) shouldBe Some("f")
    errorOf(fromNested) shouldBe Some("nested")
  }

  test("parFlatMap continues after the downstream recovers from a nested error") {
    collect(
      Rx.fromSeq(1 to 3)
        .parFlatMap(2)(i =>
          if i == 2 then
            Rx.exception[Int](IllegalStateException("x"))
          else
            Rx.single(i)
        )
        .recover { case _: IllegalStateException =>
          0
        }
    ).map { values =>
      values.sorted shouldBe Seq(0, 1, 3)
    }
  }

  test("parFlatMap rejects a non-positive parallelism") {
    intercept[IllegalArgumentException] {
      Rx.single(1).parFlatMap(0)(Rx.single(_))
    }
  }

  test("parMapN maps every value") {
    collect(Rx.fromSeq(1 to 10).parMapN(3)(_ * 2)).map { values =>
      values.sorted shouldBe (1 to 10).map(_ * 2)
    }
  }

  test("Rx.parallel is RxParallel") {
    (Rx.parallel eq RxParallel) shouldBe true
  }

end RxMergeTest
