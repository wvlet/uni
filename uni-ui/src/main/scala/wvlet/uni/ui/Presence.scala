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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js

/**
  * Keeps an overlay in the document while it plays its exit animation. `Presence(open)(view)`
  * renders `view` when `open` turns true, and keeps it after `open` turns false until the view
  * calls [[Presence.Exit.done]], which [[OverlaySurface]] does once [[ExitAnimation]] ends.
  *
  * Opening again during an exit drops the exiting view and renders a fresh one, so its entry
  * animation plays from the start.
  */
private[ui] object Presence:

  /**
    * What a view needs to close itself: `open` turns false to start the exit, and `done` removes
    * the view once the exit ends.
    */
  class Exit(val open: Rx[Boolean], val done: () => Unit)

  def apply(open: Rx[Boolean])(view: Exit => DomNode): RxElement =
    // The generation of the last view that finished its exit
    val exited             = Rx.variable(0)
    var generation         = 0
    var wasOpen            = false
    var shown: Option[Int] = None
    Embedded(
      open
        .join(exited)
        .map { (o, done) =>
          val before = shown
          if o && !wasOpen then
            generation += 1
            shown = Some(generation)
          else if !o && shown.contains(done) then
            shown = None
          wasOpen = o
          // Render only when the view changes: open turning false keeps the exiting view in place
          if shown != before then
            Some(shown)
          else
            None
        }
        .filter(_.isDefined)
        .map {
          case Some(Some(g)) =>
            view(Exit(open, () => exited := g))
          case _ =>
            DomNode.empty
        }
    )

  end apply

end Presence

/**
  * Plays the exit animation of a closing overlay: marks it `data-state="closed"` and `inert`, then
  * calls `done` when the CSS animations that `closed` starts have ended. Without an animation
  * (`prefers-reduced-motion`, `animation: none`, or a test DOM without styles) `done` runs at once.
  */
private[ui] object ExitAnimation:
  // Waits this long past the computed duration in case `animationend` never arrives
  private val marginMs = 50.0

  def run(root: dom.HTMLElement)(done: () => Unit): Cancelable =
    root.setAttribute("inert", "")
    // The root and its direct children with a state, e.g. a dialog's backdrop and panel
    val targets =
      root +:
        root
          .children
          .toSeq
          .collect {
            case c: dom.HTMLElement if c.hasAttribute("data-state") =>
              c
          }
    targets.foreach(_.setAttribute("data-state", "closed"))
    // Reading the computed style after the attribute change picks up the exit animation
    val durations = targets.map(t => t -> durationMs(t)).filter(_._2 > 0)
    if durations.isEmpty then
      done()
      Cancelable.empty
    else
      var pending                                   = durations.map(_._1)
      var finished                                  = false
      var timer                                     = 0
      lazy val onEnd: js.Function1[dom.Event, Unit] =
        e =>
          pending = pending.filterNot(_ eq e.target)
          if pending.isEmpty then
            finish()
      def stop(): Unit =
        finished = true
        dom.window.clearTimeout(timer)
        root.removeEventListener("animationend", onEnd)
      def finish(): Unit =
        if !finished then
          stop()
          done()
      root.addEventListener("animationend", onEnd)
      timer = dom.window.setTimeout(() => finish(), durations.map(_._2).max + marginMs)
      Cancelable(() => stop())

  end run

  /** The longest animation on `el` (duration plus delay) in milliseconds; 0 when there is none. */
  private[ui] def durationMs(el: dom.Element): Double =
    val style = dom.window.getComputedStyle(el)
    if style.animationName == null || style.animationName.isEmpty || style.animationName == "none"
    then
      0
    else
      def times(s: String): Seq[Double] = Option(s).toSeq.flatMap(_.split(",")).map(parseTime)
      val durations                     = times(style.animationDuration)
      val delays                        = times(style.animationDelay)
      durations.zipAll(delays, 0.0, 0.0).map(_ + _).maxOption.getOrElse(0.0)

  private def parseTime(s: String): Double =
    val t  = s.trim
    val ms =
      if t.endsWith("ms") then
        t.dropRight(2).toDoubleOption
      else if t.endsWith("s") then
        t.dropRight(1).toDoubleOption.map(_ * 1000)
      else
        None
    ms.getOrElse(0.0).max(0)

end ExitAnimation
