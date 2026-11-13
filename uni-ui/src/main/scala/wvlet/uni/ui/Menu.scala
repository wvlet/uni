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
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.agent.{AgentAction, AgentBinding, AgentRegistry}
import wvlet.uni.ui.base.*

import scala.language.implicitConversions

/** An entry of a [[Menu]] or [[ContextMenu]]: a [[MenuItem]] or [[MenuSeparator]]. */
sealed trait MenuEntry

/** A line between groups of menu items. */
object MenuSeparator extends MenuEntry

/**
  * A command in a [[Menu]]. Choosing it (click, Enter, or Space) closes the menu, returns focus to
  * the trigger, and then runs the action.
  *
  * {{{
  *   MenuItem("Copy").withShortcut("⌘C").onSelect(() => copy())
  *   MenuItem("Dark").withChecked(theme.map(_ == ColorScheme.Dark)).onSelect(() => theme := ColorScheme.Dark)
  * }}}
  */
class MenuItem private (
    val content: Seq[DomNode],
    val action: () => Unit,
    val disabledState: Rx[Boolean],
    val shortcut: Option[String],
    val checked: Option[Rx[Boolean]]
) extends MenuEntry:

  private def copy(
      action: () => Unit = action,
      disabledState: Rx[Boolean] = disabledState,
      shortcut: Option[String] = shortcut,
      checked: Option[Rx[Boolean]] = checked
  ): MenuItem = new MenuItem(content, action, disabledState, shortcut, checked)

  /** Runs when the item is chosen; [[ChangeCause.current]] tells whether by pointer or keyboard. */
  def onSelect(f: () => Unit): MenuItem = copy(action = f)

  def withDisabled(d: Rx[Boolean]): MenuItem = copy(disabledState = d)
  def withDisabled(d: Boolean): MenuItem     = copy(disabledState = Rx.const(d))

  /** A key hint shown at the end of the row, such as "⌘C". It doesn't bind the key. */
  def withShortcut(s: String): MenuItem = copy(shortcut = Some(s))
  def noShortcut(): MenuItem            = copy(shortcut = None)

  /** Make this a `menuitemcheckbox` that shows a check mark while `c` is true. */
  def withChecked(c: Rx[Boolean]): MenuItem = copy(checked = Some(c))
  def noChecked(): MenuItem                 = copy(checked = None)

end MenuItem

object MenuItem:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(content: DomNode*): MenuItem =
    new MenuItem(content, () => (), Rx.const(false), None, None)

