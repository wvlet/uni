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

import wvlet.uni.dom.StorageCodec
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.msgpack.spi.{Packer, Unpacker}
import wvlet.uni.weaver.{Weaver, WeaverConfig, WeaverContext}
import wvlet.uni.weaver.codec.JSONValueWeaver

import scala.util.control.NonFatal

/** How a [[DockNode.Split]] lays out its children: side by side (`Row`) or stacked (`Column`). */
enum SplitDirection derives Weaver:
  case Row,
    Column

/** Where a dragged tab lands on a group: in it (`Center`), or beside it on one of its edges. */
enum DockSide:
  case Center,
    Left,
    Right,
    Top,
    Bottom

/**
  * A node of a [[DockLayout]]: a split of child nodes, or a group of tabbed panels. Panels are
  * referred to by id only, so a layout is plain data the app can save, compare, and test.
  */
enum DockNode derives Weaver:
  /**
    * Children laid out in `direction`. `sizes` are the children's shares of the space, summing to
    * 1, so the proportions hold when the window resizes.
    */
  case Split(direction: SplitDirection, children: Seq[DockNode], sizes: Seq[Double] = Seq.empty)

  /** Tabs for `panels`, showing the `active` one. */
  case Group(panels: Seq[String], active: Option[String])

object DockNode:
  /** A group of `panels` that shows the first one. */
  def group(panels: String*): DockNode = Group(panels, panels.headOption)

  /** Children side by side, with equal shares. */
  def row(children: DockNode*): DockNode = Split(SplitDirection.Row, children, equal(children.size))

  /** Children stacked top to bottom, with equal shares. */
  def column(children: DockNode*): DockNode = Split(
    SplitDirection.Column,
    children,
    equal(children.size)
  )

  private[base] def equal(n: Int): Seq[Double] = Seq.fill(n)(1.0 / n)

end DockNode

/**
  * The arrangement of a [[wvlet.uni.ui.Dock]]: a tree of splits and tab groups. It is plain data
  * held in an `RxVar`, so the app decides where it is saved:
  * {{{
  *   val layout = Storage.local("app.dock", DockLayout(DockNode.row(DockNode.group("files"), DockNode.group("editor"))))
  * }}}
  *
  * The saved JSON carries a format version ([[DockLayout.version]]). A save that is unreadable or
  * from a newer version decodes to None, so `Storage.local` falls back to the default.
  */
case class DockLayout(root: DockNode):
  /** This layout as JSON, with the format version. */
  def toJSON: String = DockLayout.toJSONValue(this).toJSON

  /** Show `panel`: switch to its tab, or add it to the first group when it is not in the layout. */
  def open(panel: String): DockLayout = DockModel.open(this, panel)

  /** Remove `panel` from the layout. */
  def close(panel: String): DockLayout = DockModel.close(this, panel)

  /** The panels the user can see: the active tab of each group. */
  def visible: Set[String] = DockModel.visible(root)

