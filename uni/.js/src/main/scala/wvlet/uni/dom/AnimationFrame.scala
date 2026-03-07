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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.Cancelable

/**
  * requestAnimationFrame integration for smooth animations.
  *
  * Usage:
  * {{{
  *   // Game loop
  *   val gameLoop = AnimationFrame.loop { deltaTime =>
  *     updateGameState(deltaTime)
  *     render()
  *   }
  *   // Later: gameLoop.cancel
  *
  *   // One-time DOM measurement after render
  *   AnimationFrame.once {
  *     val height = element.getBoundingClientRect().height
  *     println(s"Element height: $height")
  *   }
  *
  *   // Fixed timestep updates (60 FPS)
  *   val fixedUpdate = AnimationFrame.fixedStep(1000.0 / 60.0) { () =>
  *     physics.step()
  *   }
  * }}}
  */
object AnimationFrame:

  /**
    * An animation loop that runs continuously using requestAnimationFrame.
    *
    * @param callback
    *   Function that receives the delta time in milliseconds since the last frame
    */
  private class AnimationLoop(callback: Double => Unit) extends Cancelable:
    private var running        = true
    private var lastTime       = 0.0
    private var rafId          = 0
    private var firstFrame     = true
    private var cancelDelegate = Cancelable.empty

    private def loop(time: Double): Unit =
      if running then
        val dt =
          if firstFrame then
            firstFrame = false
            lastTime = time
            0.0
          else
            time - lastTime
        lastTime = time
        callback(dt)
        rafId = dom.window.requestAnimationFrame(t => loop(t))

    rafId = dom.window.requestAnimationFrame(t => loop(t))

    override def cancel: Unit =
      running = false
      dom.window.cancelAnimationFrame(rafId)
      cancelDelegate.cancel

    def withCleanup(cleanup: => Unit): AnimationLoop =
      cancelDelegate = Cancelable(() => cleanup)
      this

  end AnimationLoop

  /**
    * Create an animation loop that runs continuously.
    *
    * @param callback
    *   Function called on each frame with delta time in milliseconds
    * @return
    *   Cancelable to stop the animation loop
    */
  def loop(callback: Double => Unit): Cancelable = AnimationLoop(callback)

  /**
    * Schedule a callback to run on the next animation frame.
    *
    * Useful for:
    *   - Reading layout after DOM updates
    *   - Batching DOM writes
    *   - Deferring work to avoid layout thrashing
    *
    * @param callback
    *   Function to execute on next frame
    * @return
    *   Cancelable to cancel the scheduled callback
    */
  def once(callback: => Unit): Cancelable =
    val rafId = dom.window.requestAnimationFrame(_ => callback)
    Cancelable(() => dom.window.cancelAnimationFrame(rafId))

  /**
    * Create an animation loop with fixed timestep updates. Useful for physics simulations that need
    * consistent update intervals.
    *
    * @param stepMs
    *   Fixed timestep in milliseconds (e.g., 1000.0/60.0 for 60 FPS)
    * @param callback
    *   Function called for each fixed timestep
    * @return
    *   Cancelable to stop the loop
    */
  def fixedStep(stepMs: Double)(callback: () => Unit): Cancelable =
    var accumulator = 0.0
    loop { dt =>
      accumulator += dt
      while accumulator >= stepMs do
        callback()
        accumulator -= stepMs
    }

  /**
    * Create an animation loop that tracks elapsed time.
    *
    * @param callback
    *   Function called with (deltaTime, totalElapsedTime) in milliseconds
    * @return
    *   Cancelable to stop the loop
    */
  def withElapsed(callback: (Double, Double) => Unit): Cancelable =
    var elapsed = 0.0
    loop { dt =>
      elapsed += dt
      callback(dt, elapsed)
    }

  /**
    * Create an animation loop that automatically stops after a duration.
    *
    * @param durationMs
    *   Duration in milliseconds before the loop stops
    * @param callback
    *   Function called with (deltaTime, progress) where progress is 0.0 to 1.0
    * @param onComplete
    *   Optional callback when animation completes
    * @return
    *   Cancelable to stop the loop early
    */
  def timed(
      durationMs: Double,
      callback: (Double, Double) => Unit,
      onComplete: () => Unit = () => ()
  ): Cancelable =
    var elapsed                  = 0.0
    lazy val loopRef: Cancelable = loop { dt =>
      elapsed += dt
      val progress = Math.min(elapsed / durationMs, 1.0)
      callback(dt, progress)
      if progress >= 1.0 then
        loopRef.cancel
        onComplete()
    }
    loopRef

end AnimationFrame
