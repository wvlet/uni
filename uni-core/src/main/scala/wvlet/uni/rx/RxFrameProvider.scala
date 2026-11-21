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
import scala.collection.mutable

/**
  * The frame clock behind frame operators (`Rx.everyFrame`, `Rx.nextFrame`, `sampleFrame`).
  *
  * In a browser the default runs callbacks in `requestAnimationFrame`, so a UI update that uses
  * `sampleFrame` renders at most once per frame. Elsewhere (JVM, Scala Native, Node.js) the default
  * is a 16 ms timer from [[RxTimeProvider.system]]. Tests put a [[ManualFrameProvider]] in scope:
  *
  * {{{
  *   given frames: ManualFrameProvider = RxFrameProvider.manual()
  *   val out = Seq.newBuilder[Int]
  *   counter.sampleFrame.subscribe(out += _)
  *   counter := 1; counter := 2
  *   frames.advanceFrame() // out receives 2 only
  * }}}
  */
trait RxFrameProvider:
  /**
    * Run `body` once on the next frame. A callback requested while a frame runs goes to the frame
    * after it.
    */
  def requestFrame(body: => Unit): Cancelable

object RxFrameProvider:

  /**
    * The default provider when no other `given RxFrameProvider` is in scope.
    */
  given default: RxFrameProvider = compat.defaultFrameProvider

  /**
    * Frames from a timer: a frame starts every `frameMillis` milliseconds of `time`.
    */
  def fromTime(frameMillis: Long = 16L)(using time: RxTimeProvider): RxFrameProvider =
    TimeFrameProvider(time, frameMillis.max(1L))

  /**
    * A provider whose frames advance only when [[ManualFrameProvider.advanceFrame]] is called.
    */
  def manual(): ManualFrameProvider = ManualFrameProvider()

  private class TimeFrameProvider(time: RxTimeProvider, frameMillis: Long) extends RxFrameProvider:
    private val frameNanos        = TimeUnit.MILLISECONDS.toNanos(frameMillis)
    private val startNanos        = time.currentNanos
    override def toString: String = s"RxFrameProvider.fromTime(${frameMillis}ms)"
    override def requestFrame(body: => Unit): Cancelable =
      val elapsed     = time.currentNanos - startNanos
      val untilNext   = frameNanos - Math.floorMod(elapsed, frameNanos)
      val delayMillis = (untilNext + 999_999L) / 1_000_000L
      time.scheduleOnce(delayMillis.max(1L))(body)

end RxFrameProvider

/**
  * An [[RxFrameProvider]] for tests. Callbacks wait until [[advanceFrame]] runs them on the calling
  * thread.
  */
class ManualFrameProvider extends RxFrameProvider:
  private class Request(body: () => Unit) extends Cancelable:
    @volatile
    var cancelled = false

    def run(): Unit =
      if !cancelled then
        body()

    override def cancel: Unit = cancelled = true

  private var frames: Long                           = 0L
  private var requests: mutable.ArrayBuffer[Request] = mutable.ArrayBuffer.empty

  override def toString: String = s"ManualFrameProvider(frame ${frameCount})"

  /**
    * The number of frames run so far.
    */
  def frameCount: Long = synchronized(frames)

  /**
    * The number of callbacks waiting for the next frame.
    */
  def pendingCallbacks: Int = synchronized(requests.count(!_.cancelled))

  override def requestFrame(body: => Unit): Cancelable = synchronized {
    val r = Request(() => body)
    requests += r
    r
  }

  /**
    * Run `n` frames. Each frame runs the callbacks requested before it started.
    */
  def advanceFrame(n: Int = 1): ManualFrameProvider =
    for _ <- 0 until n do
      val current = synchronized {
        frames += 1
        val r = requests
        requests = mutable.ArrayBuffer.empty
        r
      }
      current.foreach(_.run())
    this

end ManualFrameProvider
