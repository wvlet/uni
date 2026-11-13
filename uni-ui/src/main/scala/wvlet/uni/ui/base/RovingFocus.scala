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

/** The axis along which the arrow keys move through a group of items. */
enum Orientation:
  case Horizontal,
    Vertical

/**
  * Arrow-key navigation for a group with one tab stop, such as a tab list or a toolbar ("roving
  * tabindex" in the WAI-ARIA Authoring Practices). Only the active item has `tabindex="0"`; the
  * arrow keys, Home, and End move between items.
  */
object RovingFocus:

  /**
    * The index to move to after pressing `key`, or None when the key does not navigate. The arrow
    * keys wrap around at both ends.
    *
    * @param current
    *   the index of the active item, or -1 when none is active: the next key then goes to the first
    *   item and the previous key to the last
    * @param count
    *   the number of items
    * @param rtl
    *   whether the group is laid out right to left ([[TextDirection.isRtl]]): ArrowLeft then moves
    *   to the next item in a horizontal group
    */
  def nextIndex(
      key: String,
      current: Int,
      count: Int,
      orientation: Orientation = Orientation.Horizontal,
      rtl: Boolean = false
  ): Option[Int] =
    if count <= 0 then
      None
    else
      val (prevKey, nextKey) =
        orientation match
          case Orientation.Horizontal =>
            ("ArrowLeft", "ArrowRight")
          case Orientation.Vertical =>
            ("ArrowUp", "ArrowDown")
      TextDirection.logicalKey(key, rtl && orientation == Orientation.Horizontal) match
        case `nextKey` =>
          Some((current + 1) % count)
        case `prevKey` if current < 0 =>
          Some(count - 1)
        case `prevKey` =>
          Some((current - 1 + count) % count)
        case "Home" =>
          Some(0)
        case "End" =>
          Some(count - 1)
        case _ =>
          None

  /**
    * The index of the next label after `current` that starts with `key` (ignoring case), wrapping
    * around: typing a letter in a menu or list jumps to the matching item. None when no label
    * matches.
    */
  def typeahead(labels: Seq[String], current: Int, key: String): Option[Int] =
    val k = key.toLowerCase
    if labels.isEmpty || k.isBlank then
      None
    else
      (1 to labels.size)
        .map(step => (current + step + labels.size) % labels.size)
        .find(i => labels(i).trim.toLowerCase.startsWith(k))

end RovingFocus