/** The open menu list shared by [[Menu]] and [[ContextMenu]]. */
private[ui] object MenuList:
  // One menu has the keyboard at a time, so they share the search
  private val typeahead = Typeahead()

  private def items(menu: dom.Element): Seq[dom.HTMLElement] =
    val nodes = menu.querySelectorAll("[role^=menuitem]")
    (0 until nodes.length).collect {
      case i if nodes(i).getAttribute("aria-disabled") != "true" =>
        nodes(i).asInstanceOf[dom.HTMLElement]
    }

  def itemBinding(item: MenuItem): AgentBinding =
    new AgentBinding:
      private var checked: Option[Boolean] = None
      override def kind: String            = "menuitem"
      override def value: Option[String]   = checked.map(c =>
        if c then
          "checked"
        else
          "unchecked"
      )
      override def watch(): Cancelable = item
        .checked
        .map(_.subscribe(c => checked = Some(c)))
        .getOrElse(Cancelable.empty)
      override def actions: Seq[AgentAction] = Seq(AgentAction.press(this, "Choose it"))

  def first(menu: dom.Element): Option[dom.HTMLElement] = items(menu).headOption
  def last(menu: dom.Element): Option[dom.HTMLElement]  = items(menu).lastOption

  private def onKey(menu: dom.Element, e: dom.KeyboardEvent, close: () => Boolean): Unit =
    val all     = items(menu)
    val current = all.indexWhere(_ eq dom.document.activeElement)
    e.key match
      case "Enter" | " " =>
        e.preventDefault()
        all.lift(current).foreach(_.click())
      case "Tab" =>
        // Close and let the Tab move on from the trigger; a refused close keeps focus in the menu
        if ChangeCause.during(ChangeCause(ChangeReason.FocusOut, ChangeSource.Keyboard))(close())
        then
          FocusTrap.continueTab(e)
        else
          e.preventDefault()
      case k =>
        RovingFocus
          .nextIndex(k, current, all.size, Orientation.Vertical)
          // Typing the start of an item's label jumps to it
          .orElse(typeahead.onKey(e, all.map(_.textContent), current))
          .foreach { i =>
            e.preventDefault()
            all(i).focus()
          }

  def render(
      menuId: String,
      entries: Seq[MenuEntry],
      labelledBy: Option[String],
      close: () => Boolean
  ): RxElement = div(
    id              -> menuId,
    cls             -> "uni-menu",
    role            -> "menu",
    tabindex        -> "-1",
    ariaOrientation -> "vertical",
    data("state")   -> "open",
    data("slot")    -> "menu",
    labelledBy.map(l => ariaLabelledby -> l).getOrElse(DomNode.empty),
    EventListenerBinding(
      "keydown",
      e =>
        e.currentTarget match
          case m: dom.Element if !Keyboard.isComposing(e.asInstanceOf[dom.KeyboardEvent]) =>
            onKey(m, e.asInstanceOf[dom.KeyboardEvent], close)
          case _ =>
    ),
    entries.map {
      case MenuSeparator =>
        div(role -> "separator", cls -> "uni-menu__separator", data("slot") -> "menu-separator")
      case item: MenuItem =>
        var disabledNow = false
        div(
          role         -> item.checked.map(_ => "menuitemcheckbox").getOrElse("menuitem"),
          cls          -> "uni-menu__item",
          data("slot") -> "menu-item",
          tabindex     -> "-1",
          AgentRegistry.attach(MenuList.itemBinding(item)),
          ariaDisabled ->
            item
              .disabledState
              .map { d =>
                disabledNow = d
                d.toString
              },
          item.checked.map(c => ariaChecked -> c.map(_.toString)).getOrElse(DomNode.empty),
          // The pointer moves focus, so the highlight and the keyboard position stay one
          EventListenerBinding(
            "pointermove",
            e =>
              e.currentTarget match
                case el: dom.HTMLElement if !disabledNow && !(el eq dom.document.activeElement) =>
                  el.focus()
                case _ =>
          ),
          EventListenerBinding(
            "click",
            e =>
              if !disabledNow then
                // Close first, so focus is back on the trigger before the action opens a dialog
                // A close that onChange refuses skips the action, as in AlertDialog
                ChangeCause.during(ChangeCause.of(ChangeReason.ItemPress, e)) {
                  if close() then
                    item.action()
                }
          ),
          span(cls -> "uni-menu__check", ariaHidden -> "true"),
          span(cls -> "uni-menu__label", item.content),
          item.shortcut.map(s => span(cls -> "uni-menu__shortcut", s)).getOrElse(DomNode.empty)
        )
    }
  )

end MenuList

/**
  * A dropdown list of commands that opens from a trigger button. Click, Enter, or Space on the
  * trigger toggles it; ArrowDown or ArrowUp opens it at the first or last item. Inside, the arrow
  * keys, Home, End, and typing a letter move between items; Escape, Tab, or a click outside closes
  * it.
  *
  * {{{
  *   val open = Rx.variable(false)
  *   Menu(open)(IconButton("More", Icons.more))(
  *     MenuItem("Rename").onSelect(() => rename()),
  *     MenuSeparator,
  *     MenuItem("Delete").onSelect(() => confirmDelete := true)
  *   )
  * }}}
  *
  * The list is `div.uni-menu` with `role="menu"`, rendered at the end of `<body>`; rows are
  * `div.uni-menu__item`.
  */
