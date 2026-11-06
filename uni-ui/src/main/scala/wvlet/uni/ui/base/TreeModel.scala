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

/**
  * One visible row of a tree, flattened for rendering as a list.
  *
  * @param level
  *   1 for a root, 2 for its children, and so on (`aria-level`)
  * @param position
  *   1-based position among its siblings (`aria-posinset`)
  * @param siblings
  *   the number of siblings including itself (`aria-setsize`)
  * @param parent
  *   the key of the parent row, None for a root
  * @param expandable
  *   whether the item has children
  */
case class TreeRow[A, K](
    item: A,
    key: K,
    level: Int,
    position: Int,
    siblings: Int,
    parent: Option[K],
    expandable: Boolean,
    expanded: Boolean
)

/** What a key press asks a tree to do. */
enum TreeAction[+K]:
  case Move(index: Int)
  case Expand(key: K)
  case Collapse(key: K)

/** Tree flattening and the WAI-ARIA treeview keyboard model. */
object TreeModel:

  /** The rows a user can see: the roots, and the children of every expanded row, depth first. */
  def flatten[A, K](
      roots: Seq[A],
      children: A => Seq[A],
      key: A => K,
      expanded: Set[K]
  ): Seq[TreeRow[A, K]] =
    val rows                                                      = Seq.newBuilder[TreeRow[A, K]]
    def visit(items: Seq[A], level: Int, parent: Option[K]): Unit = items
      .zipWithIndex
      .foreach { (a, i) =>
        val k    = key(a)
        val kids = children(a)
        val open = kids.nonEmpty && expanded.contains(k)
        rows += TreeRow(a, k, level, i + 1, items.size, parent, kids.nonEmpty, open)
        if open then
          visit(kids, level + 1, Some(k))
      }
    visit(roots, 1, None)
    rows.result()

  /**
    * The action for `key` on the active row `current` (-1 when none), or None when the key does
    * nothing:
    *
    *   - Up, Down, Home, End, PageUp, PageDown move as in [[ListNavigation]].
    *   - Right expands a collapsed row, or moves to the first child of an expanded one.
    *   - Left collapses an expanded row, or moves to the parent.
    */
  def onKey[A, K](
      key: String,
      rows: Seq[TreeRow[A, K]],
      current: Int,
      pageSize: Int
  ): Option[TreeAction[K]] =
    val row = rows.lift(current)
    key match
      case "ArrowRight" =>
        row.flatMap { r =>
          if r.expandable && !r.expanded then
            Some(TreeAction.Expand(r.key))
          else if r.expanded then
            Some(TreeAction.Move(current + 1))
          else
            None
        }
      case "ArrowLeft" =>
        row.flatMap { r =>
          if r.expanded then
            Some(TreeAction.Collapse(r.key))
          else
            r.parent.map(p => rows.indexWhere(_.key == p)).filter(_ >= 0).map(TreeAction.Move(_))
        }
      case _ =>
        ListNavigation.nextIndex(key, current, rows.size, pageSize).map(TreeAction.Move(_))

  end onKey

end TreeModel
