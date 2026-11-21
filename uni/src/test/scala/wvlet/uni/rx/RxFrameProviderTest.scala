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

class RxFrameProviderTest extends UniTest:

  test("ManualFrameProvider runs a callback requested during a frame on the next frame") {
    val frames = RxFrameProvider.manual()
    val log    = Seq.newBuilder[Long]
    frames.requestFrame {
      log += frames.frameCount
      frames.requestFrame(log += frames.frameCount)
    }
    frames.advanceFrame()
    log.result() shouldBe Seq(1L)
    frames.advanceFrame()
    log.result() shouldBe Seq(1L, 2L)
    frames.pendingCallbacks shouldBe 0
  }

  test("Rx.everyFrame emits frame numbers until cancelled") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val out                           = Seq.newBuilder[Long]
    val c                             = Rx.everyFrame.subscribe(out += _)
    frames.advanceFrame(3)
    c.cancel
    frames.advanceFrame(3)
    out.result() shouldBe Seq(0L, 1L, 2L)
    frames.pendingCallbacks shouldBe 0
  }

  test("Rx.everyFrame stops after take") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val events                        = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.everyFrame.take(2))(events += _)
    frames.advanceFrame(5)
    events.result() shouldBe Seq(OnNext(0L), OnNext(1L), OnCompletion)
    frames.pendingCallbacks shouldBe 0
  }

  test("Rx.nextFrame emits once, then completes") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val events                        = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.nextFrame)(events += _)
    events.result() shouldBe Seq.empty
    frames.advanceFrame()
    events.result() shouldBe Seq(OnNext(()), OnCompletion)
  }

  test("sampleFrame emits the last value of each frame") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val v                             = Rx.variable(0)
    val out                           = Seq.newBuilder[Int]
    val c                             = v.sampleFrame.subscribe(out += _)
    v := 1
    v := 2
    frames.advanceFrame()
    out.result() shouldBe Seq(2)
    // No update, no emission
    frames.advanceFrame()
    out.result() shouldBe Seq(2)
    v := 3
    frames.advanceFrame()
    out.result() shouldBe Seq(2, 3)
    v := 4
    c.cancel
    frames.advanceFrame()
    out.result() shouldBe Seq(2, 3)
    frames.pendingCallbacks shouldBe 0
  }

  test("sampleFrame flushes the held value on completion") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val events                        = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.sequence(1, 2, 3).sampleFrame)(events += _)
    events.result() shouldBe Seq(OnNext(3), OnCompletion)
    frames.pendingCallbacks shouldBe 0
  }

  test("sampleFrame flushes the held value before an error") {
    given frames: ManualFrameProvider = RxFrameProvider.manual()
    val e                             = IllegalStateException("boom")
    val events                        = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.concat(Rx.single(1), Rx.exception[Int](e)).sampleFrame)(events += _)
    events.result() shouldBe Seq(OnNext(1), OnError(e))
  }

  test("frames from a time provider") {
    given time: ManualTimeProvider = RxTimeProvider.manual()
    given frames: RxFrameProvider  = RxFrameProvider.fromTime(10)
    val out                        = Seq.newBuilder[Long]
    val c                          = Rx.everyFrame.subscribe(out += _)
    time.advance(35)
    c.cancel
    out.result() shouldBe Seq(0L, 1L, 2L)
  }

end RxFrameProviderTest