class Menu private (
    open: RxVar[Boolean],
    trigger: RxElement,
    entries: Seq[MenuEntry],
    side: Side,
    align: Align,
    changeHandler: ChangeHandler
) extends RxElement:

  private val menuId                          = Ids.next("uni-menu")
  private val triggerId                       = s"${menuId}-trigger"
  private val triggerRef: DomRef[dom.Element] = DomRef[dom.Element]()
  // Which end to focus when the menu opens: ArrowUp opens at the last item
  private var focusLast = false

  private def copy(
      side: Side = side,
      align: Align = align,
      changeHandler: ChangeHandler = changeHandler
  ): Menu = new Menu(open, trigger, entries, side, align, changeHandler)

  def withSide(s: Side): Menu   = copy(side = s)
  def withAlign(a: Align): Menu = copy(align = a)

  /** Called before the menu opens or closes; `cancel()` keeps it as it is. Key: `open`. */
  def onChange(f: ChangeHandler): Menu = copy(changeHandler = f)

  private def setOpen(v: Boolean, cause: ChangeCause): Boolean = ChangeGate.write(
    open,
    "open",
    v,
    cause,
    changeHandler
  )

  private def onTriggerKey(e: dom.KeyboardEvent): Unit =
    e.key match
      case "ArrowDown" | "ArrowUp" =>
        e.preventDefault()
        focusLast = e.key == "ArrowUp"
        setOpen(true, ChangeCause.of(ChangeReason.NavigationKey, e)): Unit
      case _ =>

  private def list(exit: Presence.Exit): RxElement = OverlaySurface(
    exit,
    MenuList.render(menuId, entries, Some(triggerId), () => setOpen(false, ChangeCause.current)),
    anchor = () => triggerRef.current.map(Rect.of),
    inside = el => el +: triggerRef.current.toSeq,
    side = side,
    align = align,
    offset = 4,
    modal = false,
    dismissOnOutside = true,
    onDismiss = () => setOpen(false, ChangeCause.current): Unit,
    initialFocus =
      el =>
        val target =
          if focusLast then
            MenuList.last(el)
          else
            MenuList.first(el)
        focusLast = false
        target.orElse(Some(el))
    ,
    returnFocus = true
  )

  private object agentBinding extends AgentBinding:
    override def kind: String           = "menu"
    override def priority: Int          = 1
    override def states: Seq[AnyRef]    = Seq(open)
    override def value: Option[String]  = Some(Popover.stateName(open.get))
    override def format(v: Any): String = Popover.stateName(v == true)

    override def actions: Seq[AgentAction] =
      if open.get then
        Seq(
          AgentAction("close", "Close the menu")(() =>
            setOpen(false, AgentAction.cause(ChangeReason.Press)): Unit
          )
        )
      else
        Seq(
          AgentAction("open", "Open the menu to list its items")(() =>
            setOpen(true, AgentAction.cause(ChangeReason.Press)): Unit
          )
        )

  end agentBinding

  override def render: RxElement = trigger(
    triggerRef.binding,
    AgentRegistry.attach(agentBinding),
    id           -> triggerId,
    ariaHaspopup -> "menu",
    ariaExpanded -> open.map(_.toString),
    // Only while open: a closed popup is not in the document, and the reference would dangle
    ariaControls  -> open.map(o => Option.when(o)(menuId)),
    data("state") -> open.map(Popover.stateName),
    EventListenerBinding(
      "click",
      e => setOpen(!open.get, ChangeCause.of(ChangeReason.Press, e)): Unit
    ),
    EventListenerBinding("keydown", e => onTriggerKey(e.asInstanceOf[dom.KeyboardEvent])),
    Presence(open)(exit => Portal.toBody(list(exit)))
  )

end Menu

object Menu:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(open: RxVar[Boolean])(trigger: RxElement)(entries: MenuEntry*): Menu =
    new Menu(open, trigger, entries, Side.Bottom, Align.Start, ChangeGate.noHandler)

/**
  * A [[Menu]] that opens at the pointer when `target` is right-clicked (or gets the keyboard's
  * context-menu key or Shift+F10), in place of the browser's own menu.
  *
  * {{{
  *   ContextMenu(messageView)(
  *     MenuItem("Copy").onSelect(() => Clipboard.writeText(text)),
  *     MenuItem("Quote").onSelect(() => quote(text))
  *   )
  * }}}
  */
class ContextMenu(target: RxElement)(entries: MenuEntry*) extends RxElement:
  private val menuId                         = Ids.next("uni-context-menu")
  private val open                           = Rx.variable(false)
  private val targetRef: DomRef[dom.Element] = DomRef[dom.Element]()
  private var at: Rect                       = Rect.point(0, 0)

  private def onContextMenu(e: dom.Event): Unit =
    e.preventDefault()
    at =
      e match
        // A keyboard-opened menu has no pointer position: open it at the target
        case m: dom.MouseEvent if m.clientX != 0 || m.clientY != 0 =>
          Rect.point(m.clientX, m.clientY)
        case _ =>
          targetRef.current.map(Rect.of).map(r => Rect.point(r.x, r.bottom)).getOrElse(at)
    // Reopen at the new position when the menu is already open
    open := false
    open := true

  private def list(exit: Presence.Exit): RxElement = OverlaySurface(
    exit,
    MenuList.render(
      menuId,
      entries,
      None,
      () =>
        open := false
        true
    ),
    anchor = () => Some(at),
    inside = el => Seq(el),
    side = Side.Bottom,
    align = Align.Start,
    offset = 0,
    modal = false,
    dismissOnOutside = true,
    onDismiss = () => open := false,
    initialFocus = el => MenuList.first(el).orElse(Some(el)),
    returnFocus = true
  )

  override def render: RxElement = target(
    targetRef.binding,
    EventListenerBinding("contextmenu", onContextMenu),
    Presence(open)(exit => Portal.toBody(list(exit)))
  )

end ContextMenu
