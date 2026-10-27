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

import java.util.concurrent.atomic.AtomicInteger

/**
  * Parallelism bounds of parFlatMap and parMapN, which need threads to observe.
  */
class RxParallelTest extends UniTest:

  // Runs `body` while counting how many calls overlap
  private class Overlap:
    private val active          = AtomicInteger(0)
    val max                     = AtomicInteger(0)
    def apply[A](body: => A): A =
      val n = active.incrementAndGet()
      max.accumulateAndGet(n, math.max)
      try
        Thread.sleep(20)
        body
      finally
        active.decrementAndGet()

  test("parFlatMap subscribes at most `parallelism` nested streams at a time") {
    val overlap = Overlap()
    // lastOption emits after completion, so await waits for every nested stream
    val all =
      Rx.fromSeq(1 to 12)
        .parFlatMap(3)(i => Rx.single(overlap(i)).evalOnBlocking)
        .lastOption
        .toRx
        .await
    all.isDefined shouldBe true
    (overlap.max.get <= 3) shouldBe true
    (overlap.max.get >= 2) shouldBe true
  }

  test("parMapN runs the function in parallel") {
    val overlap = Overlap()
    val seen    = java.util.concurrent.ConcurrentLinkedQueue[Int]()
    Rx.fromSeq(1 to 8).parMapN(4)(i => overlap(i * 2)).tap(seen.add).lastOption.toRx.await
    seen.size shouldBe 8
    (overlap.max.get <= 4) shouldBe true
    // The default scheduler has one thread per CPU
    if RxScheduler.default.parallelism >= 2 then
      (overlap.max.get >= 2) shouldBe true
  }

  test("parFlatMap drains a long queue of synchronous nested streams without deep recursion") {
    // The first nested stream is asynchronous, so the remaining values queue up behind it
    Rx.fromSeq(1 to 100000)
      .parFlatMap(1)(i =>
        if i == 1 then
          Rx.single(i).evalOnBlocking
        else
          Rx.single(i)
      )
      .lastOption
      .toRx
      .await shouldBe Some(100000)
  }

  test("Rx.parallel runs RxParallel operations") {
    Rx.parallel.parTraverseN(2)(1 to 4)(i => Rx.single(i * 10)).await shouldBe Seq(10, 20, 30, 40)
  }

end RxParallelTest