object DockLayout:
  /** The current format version, written as `"version"` in the JSON. */
  val version: Int = 2

  /** A layout with no panels. */
  val empty: DockLayout = DockLayout(DockNode.Group(Seq.empty, None))

  /** A layout of one group holding `panels`. */
  def of(panels: String*): DockLayout = DockLayout(DockNode.group(panels*))

  // Fixed rather than the caller's config, so an app's Weaver settings never change the saved shape
  private val format = WeaverConfig().withOmitNoneFields(true)

  /** The saved form: `{"version":2,"root":...}`, with `root` in the derived `DockNode` format. */
  def toJSONValue(l: DockLayout): JSONValue = JSONObject(
    Seq("version" -> JSONLong(version), "root" -> JSON.parse(Weaver.toJson(l.root, format)))
  )

  /** Reads a saved layout, migrating older versions; None when it is unreadable or newer. */
  def fromJSONValue(v: JSONValue): Option[DockLayout] =
    def decode(root: JSONValue): Option[DockLayout] =
      try
        Some(DockLayout(summon[Weaver[DockNode]].fromJSONValue(root, format)))
      catch
        case NonFatal(_) =>
          None
    v match
      case o: JSONObject =>
        (o.get("version"), o.get("root")) match
          case (Some(JSONLong(2)), Some(root)) =>
            decode(root)
          case (Some(JSONLong(1)), Some(root)) =>
            decode(fromV1(root))
          case _ =>
            None
      case _ =>
        None

  /**
    * Version 1 wrote `{"type":"split","direction":"row",...}`. Rename the discriminator and use the
    * declared case names, so the derived codec reads it.
    */
  private def fromV1(v: JSONValue): JSONValue =
    v match
      case JSONObject(fields) =>
        JSONObject(
          fields.map {
            case ("type", JSONString(t)) =>
              format.discriminatorFieldName -> JSONString(t.capitalize)
            case ("direction", JSONString(d)) =>
              "direction" -> JSONString(d.capitalize)
            case ("children", JSONArray(cs)) =>
              "children" -> JSONArray(cs.map(fromV1))
            case other =>
              other
          }
        )
      case other =>
        other

  /** Parses saved JSON; None when it is unreadable or from a newer format version. */
  def fromJSON(json: String): Option[DockLayout] =
    try
      fromJSONValue(JSON.parse(json))
    catch
      case NonFatal(_) =>
        None

  /** The saved form, with its version, whatever the caller's config. */
  given Weaver[DockLayout] with
    override def pack(p: Packer, v: DockLayout, config: WeaverConfig): Unit = JSONValueWeaver.pack(
      p,
      toJSONValue(v),
      config
    )

    override def unpack(u: Unpacker, context: WeaverContext): Unit =
      DockLayout.fromJSONValue(JSONValueWeaver.unpackJson(u)) match
        case Some(l) =>
          context.setObject(l)
        case None =>
          context.setError(IllegalArgumentException("Not a valid DockLayout"))

  /** Saves the layout as JSON in browser storage (`Storage.local(key, default)`). */
  given StorageCodec[DockLayout] with
    def encode(value: DockLayout): String       = value.toJSON
    def decode(raw: String): Option[DockLayout] = fromJSON(raw)

end DockLayout

/** A step down the layout tree: the index of a child in each split from the root. */
type DockPath = List[Int]

/**
  * Pure operations on a [[DockLayout]]. Each returns a [[normalize]]d layout, so empty groups
  * disappear, single-child splits collapse, and the sizes stay valid.
  */
