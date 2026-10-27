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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
  * A nested stream that fails on another thread while the input completes: the completion must not
  * overtake the error (or the value recovered from it).
  */
class RxFlatMapRaceTest extends UniTest:

  private def raceInputCompletion(build: (Rx[Int], Rx[Int]) => Rx[String]): Seq[String] =
    // Emits 1, then completes once the gate opens
    val gate      = RxDeferred[Unit]()
    val input     = Rx.single(1).concat(gate.get.filter(_ => false).map(_ => 0))
    val nested    = RxDeferred[Int]()
    val inHandler = CountDownLatch(1)
    val release   = CountDownLatch(1)
    val events    = ConcurrentLinkedQueue[String]()
    val c         =
      RxRunner.run(build(input, nested.get)) {
        case OnNext(v) =>
          inHandler.countDown()
          release.await(5, TimeUnit.SECONDS)
          events.add(s"next ${v}")
        case OnError(e) =>
          events.add(s"error ${e.getMessage}")
        case OnCompletion =>
          events.add("completion")
      }
    val failer = Thread(() =>
      nested.completeWithError(IllegalStateException("bad")).run();
      ()
    )
    failer.start()
    inHandler.await(5, TimeUnit.SECONDS) shouldBe true
    // The input completes while the recovered value is still being delivered
    gate.complete(()).run()
    Thread.sleep(100)
    release.countDown()
    failer.join(5000)
    Thread.sleep(100)
    c.cancel
    events.asScala.toSeq

  end raceInputCompletion

  test("flatMap reports completion after a recovered nested error") {
    raceInputCompletion { (input, nested) =>
      input
        .flatMap(_ => nested)
        .map(_.toString)
        .recover { case e: IllegalStateException =>
          e.getMessage
        }
    } shouldBe Seq("next bad", "completion")
  }

  test("transformRx reports completion after a recovered nested error") {
    raceInputCompletion { (input, nested) =>
      input
        .transformRx {
          case scala.util.Success(_) =>
            nested
          case scala.util.Failure(e) =>
            Rx.exception[Int](e)
        }
        .map(_.toString)
        .recover { case e: IllegalStateException =>
          e.getMessage
        }
    } shouldBe Seq("next bad", "completion")
  }

end RxFlatMapRaceTest
