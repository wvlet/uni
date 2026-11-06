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
    *   the index of the active item
    * @param count
    *   the number of items
    */
  def nextIndex(
      key: String,
      current: Int,
      count: Int,
      orientation: Orientation = Orientation.Horizontal
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
      key match
        case `nextKey` =>
          Some((current + 1) % count)
        case `prevKey` =>
          Some((current - 1 + count) % count)
        case "Home" =>
          Some(0)
        case "End" =>
          Some(count - 1)
        case _ =>
          None

end RovingFocus
