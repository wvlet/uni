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
package wvlet.uni.ui.base

import org.scalajs.dom

/**
  * Tells screen reader users about a change they cannot see, such as "3 results" or "Response
  * complete", through an ARIA live region. The regions are visually hidden and shared by the whole
  * page.
  *
  * {{{
  *   Announce.polite("Saved")
  *   Announce.assertive("Connection lost")
  * }}}
  */
object Announce:
  /** How long a message stays in its region; only the newest is kept after that. */
  private val keepMillis = 7000

  /** How long the first message waits when its region was just added. */
  private val freshDelayMillis = 250

  /** Reads `message` once the screen reader finishes what it is saying. */
  def polite(message: String): Unit = announce(message, "polite")

  /** Reads `message` right away, interrupting. Keep it for errors and urgent news. */
  def assertive(message: String): Unit = announce(message, "assertive")

  /**
    * Adds the live regions to the page now. Screen readers often skip a message put into a region
    * that has just appeared, so call this when the app starts; the first message is otherwise held
    * back a moment.
    */
  def prepare(): Unit = Seq("polite", "assertive").foreach(region)

  /** Empties both regions, e.g. when the page changes. */
  def clear(): Unit = Seq("polite", "assertive").foreach(p => find(p).foreach(_.textContent = ""))

  private def announce(message: String, politeness: String): Unit =
    if !message.isBlank then
      val fresh = find(politeness).isEmpty
      val r     = region(politeness)
      // A new node, rather than new text, makes a repeated message read again
      val line = dom.document.createElement("div")
      line.textContent = message
      if fresh then
        // Give screen readers time to notice the new region first
        dom.window.setTimeout(() => r.appendChild(line), freshDelayMillis)
      else
        r.appendChild(line)
      dom
        .window
        .setTimeout(
          () =>
            if (line.parentNode eq r) && (r.lastChild ne line) then
              r.removeChild(line),
          keepMillis
        )

  private def find(politeness: String): Option[dom.Element] = Option(
    dom.document.querySelector(s"[data-slot=announcer][aria-live=${politeness}]")
  )

  private def region(politeness: String): dom.Element = find(politeness).getOrElse {
    val r = dom.document.createElement("div")
    r.setAttribute("data-slot", "announcer")
    r.setAttribute("aria-live", politeness)
    r.setAttribute(
      "role",
      if politeness == "assertive" then
        "alert"
      else
        "status"
    )
    r.setAttribute("aria-atomic", "false")
    // Hidden from view but not from assistive technology
    r.setAttribute(
      "style",
      "position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0"
    )
    dom.document.body.appendChild(r)
    r
  }

end Announce