object DockModel:

  /**
    * Clean a layout up, on load and after every operation:
    *   - drop panels that `keep` rejects (unknown ids) and repeated ids;
    *   - remove empty groups, collapse single-child splits, and flatten a split nested in the same
    *     direction into its parent;
    *   - reset sizes that are missing or not positive to equal shares, and rescale them to sum to
    *     1;
    *   - make `active` a panel of its group;
    *   - append `required` panels that are missing to the first group.
    *
    * An empty layout becomes [[DockLayout.empty]].
    */
  def normalize(
      layout: DockLayout,
      keep: String => Boolean = _ => true,
      required: Seq[String] = Seq.empty
  ): DockLayout =
    val seen = scala.collection.mutable.Set.empty[String]

    def go(n: DockNode): Option[DockNode] =
      n match
        case DockNode.Group(panels, active) =>
          val ps = panels.filter(p => keep(p) && seen.add(p))
          if ps.isEmpty then
            None
          else
            Some(DockNode.Group(ps, active.filter(ps.contains).orElse(ps.headOption)))
        case DockNode.Split(dir, children, sizes) =>
          val valid  = sizes.size == children.size && sizes.forall(s => s > 0 && !s.isInfinite)
          val shares =
            if valid then
              sizes
            else
              DockNode.equal(children.size)
          val kept = children
            .zip(shares)
            .flatMap { (c, s) =>
              go(c).map(_ -> s)
            }
            // A child split in the same direction merges into this one, scaled to its share
            .flatMap {
              case (DockNode.Split(d, cs, ss), s) if d == dir =>
                cs.zip(ss.map(_ * s))
              case other =>
                Seq(other)
            }
          kept match
            case Seq() =>
              None
            case Seq((only, _)) =>
              Some(only)
            case _ =>
              val total = kept.map(_._2).sum
              Some(DockNode.Split(dir, kept.map(_._1), kept.map(_._2 / total)))

    end go

    val root    = go(layout.root).getOrElse(DockLayout.empty.root)
    val missing = required.filter(p => keep(p) && !seen.contains(p))
    if missing.isEmpty then
      DockLayout(root)
    else
      val first = firstGroup(root)
      DockLayout(
        updateAt(root, first) {
          case DockNode.Group(ps, active) =>
            val all = ps ++ missing
            DockNode.Group(all, active.orElse(all.headOption))
          case other =>
            other
        }
      )

  end normalize

  /** The node at `path`, if there is one. */
  def nodeAt(root: DockNode, path: DockPath): Option[DockNode] =
    path match
      case Nil =>
        Some(root)
      case i :: rest =>
        root match
          case DockNode.Split(_, cs, _) if i >= 0 && i < cs.size =>
            nodeAt(cs(i), rest)
          case _ =>
            None

  /** The paths of every group, in reading order (depth first). */
  def groups(root: DockNode): Seq[DockPath] =
    def go(n: DockNode, path: DockPath): Seq[DockPath] =
      n match
        case DockNode.Group(_, _) =>
          Seq(path)
        case DockNode.Split(_, cs, _) =>
          cs.zipWithIndex.flatMap((c, i) => go(c, path :+ i))
    go(root, Nil)

  /** The path of the first group. */
  def firstGroup(root: DockNode): DockPath = groups(root).headOption.getOrElse(Nil)

  /** The path of the group in the top-right corner, where apps put dock-wide actions. */
  def cornerGroup(root: DockNode): DockPath =
    root match
      case DockNode.Split(SplitDirection.Row, cs, _) =>
        (cs.size - 1) :: cornerGroup(cs.last)
      case DockNode.Split(SplitDirection.Column, cs, _) =>
        0 :: cornerGroup(cs.head)
      case DockNode.Group(_, _) =>
        Nil

  /** The paths of the groups along the top edge, where apps align their title bar. */
  def topGroups(root: DockNode): Set[DockPath] =
    def go(n: DockNode, path: DockPath): Set[DockPath] =
      n match
        case DockNode.Group(_, _) =>
          Set(path)
        case DockNode.Split(SplitDirection.Row, cs, _) =>
          cs.zipWithIndex.flatMap((c, i) => go(c, path :+ i)).toSet
        case DockNode.Split(SplitDirection.Column, cs, _) =>
          go(cs.head, path :+ 0)
    go(root, Nil)

  /** The path of the group holding `panel`. */
  def groupOf(root: DockNode, panel: String): Option[DockPath] = groups(root).find { p =>
    nodeAt(root, p).exists {
      case DockNode.Group(ps, _) =>
        ps.contains(panel)
      case _ =>
        false
    }
  }

  /** Every panel in the layout, in reading order. */
  def panels(root: DockNode): Seq[String] =
    root match
      case DockNode.Group(ps, _) =>
        ps
      case DockNode.Split(_, cs, _) =>
        cs.flatMap(panels)

  /** The panels the user can see: the active one of each group. */
  def visible(root: DockNode): Set[String] =
    root match
      case DockNode.Group(_, active) =>
        active.toSet
      case DockNode.Split(_, cs, _) =>
        cs.flatMap(visible).toSet

  /**
    * The layout's structure without sizes or active tabs: what has to be rebuilt when it changes.
    */
  def shape(root: DockNode): DockNode =
    root match
      case DockNode.Group(ps, _) =>
        DockNode.Group(ps, None)
      case DockNode.Split(d, cs, _) =>
        DockNode.Split(d, cs.map(shape), Seq.empty)

  private def updateAt(root: DockNode, path: DockPath)(f: DockNode => DockNode): DockNode =
    path match
      case Nil =>
        f(root)
      case i :: rest =>
        root match
          case DockNode.Split(d, cs, ss) if i >= 0 && i < cs.size =>
            DockNode.Split(d, cs.updated(i, updateAt(cs(i), rest)(f)), ss)
          case other =>
            other

  // Takes `panel` out of its group but leaves the group in place, even if empty, so the paths
  // of the other nodes stay valid until the result is normalized
  private def detach(root: DockNode, panel: String): DockNode =
    root match
      case DockNode.Group(ps, active) if ps.contains(panel) =>
        val i    = ps.indexOf(panel)
        val rest = ps.filterNot(_ == panel)
        val next =
          if active.contains(panel) then
            // The neighbor takes over, as when closing a tab in an editor
            rest.lift(math.min(i, rest.size - 1))
          else
            active
        DockNode.Group(rest, next)
      case g: DockNode.Group =>
        g
      case DockNode.Split(d, cs, ss) =>
        DockNode.Split(d, cs.map(detach(_, panel)), ss)

  private def insert(group: DockNode, panel: String, index: Int): DockNode =
    group match
      case DockNode.Group(ps, _) =>
        val at = math.max(0, math.min(index, ps.size))
        DockNode.Group(ps.patch(at, Seq(panel), 0), Some(panel))
      case other =>
        other

  /** Show `panel` in its group. */
  def activate(layout: DockLayout, panel: String): DockLayout =
    groupOf(layout.root, panel) match
      case Some(path) =>
        DockLayout(
          updateAt(layout.root, path) {
            case DockNode.Group(ps, _) =>
              DockNode.Group(ps, Some(panel))
            case other =>
              other
          }
        )
      case None =>
        layout

  /**
    * Move `panel` into the group at `target`, before the tab at `index` (the end when `index` is
    * past the last tab), and show it. Within its own group, this reorders the tabs.
    */
  def move(layout: DockLayout, panel: String, target: DockPath, index: Int): DockLayout =
    (groupOf(layout.root, panel), nodeAt(layout.root, target)) match
      case (Some(from), Some(DockNode.Group(ps, _))) =>
        // Removing the tab first shifts the later tabs of the same group left by one
        val at =
          if from == target && ps.indexOf(panel) < index then
            index - 1
          else
            index
        val detached = detach(layout.root, panel)
        normalize(DockLayout(updateAt(detached, target)(insert(_, panel, at))))
      case _ =>
        layout

  /**
    * Move `panel` beside the group at `target`, splitting it on `side`. `DockSide.Center` moves the
    * panel into the group instead. Splitting a group's only tab off itself does nothing.
    */
  def split(layout: DockLayout, panel: String, target: DockPath, side: DockSide): DockLayout =
    nodeAt(layout.root, target) match
      case Some(DockNode.Group(ps, _)) if groupOf(layout.root, panel).isDefined =>
        side match
          case DockSide.Center =>
            move(layout, panel, target, ps.size)
          case _ if ps == Seq(panel) =>
            layout
          case _ =>
            val dir =
              side match
                case DockSide.Left | DockSide.Right =>
                  SplitDirection.Row
                case _ =>
                  SplitDirection.Column
            val before   = side == DockSide.Left || side == DockSide.Top
            val detached = detach(layout.root, panel)
            val result   =
              updateAt(detached, target) { g =>
                val added = DockNode.group(panel)
                val cs    =
                  if before then
                    Seq(added, g)
                  else
                    Seq(g, added)
                DockNode.Split(dir, cs, Seq(0.5, 0.5))
              }
            normalize(DockLayout(result))
      case _ =>
        layout

  /** Remove `panel` from the layout. */
  def close(layout: DockLayout, panel: String): DockLayout = normalize(
    DockLayout(detach(layout.root, panel))
  )

  /**
    * Show `panel`: activate it when it is in the layout, otherwise add it to the group at `target`
    * (the first group when `target` is not a group) and show it there.
    */
  def open(layout: DockLayout, panel: String, target: Option[DockPath] = None): DockLayout =
    if groupOf(layout.root, panel).isDefined then
      activate(layout, panel)
    else
      val path = target
        .filter(p =>
          nodeAt(layout.root, p).exists {
            case _: DockNode.Group =>
              true
            case _ =>
              false
          }
        )
        .getOrElse(firstGroup(layout.root))
      normalize(DockLayout(updateAt(layout.root, path)(g => insert(g, panel, Int.MaxValue))))

  /** Replace the sizes of the split at `path`. */
  def withSizes(layout: DockLayout, path: DockPath, sizes: Seq[Double]): DockLayout = DockLayout(
    updateAt(layout.root, path) {
      case DockNode.Split(d, cs, _) if sizes.size == cs.size =>
        DockNode.Split(d, cs, sizes)
      case other =>
        other
    }
  )

  /**
    * Move the border after child `border` by `deltaPx` pixels, in a split whose children share
    * `containerPx` pixels. Only the two children beside the border change, and neither goes below
    * `minPx`; when both together are smaller than twice the minimum, nothing changes.
    */
  def resize(
      sizes: Seq[Double],
      border: Int,
      deltaPx: Double,
      containerPx: Double,
      minPx: Double
  ): Seq[Double] =
    if border < 0 || border + 1 >= sizes.size || containerPx <= 0 then
      sizes
    else
      val a    = sizes(border) * containerPx
      val b    = sizes(border + 1) * containerPx
      val both = a + b
      if both < 2 * minPx then
        sizes
      else
        val newA = math.max(minPx, math.min(a + deltaPx, both - minPx))
        sizes.updated(border, newA / containerPx).updated(border + 1, (both - newA) / containerPx)

end DockModel
