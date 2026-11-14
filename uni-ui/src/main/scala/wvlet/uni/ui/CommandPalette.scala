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
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.*

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * One entry of a [[CommandPalette]]: something to run, or a place to go.
  *
  * {{{
  *   Command("toggle-dock", "Toggle dock").withGroup("View").withShortcut("⌘⇧B").onRun(() => layout.toggleDock())
  *   Command(s"chat-${c.id}", c.title).withGroup("Conversations").withHint(ago(c.updatedAt)).onRun(() => store.select(c.id))
  * }}}
  *
  * @param id
  *   unique within the palette's list
  * @param keywords
  *   more words that match the command without being shown, such as "dark" for "Theme: Night"
  */
class Command(
    val id: String,
    val label: String,
    val group: Option[String] = None,
    val hint: Option[String] = None,
    val shortcut: Option[String] = None,
    val keywords: Seq[String] = Seq.empty,
    val action: () => Unit = () => ()
):
  private def copy(
      group: Option[String] = group,
      hint: Option[String] = hint,
      shortcut: Option[String] = shortcut,
      keywords: Seq[String] = keywords,
      action: () => Unit = action
  ): Command = Command(id, label, group, hint, shortcut, keywords, action)

  /** The heading the command is listed under while the query is empty. */
  def withGroup(g: String): Command = copy(group = Some(g))
  def noGroup(): Command            = copy(group = None)

  /** Faint text at the end of the row, such as a date or a path. */
  def withHint(h: String): Command = copy(hint = Some(h))
  def noHint(): Command            = copy(hint = None)

  /** A key hint shown at the end of the row, such as "⌘⇧B". It doesn't bind the key. */
  def withShortcut(s: String): Command = copy(shortcut = Some(s))
  def noShortcut(): Command            = copy(shortcut = None)

  def withKeywords(k: String*): Command = copy(keywords = k)

  /** Runs the command; [[ChangeCause.current]] tells whether a pointer or the keyboard chose it. */
  def onRun(f: () => Unit): Command = copy(action = f)

end Command

/**
  * A search box over a list of commands, in a modal dialog near the top of the window: the ⌘K
  * palette of desktop apps. Typing filters the list by fuzzy match (see [[Fuzzy]]) and highlights
  * the matched letters; Up and Down move, Enter or a click runs the command, Escape or a click
  * outside closes the palette, and focus returns to where it was.
  *
  * {{{
  *   val paletteOpen = Rx.variable(false)
  *   Keyboard.bindAll("meta+k" -> (() => paletteOpen := true))
  *   CommandPalette(paletteOpen, commands)
  * }}}
  *
  * `commands` can be an `Rx` whose list changes, such as one that adds the open conversations.
  * While the query is empty, commands are listed in order under their groups; once the user types,
  * the best matches come first. The palette closes before the command runs, so a command that opens
  * another dialog returns focus to the palette's opener. uni-ui doesn't bind ⌘K: bind it with
  * `Keyboard.bindAll`.
  *
  * The panel is `div.uni-dialog.uni-dialog--palette` holding `input.uni-palette__input`
  * (`role="combobox"`) and `div.uni-palette__list` (`role="listbox"`), whose rows are
  * `div.uni-palette__item` with `data-active="true"` on the highlighted one and `mark` around
  * matched letters.
  */
