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
import wvlet.uni.test.empty
import wvlet.uni.test.defined

/**
  */
class BackPropagationTest extends UniTest:
  val ex = new IllegalArgumentException("dummy")

  private def eval[A](rx: Rx[A]): Seq[RxEvent] =
    val b = Seq.newBuilder[RxEvent]
    RxRunner.run(rx)(b += _)
    val events = b.result()
    debug(events)
    events

  test("map") {
    val rx = Rx
      .sequence(1, 2, 3)
      .map {
        case 2 =>
          throw ex
        case other =>
          other
      }

    eval(rx) shouldBe Seq(OnNext(1), OnCompletion.failure(ex))
  }

  test("flatMap") {
    val rx = Rx
      .sequence(1, 2, 3)
      .flatMap {
        case 2 =>
          Rx.single(2).concat(Rx.exception(ex))
        case other =>
          Rx.sequence(other, other)
      }

    eval(rx) shouldBe Seq(OnNext(1), OnNext(1), OnNext(2), OnCompletion.failure(ex))
  }

  test("concat") {
    val rx = Rx.single(2).concat(Rx.exception(ex))
    eval(rx) shouldBe Seq(OnNext(2), OnCompletion.failure(ex))
  }

end BackPropagationTest
