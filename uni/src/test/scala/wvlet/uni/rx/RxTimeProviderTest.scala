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

import java.util.concurrent.TimeUnit
import wvlet.uni.test.UniTest

class RxTimeProviderTest extends UniTest:

  test("ManualTimeProvider runs due tasks in time order") {
    val time = RxTimeProvider.manual()
    val log  = Seq.newBuilder[String]
    time.scheduleOnce(30)(log += "c")
    time.scheduleOnce(10)(log += "a")
    time.scheduleOnce(20)(log += "b")
    time.advance(15)
    log.result() shouldBe Seq("a")
    time.advance(15)
    log.result() shouldBe Seq("a", "b", "c")
    time.pendingTasks shouldBe 0
    time.currentNanos shouldBe TimeUnit.MILLISECONDS.toNanos(30)
  }

  test("ManualTimeProvider runs a task scheduled while advancing") {
    val time = RxTimeProvider.manual()
    val log  = Seq.newBuilder[Long]
    time.scheduleOnce(10) {
      log += time.currentNanos
      time.scheduleOnce(5)(log += time.currentNanos)
    }
    time.advance(20)
    log.result() shouldBe Seq(10_000_000L, 15_000_000L)
  }

  test("cancelled tasks do not run") {
    val time = RxTimeProvider.manual()
    var ran  = 0
    val c    = time.schedulePeriodically(10, 10)(ran += 1)
    time.advance(25)
    ran shouldBe 2
    c.cancel
    time.advance(100)
    ran shouldBe 2
    time.pendingTasks shouldBe 0
  }

  test("Rx.interval with a given time provider") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val ticks                      = Seq.newBuilder[Long]
    Rx.interval(100, TimeUnit.MILLISECONDS).take(3).run(ticks += _)
    time.advance(99)
    ticks.result() shouldBe Seq.empty
    time.advance(1)
    ticks.result() shouldBe Seq(100L)
    time.advance(1000)
    ticks.result() shouldBe Seq(100L, 100L, 100L)
    // take(3) stops the timer
    time.pendingTasks shouldBe 0
  }

  test("Rx.timer with a given time provider") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val events                     = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.timer(50, TimeUnit.MILLISECONDS))(events += _)
    time.advance(49)
    events.result() shouldBe Seq.empty
    time.advance(1)
    events.result() shouldBe Seq(OnNext(0L), OnCompletion)
  }

  test("throttleFirst drops values within the window") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val v                          = Rx.variable(0)
    val out                        = Seq.newBuilder[Int]
    val c                          = v.throttleFirst(100).subscribe(out += _)
    v := 1
    time.advance(50)
    v := 2
    time.advance(50)
    v := 3
    v := 4
    c.cancel
    out.result() shouldBe Seq(0, 3)
  }

  test("throttleLast emits the latest value per window") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val v                          = Rx.variable(0)
    val out                        = Seq.newBuilder[Int]
    val c                          = v.throttleLast(100).subscribe(out += _)
    v := 1
    v := 2
    time.advance(100)
    v := 3
    time.advance(100)
    time.advance(100)
    c.cancel
    out.result() shouldBe Seq(2, 3)
    time.pendingTasks shouldBe 0
  }

  test("timeout fails when the first event is late") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val v                          = Rx.variable(Option.empty[Int])
    val events                     = Seq.newBuilder[RxEvent]
    RxRunner.run(v.filter(_.isDefined).timeout(100))(events += _)
    time.advance(100)
    events.result() shouldMatch { case Seq(OnError(e: Rx.TimeoutException)) =>
    }
  }

  test("timeout passes an event that arrives in time") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    val events                     = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.timer(50, TimeUnit.MILLISECONDS).timeout(100))(events += _)
    time.advance(200)
    events.result() shouldBe Seq(OnNext(0L), OnCompletion)
  }

end RxTimeProviderTest