class CommandPalette(
    open: RxVar[Boolean],
    commands: Rx[Seq[Command]],
    placeholderText: String = "Type a command or search",
    emptyText: String = "No matching commands",
    label: String = "Command palette",
    limit: Int = 50,
    filter: Option[(String, Seq[Command]) => Seq[Command]] = None,
    changeHandler: ChangeHandler = ChangeGate.noHandler
) extends RxElement:

  private def copy(
      placeholderText: String = placeholderText,
      emptyText: String = emptyText,
      label: String = label,
      limit: Int = limit,
      filter: Option[(String, Seq[Command]) => Seq[Command]] = filter,
      changeHandler: ChangeHandler = changeHandler
  ): CommandPalette = CommandPalette(
    open,
    commands,
    placeholderText,
    emptyText,
    label,
    limit,
    filter,
    changeHandler
  )

  /**
    * Called before the palette closes, on Escape, a click outside, or a run command; `cancel()`
    * keeps it open, and the command then doesn't run. Key: `open`.
    */
  def onChange(f: ChangeHandler): CommandPalette = copy(changeHandler = f)

  def withPlaceholder(p: String): CommandPalette = copy(placeholderText = p)

  /** The text shown when nothing matches. */
  def withEmptyText(t: String): CommandPalette = copy(emptyText = t)

  /** The accessible name of the palette dialog. */
  def withLabel(l: String): CommandPalette = copy(label = l)

  /** The most results to list at once (50 by default). */
  def withLimit(n: Int): CommandPalette = copy(limit = math.max(1, n))

  /**
    * Replace the fuzzy filter: `f` gets the query and every command, and returns the ones to show
    * in order. Matched letters are still highlighted where the fuzzy match finds them.
    */
  def withFilter(f: (String, Seq[Command]) => Seq[Command]): CommandPalette = copy(filter = Some(f))
  def noFilter(): CommandPalette                                            = copy(filter = None)

  private val listId = Ids.next("uni-palette")
  private val query  = Rx.variable("")
  private val active = Rx.variable(Option.empty[String])
  // The latest results, for keyboard moves; kept in step by the rendered list
  private var current: Seq[CommandPalette.Hit] = Seq.empty

  private def itemId(c: Command): String = s"${listId}-${c.id}"

  private def search(q: String, all: Seq[Command]): Seq[CommandPalette.Hit] =
    def positions(c: Command) = Fuzzy.matches(q, c.label).map(_.positions).getOrElse(Seq.empty)
    val hits                  =
      filter match
        case Some(f) =>
          f(q, all).map(c => CommandPalette.Hit(c, positions(c)))
        case None if q.isBlank =>
          // Keep the given order, with each group's commands together
          val groups = all.map(_.group).distinct
          groups.flatMap(g => all.filter(_.group == g)).map(c => CommandPalette.Hit(c, Seq.empty))
        case None =>
          all
            .flatMap { c =>
              val onLabel = Fuzzy.matches(q, c.label)
              val best    = (onLabel.toSeq ++ c.keywords.flatMap(Fuzzy.matches(q, _))).maxByOption(
                _.score
              )
              best.map(m =>
                (CommandPalette.Hit(c, onLabel.map(_.positions).getOrElse(Seq.empty)), m.score)
              )
            }
            .sortBy(-_._2)
            .map(_._1)
    hits.take(limit)

  private def run(c: Command, cause: ChangeCause): Unit =
    // Close first, so focus is back on the opener when the command runs
    if ChangeGate.write(open, "open", false, cause, changeHandler) then
      query := ""
      ChangeCause.during(cause)(c.action())

  private def moveTo(i: Int): Unit = current
    .lift(i)
    .foreach { h =>
      active := Some(h.command.id)
      Option(dom.document.getElementById(itemId(h.command))).foreach(
        _.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
      )
    }

  private def onKey(e: dom.KeyboardEvent): Unit =
    if !Keyboard.isComposing(e) then
      val i = active.get.map(id => current.indexWhere(_.command.id == id)).getOrElse(-1)
      e.key match
        case "Enter" =>
          current
            .lift(i)
            .foreach { h =>
              e.preventDefault()
              run(h.command, ChangeCause.of(ChangeReason.ItemPress, e))
            }
        case "ArrowDown" | "ArrowUp" =>
          RovingFocus
            .nextIndex(e.key, i, current.size, Orientation.Vertical)
            .foreach { n =>
              e.preventDefault()
              moveTo(n)
            }
        case _ =>

  private def highlighted(text: String, positions: Seq[Int]): DomNode =
    if positions.isEmpty then
      span(text)
    else
      val marked = positions.toSet
      // Runs of matched and unmatched letters
      val runs =
        text
          .indices
          .foldLeft(List.empty[(Boolean, StringBuilder)]) { (acc, i) =>
            val m = marked.contains(i)
            acc match
              case (`m`, sb) :: _ =>
                sb += text.charAt(i)
                acc
              case _ =>
                (m, StringBuilder().append(text.charAt(i))) :: acc
          }
          .reverse
      span(
        runs.map { (m, sb) =>
          if m then
            mark(cls -> "uni-palette__match", sb.toString)
          else
            span(sb.toString)
        }
      )

  private def item(row: CommandPalette.Row): DomNode =
    row match
      case CommandPalette.Heading(g) =>
        div(
          cls          -> "uni-palette__group",
          data("slot") -> "command-palette-group",
          role         -> "presentation",
          g
        )
      case CommandPalette.Hit(c, positions) =>
        div(
          id             -> itemId(c),
          role           -> "option",
          cls            -> "uni-palette__item",
          data("slot")   -> "command-palette-item",
          ariaSelected   -> active.map(a => a.contains(c.id).toString),
          data("active") ->
            active.map(a =>
              if a.contains(c.id) then
                Some("true")
              else
                None
            ),
          EventListenerBinding("pointermove", _ => active := Some(c.id)),
          // Keep focus in the input
          EventListenerBinding("pointerdown", e => e.preventDefault()),
          EventListenerBinding("click", e => run(c, ChangeCause.of(ChangeReason.ItemPress, e))),
          span(cls -> "uni-palette__label", highlighted(c.label, positions)),
          c.hint.map(h => span(cls -> "uni-palette__hint", h)).getOrElse(DomNode.empty),
          c.shortcut.map(s => Kbd(s)).getOrElse(DomNode.empty)
        )

  private val rows: Rx[Seq[CommandPalette.Row]] = Rx
    .join(commands, query)
    .map { (all, q) =>
      val hits = search(q, all)
      current = hits
      // Highlight the first result after each change, unless the highlighted one is still listed
      if !active.get.exists(id => hits.exists(_.command.id == id)) || q != lastQuery then
        active := hits.headOption.map(_.command.id)
      lastQuery = q
      noResults := hits.isEmpty
      if q.isBlank && filter.isEmpty then
        CommandPalette.withHeadings(hits)
      else
        hits
    }

  private var lastQuery = ""
  // Set by the one subscription to `rows` (the list), so the empty state doesn't search again
  private val noResults = Rx.variable(false)

  // Starts empty each time the palette opens
  private class Body extends RxElement:
    override def beforeRender: Unit =
      query := ""
      lastQuery = ""
      // Highlight the first result again, not the command run last time
      active := None

    override def render: RxElement = div(
      cls          -> "uni-palette",
      data("slot") -> "command-palette",
      input(
        tpe                  -> "text",
        cls                  -> "uni-palette__input",
        data("slot")         -> "command-palette-input",
        role                 -> "combobox",
        autofocus            -> true,
        ariaExpanded         -> "true",
        ariaControls         -> listId,
        ariaAutocomplete     -> "list",
        ariaLabel            -> label,
        ariaActivedescendant -> active.map(_.map(id => s"${listId}-${id}")),
        placeholder          -> placeholderText,
        value.bind(query),
        EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent]))
      ),
      div(
        id           -> listId,
        cls          -> "uni-palette__list",
        data("slot") -> "command-palette-list",
        role         -> "listbox",
        ariaLabel    -> label,
        each(rows)(CommandPalette.rowKey)(item)
      ),
      noResults.map { empty =>
        if empty then
          div(cls -> "uni-palette__empty", data("slot") -> "command-palette-empty", emptyText)
        else
          DomNode.empty
      }
    )

  end Body

  private val dialog = Dialog(open, content = Seq(Body()))
    .withVariant("palette")
    .withSlot("command-palette-dialog")
    .withAccessibleName(label)
    .onChange(changeHandler)
    .onDismiss(() => query := "")

  override def render: RxElement = dialog

end CommandPalette

object CommandPalette:
  private[ui] sealed trait Row
  private[ui] case class Heading(group: String)                     extends Row
  private[ui] case class Hit(command: Command, positions: Seq[Int]) extends Row

  private[ui] def rowKey(r: Row): String =
    r match
      case Heading(g) =>
        s"group:${g}"
      case Hit(c, _) =>
        s"command:${c.id}"

  private[ui] def withHeadings(hits: Seq[Hit]): Seq[Row] =
    hits
      .foldLeft((Seq.empty[Row], Option.empty[String])) { case ((acc, prev), h) =>
        h.command.group match
          case Some(g) if !prev.contains(g) =>
            (acc :+ Heading(g) :+ h, Some(g))
          case g =>
            (acc :+ h, g)
      }
      ._1

end CommandPalette
