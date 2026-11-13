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

import scala.scalajs.js

/**
  * Jumps to an item by typing the start of its label, as in a list box or a menu. Keys typed within
  * `timeoutMillis` of each other add up to one search: typing "ca" moves to "Cat", not to an item
  * starting with "a". Typing the same letter again moves on to the next item starting with it.
  *
  * {{{
  *   val typeahead = Typeahead()
  *   // in a keydown handler
  *   typeahead.onKey(e, labels, activeIndex).foreach(moveTo)
  * }}}
  *
  * @param now
  *   the clock in milliseconds; tests pass their own
  */
class Typeahead(timeoutMillis: Double = 500, now: () => Double = () => js.Date.now()):
  private var buffer   = ""
  private var lastTime = Double.NegativeInfinity

  /** The text typed so far in the current search. */
  def query: String = buffer

  /** Starts the next key on a new search. */
  def reset(): Unit = buffer = ""

  /**
    * The index of the item to move to after typing `key`, or None when `key` is not a single
    * character or no label matches.
    *
    * @param current
    *   the index of the active item, or -1
    */
  def onKey(key: String, labels: Seq[String], current: Int): Option[Int] =
    if key.length != 1 then
      None
    else
      val t = now()
      if t - lastTime > timeoutMillis then
        buffer = ""
      lastTime = t
      buffer += key.toLowerCase
      if buffer.isBlank then
        // A space alone does not search; leave it to the component (to select, say)
        buffer = ""
        None
      else if buffer.forall(_ == buffer.head) then
        // One letter, or the same letter repeated: cycle through the items starting with it
        RovingFocus.typeahead(labels, current, buffer.take(1))
      else
        // A longer prefix: the current item stays active while it still matches
        Typeahead.find(labels, current, buffer)

  /**
    * [[onKey]] for a key event. Keys with Ctrl, Cmd, or Alt, and keys during IME composition, are
    * shortcuts or text, not a search.
    */
  def onKey(e: dom.KeyboardEvent, labels: Seq[String], current: Int): Option[Int] =
    if e.ctrlKey || e.metaKey || e.altKey || e.isComposing then
      None
    else
      onKey(e.key, labels, current)

end Typeahead

object Typeahead:
  /**
    * The first label at or after `from` (wrapping around) that starts with `prefix`, ignoring case
    * and leading spaces.
    */
  def find(labels: Seq[String], from: Int, prefix: String): Option[Int] =
    val p = prefix.toLowerCase
    if labels.isEmpty || p.isEmpty then
      None
    else
      val start = math.max(from, 0)
      (0 until labels.size)
        .map(step => (start + step) % labels.size)
        .find(i => labels(i).trim.toLowerCase.startsWith(p))

end Typeahead
