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
package wvlet.uni.dom.testing

import org.scalajs.dom
import wvlet.uni.rx.Rx

import scala.collection.mutable.ListBuffer

/** The rules behind [[checkPattern]]: one structure check and one key sequence per pattern. */
private[testing] object AriaChecks:

  // A keyboard check is a list of steps; the runner settles between them, so an overlay that
  // opens or closes has run its onMount (initial focus, Escape handling) before the next step
  private type Step = () => Unit

  def check(root: dom.Element, pattern: AriaPattern, keyboard: Boolean): Rx[Seq[AriaViolation]] =
    val c     = Checker(pattern.rulePrefix)
    val steps =
      pattern match
        case AriaPattern.Tabs =>
          tabs(c, root)
        case AriaPattern.Menu =>
          menu(c, root)
        case AriaPattern.MenuButton =>
          menuButton(c, root)
        case AriaPattern.Listbox =>
          listbox(c, root)
        case AriaPattern.Combobox =>
          combobox(c, root)
        case AriaPattern.Dialog =>
          dialog(c, root, "dialog")
        case AriaPattern.AlertDialog =>
          dialog(c, root, "alertdialog")
        case AriaPattern.Tree =>
          tree(c, root)
        case AriaPattern.Grid =>
          grid(c, root)
        case AriaPattern.Tooltip =>
          tooltip(c, root)
    if keyboard then
      run(c, steps).map(_ => c.violations)
    else
      Rx.single(c.violations)

  private def run(c: Checker, steps: List[Step]): Rx[Unit] =
    steps match
      case Nil =>
        Rx.single(())
      case step :: rest =>
        val before = c.count
        step()
        if c.count > before then
          // Later keys would start from the wrong place: report the first keyboard violation only
          Rx.single(())
        else
          settle().flatMap(_ => run(c, rest))

  private class Checker(prefix: String):
    private val found = ListBuffer.empty[AriaViolation]

    def fail(rule: String, el: dom.Element, message: String): Unit =
      found += AriaViolation(s"${prefix}/${rule}", describe(el), message)

    def count: Int                     = found.size
    def violations: Seq[AriaViolation] = found.toList

  // --- DOM helpers ---

  private val compositeRoles = Set(
    "tablist",
    "menu",
    "menubar",
    "listbox",
    "tree",
    "treegrid",
    "grid",
    "dialog",
    "alertdialog",
    "combobox"
  )

  // Roles whose accessible name can come from their text
  private val nameFromContent = Set(
    "button",
    "link",
    "tab",
    "option",
    "menuitem",
    "menuitemcheckbox",
    "menuitemradio",
    "treeitem",
    "row",
    "cell",
    "gridcell",
    "columnheader",
    "rowheader",
    "tooltip",
    "checkbox",
    "radio",
    "switch",
    "heading"
  )

  private val idRefAttributes = Seq(
    "aria-controls",
    "aria-labelledby",
    "aria-describedby",
    "aria-activedescendant",
    "aria-owns",
    "aria-details",
    "aria-errormessage"
  )

  private def all(nodes: dom.NodeList[dom.Element]): Seq[dom.Element] = (0 until nodes.length).map(
    nodes(_)
  )

  private def attr(el: dom.Element, name: String): Option[String] = Option(el.getAttribute(name))
    .map(_.trim)
    .filter(_.nonEmpty)

  private def ids(el: dom.Element, name: String): Seq[String] = attr(el, name)
    .toSeq
    .flatMap(_.split("\\s+"))

  private def byId(id: String): Option[dom.Element] = Option(dom.document.getElementById(id))

  def role(el: dom.Element): String = attr(el, "role")
    .map(_.split("\\s+").head)
    .getOrElse {
      el.tagName.toLowerCase match
        case "button" =>
          "button"
        case "dialog" =>
          "dialog"
        case "a" if el.hasAttribute("href") =>
          "link"
        case "textarea" =>
          "textbox"
        case "input" =>
          attr(el, "type").getOrElse("text") match
            case "checkbox" =>
              "checkbox"
            case "radio" =>
              "radio"
            case _ =>
              "textbox"
        case _ =>
          ""
    }

  def accessibleName(el: dom.Element): String =
    val labelled = ids(el, "aria-labelledby")
      .flatMap(byId)
      .map(_.textContent.trim)
      .filter(_.nonEmpty)
      .mkString(" ")
    if labelled.nonEmpty then
      labelled
    else
      attr(el, "aria-label")
        .orElse(
          Option(el.textContent).map(_.trim).filter(t => t.nonEmpty && nameFromContent(role(el)))
        )
        .orElse(attr(el, "title"))
        .orElse(
          Option(el.id)
            .filter(_.nonEmpty)
            .flatMap(id => Option(dom.document.querySelector(s"label[for='${id}']")))
            .map(_.textContent.trim)
            .filter(_.nonEmpty)
        )
        .getOrElse("")

  def describe(el: dom.Element): String =
    val tag  = el.tagName.toLowerCase
    val r    = attr(el, "role").map(r => s"[role=${r}]").getOrElse("")
    val id   = Option(el.id).filter(_.nonEmpty).map(i => s"#${i}").getOrElse("")
    val name = accessibleName(el)
    val text =
      if name.isEmpty then
        ""
      else if name.length > 30 then
        s" \"${name.take(30)}…\""
      else
        s" \"${name}\""
    s"${tag}${r}${id}${text}"

  /** `root` itself when it has one of `roles`, else its first descendant that does. */
  private def locate(root: dom.Element, roles: Set[String]): Option[dom.Element] =
    if roles(role(root)) then
      Some(root)
    else
      all(root.querySelectorAll("[role]")).find(el => roles(role(el)))

  private def parent(el: dom.Element): Option[dom.Element] =
    el.parentNode match
      case p: dom.Element =>
        Some(p)
      case _ =>
        None

  private def ancestors(el: dom.Element): Iterator[dom.Element] =
    Iterator.iterate(parent(el))(_.flatMap(parent)).takeWhile(_.isDefined).flatten

  /** The items with `roles` that belong to `container`, not to a composite widget nested in it. */
  private def items(container: dom.Element, roles: Set[String]): Seq[dom.Element] =
    val selector = roles.map(r => s"[role=${r}]").mkString(",")
    all(container.querySelectorAll(selector)).filter { el =>
      ancestors(el).find(p => p.eq(container) || compositeRoles(role(p))).exists(_.eq(container))
    }

  private def html(el: dom.Element): dom.HTMLElement = el.asInstanceOf[dom.HTMLElement]

  private def isTabbable(el: dom.Element): Boolean =
    html(el).tabIndex >= 0 && !el.hasAttribute("disabled") && !el.hasAttribute("hidden")

  private val focusableSelector =
    "a[href], button, input, select, textarea, [tabindex], [contenteditable=true]"

  /** The elements inside `container` that Tab reaches, in document order. */
  private def tabbables(container: dom.Element): Seq[dom.Element] = all(
    container.querySelectorAll(focusableSelector)
  ).filter(el => isTabbable(el) && html(el).offsetParent != null)

  private def active: dom.Element = dom.document.activeElement

  private def isClosed(el: dom.Element): Boolean =
    !dom.document.contains(el) || attr(el, "data-state").contains("closed") ||
      el.hasAttribute("hidden")

  private def press(key: String, shift: Boolean = false): Unit =
    val target = Option(active).getOrElse(dom.document.body)
    fireEvent.keyDown(target, key, shiftKey = shift): Unit

  private def focus(el: dom.Element): Unit =
    if !(active eq el) then
      html(el).focus()

  // --- Shared rules ---

  private def requireName(c: Checker, el: dom.Element, rule: String = "name"): Unit =
    if accessibleName(el).isEmpty then
      c.fail(rule, el, "needs an accessible name (aria-label, aria-labelledby, or text)")

  private def requireIdRefs(c: Checker, els: Seq[dom.Element]): Unit = els.foreach { el =>
    idRefAttributes.foreach { a =>
      ids(el, a)
        .filter(byId(_).isEmpty)
        .foreach(id => c.fail("id-ref", el, s"${a}=\"${id}\" points to no element"))
    }
  }

  private def requireOneTabStop(c: Checker, container: dom.Element, its: Seq[dom.Element]): Unit =
    val stops = its.filter(isTabbable)
    attr(container, "aria-activedescendant") match
      case Some(id) =>
        // Focus stays on the container, which names the active item
        if !container.hasAttribute("tabindex") then
          c.fail("focus", container, "uses aria-activedescendant, so it needs a tabindex")
        if !byId(id).exists(t => container.contains(t)) then
          c.fail("focus", container, s"aria-activedescendant=\"${id}\" is not one of its items")
        if stops.nonEmpty then
          c.fail(
            "focus",
            stops.head,
            "is a tab stop, but the container already takes the focus (aria-activedescendant)"
          )
      case None if container.hasAttribute("tabindex") && stops.isEmpty =>
      // A container that takes the focus and names no active item yet
      case None =>
        if stops.size != 1 then
          c.fail(
            "focus",
            container,
            s"needs exactly one tab stop among its items (roving tabindex), found ${stops.size}"
          )

  private def requireSingleSelection(c: Checker, container: dom.Element, its: Seq[dom.Element]) =
    if !attr(container, "aria-multiselectable").contains("true") then
      val n = its.count(el => attr(el, "aria-selected").contains("true"))
      if n > 1 then
        c.fail("one-selected", container, s"is single-select, but ${n} items have aria-selected")

  /** The item the keys act on: the active descendant, else the focused item. */
  private def current(container: dom.Element, its: Seq[dom.Element]): Option[dom.Element] = attr(
    container,
    "aria-activedescendant"
  ).flatMap(byId)
    .filter(t => its.exists(_ eq t))
    .orElse(its.find(_ eq active))
    .orElse(its.find(_.contains(active)))

  /** Where the keys go: the container when it names an active descendant, else the tab stop. */
  private def host(container: dom.Element, its: Seq[dom.Element]): dom.Element =
    if container.hasAttribute("aria-activedescendant") || !its.exists(isTabbable) then
      container
    else
      its.find(el => el eq active).orElse(its.find(isTabbable)).getOrElse(container)

  /**
    * Press `key`, then check that the item at `index()` moved to one of `expected(from)`. `index`
    * returns -1 when no item is current.
    */
  private def nav(c: Checker, rule: String, el: () => dom.Element, key: String, index: () => Int)(
      expected: Int => Set[Int]
  ): List[Step] =
    var from = -1
    List(
      () =>
        from = index()
        press(key)
      ,
      () =>
        val now = index()
        if !expected(from).contains(now) then
          c.fail(
            rule,
            el(),
            s"${key} from item ${from} should move to item ${expected(from)
                .toSeq
                .sorted
                .mkString(" or ")}, but the current item is ${now}"
          )
    )

  // --- Patterns ---

  private def missing(c: Checker, root: dom.Element, what: String): List[Step] =
    c.fail("role", root, s"contains no ${what}")
    Nil

  private def tabs(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("tablist")) match
      case None =>
        missing(c, root, "element with role=tablist")
      case Some(list) =>
        def tabList = items(list, Set("tab"))
        val ts      = tabList
        requireName(c, list)
        if ts.isEmpty then
          c.fail("tabs", list, "owns no role=tab")
        ts.foreach { t =>
          requireName(c, t, "tab-name")
          if !attr(t, "aria-selected").exists(Set("true", "false")) then
            c.fail("selected", t, "needs aria-selected=\"true\" or \"false\"")
        }
        val selected = ts.filter(t => attr(t, "aria-selected").contains("true"))
        if ts.nonEmpty && selected.size != 1 then
          c.fail("one-selected", list, s"needs exactly one selected tab, found ${selected.size}")
        selected
          .headOption
          .foreach { t =>
            ids(t, "aria-controls").headOption.flatMap(byId) match
              case Some(panel) if role(panel) == "tabpanel" =>
                requireName(c, panel, "panel-name")
                requireIdRefs(c, Seq(panel))
              case _ =>
                c.fail("controls", t, "needs aria-controls pointing to its role=tabpanel")
          }
        requireIdRefs(c, list +: ts)
        requireOneTabStop(c, list, ts)
        ts.filter(isTabbable)
          .find(t => !attr(t, "aria-selected").contains("true"))
          .foreach(t => c.fail("tab-stop", t, "is the tab stop, but the selected tab should be"))

        val n = ts.size
        if n < 2 then
          Nil
        else
          val vertical    = attr(list, "aria-orientation").contains("vertical")
          val (next, prv) =
            if vertical then
              ("ArrowDown", "ArrowUp")
            else
              ("ArrowRight", "ArrowLeft")
          val index =
            () => current(list, tabList).map(t => tabList.indexWhere(_ eq t)).getOrElse(-1)
          def go(key: String)(expected: Int => Set[Int]) =
            nav(c, "keyboard", () => list, key, index)(expected)
          List[Step](() => focus(host(list, ts))) ++ go(next)(i => Set((i + 1) % n)) ++
            go(prv)(i => Set((i - 1 + n) % n)) ++ go("End")(_ => Set(n - 1)) ++
            go("Home")(_ => Set(0))

  private val menuItemRoles = Set("menuitem", "menuitemcheckbox", "menuitemradio")

  private def menu(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("menu", "menubar")) match
      case None =>
        missing(c, root, "element with role=menu")
      case Some(m) =>
        def its = items(m, menuItemRoles)
        val is  = its
        if is.isEmpty then
          c.fail("items", m, "owns no menuitem")
        is.foreach { it =>
          requireName(c, it, "item-name")
          if role(it) != "menuitem" &&
            !attr(it, "aria-checked").exists(Set("true", "false", "mixed"))
          then
            c.fail("checked", it, s"is a ${role(it)}, so it needs aria-checked")
        }
        requireIdRefs(c, m +: is)
        if !m.contains(active) then
          c.fail("focus", m, "an open menu should hold the focus, on one of its items")

        val n = is.size
        if n < 2 then
          Nil
        else
          val horizontal =
            role(m) == "menubar" || attr(m, "aria-orientation").contains("horizontal")
          val (next, prv) =
            if horizontal then
              ("ArrowRight", "ArrowLeft")
            else
              ("ArrowDown", "ArrowUp")
          val index           = () => current(m, its).map(t => its.indexWhere(_ eq t)).getOrElse(-1)
          def enabled(i: Int) = !attr(is(i), "aria-disabled").contains("true")
          // Disabled items may be skipped or focused, and wrapping at the ends is optional
          def step(from: Int, dir: Int): Set[Int] =
            val plain   = Seq(from + dir).filter(i => i >= 0 && i < n)
            val wrapped = (1 to n).map(k => ((from + dir * k) % n + n) % n).find(enabled)
            val stay    =
              if from + dir < 0 || from + dir >= n then
                Seq(from)
              else
                Nil
            (plain ++ wrapped ++ stay).toSet
          val first = (0 until n).find(enabled).toSeq :+ 0
          val last  = (0 until n).reverse.find(enabled).toSeq :+ (n - 1)
          def go(key: String)(expected: Int => Set[Int]) =
            nav(c, "keyboard", () => m, key, index)(expected)
          // Where the focus goes after Escape is checked by MenuButton, which opens from its trigger
          val escape = List[Step](
            () => press("Escape"),
            () =>
              if !isClosed(m) then
                c.fail("escape", m, "Escape should close the menu")
          )
          val start = List[Step](() =>
            if index() < 0 then
              is.find(i => enabled(is.indexOf(i))).foreach(focus)
          )
          start ++ go(next)(step(_, 1)) ++ go(prv)(step(_, -1)) ++ go("End")(_ => last.toSet) ++
            go("Home")(_ => first.toSet) ++ (
              if role(m) == "menu" then
                escape
              else
                Nil
            )
        end if

  private def menuButton(c: Checker, root: dom.Element): List[Step] =
    val trigger =
      if root.hasAttribute("aria-haspopup") then
        Some(root)
      else
        Option(root.querySelector("[aria-haspopup]"))
    trigger match
      case None =>
        missing(c, root, "element with aria-haspopup")
      case Some(t) =>
        if !attr(t, "aria-haspopup").exists(Set("menu", "true")) then
          c.fail("haspopup", t, "a menu button needs aria-haspopup=\"menu\" (or \"true\")")
        requireName(c, t)
        if !attr(t, "aria-expanded").exists(Set("true", "false")) then
          c.fail("expanded", t, "needs aria-expanded=\"true\" or \"false\"")
        def popup = ids(t, "aria-controls").headOption.flatMap(byId)
        if attr(t, "aria-expanded").contains("true") && !popup.exists(p => role(p) == "menu") then
          c.fail("controls", t, "is expanded, so aria-controls should point to its role=menu")
        requireIdRefs(c, Seq(t))

        if !attr(t, "aria-expanded").contains("false") then
          Nil
        else
          List(
            () =>
              focus(t)
              // A native button turns Enter and Space into a click
              if t.tagName.equalsIgnoreCase("button") then
                html(t).click()
              else
                press("Enter")
            ,
            () =>
              val m = popup.filter(p => role(p) == "menu" && !isClosed(p))
              if !attr(t, "aria-expanded").contains("true") || m.isEmpty then
                c.fail("open", t, "Enter or Space should open the menu and set aria-expanded")
              else
                m.foreach { menu =>
                  val first = items(menu, menuItemRoles).headOption
                  if !first.exists(f => f eq active) && !first.exists(f => menu.contains(active))
                  then
                    c.fail("open-focus", menu, "opening the menu should focus its first item")
                }
            ,
            () => press("Escape"),
            () =>
              if !attr(t, "aria-expanded").contains("false") then
                c.fail("escape", t, "Escape should close the menu and set aria-expanded=\"false\"")
              else if !(active eq t) then
                c.fail("return-focus", t, "should get the focus back when the menu closes")
          )
        end if
    end match

  end menuButton

  private def listbox(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("listbox")) match
      case None =>
        missing(c, root, "element with role=listbox")
      case Some(lb) =>
        def options = items(lb, Set("option"))
        val os      = options
        requireName(c, lb)
        os.foreach(o => requireName(c, o, "option-name"))
        requireSingleSelection(c, lb, os)
        requireIdRefs(c, lb +: os)
        // A combobox keeps the focus for a listbox it controls; check that with Combobox
        val ownedByCombobox = Option(lb.id)
          .filter(_.nonEmpty)
          .exists(id =>
            all(dom.document.querySelectorAll(s"[aria-controls~='${id}']")).exists(el =>
              role(el) == "combobox"
            )
          )
        if ownedByCombobox then
          Nil
        else
          requireOneTabStop(c, lb, os)
          verticalNav(c, lb, () => options)

  /** ArrowDown then ArrowUp on a vertical list, which may or may not wrap at its ends. */
  private def verticalNav(
      c: Checker,
      container: dom.Element,
      its: () => Seq[dom.Element],
      keyTarget: () => dom.Element = () => null
  ): List[Step] =
    val n = its().size
    if n < 2 then
      Nil
    else
      val index = () => current(container, its()).map(t => its().indexWhere(_ eq t)).getOrElse(-1)
      def go(key: String)(expected: Int => Set[Int]) =
        nav(c, "keyboard", () => container, key, index)(expected)
      List[Step](() => focus(Option(keyTarget()).getOrElse(host(container, its())))) ++
        go("ArrowDown") { i =>
          if i < 0 then
            Set(0)
          else if i == n - 1 then
            Set(n - 1, 0)
          else
            Set(i + 1)
        } ++
        go("ArrowUp") { i =>
          if i <= 0 then
            Set(0, n - 1)
          else
            Set(i - 1)
        }

  private def combobox(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("combobox")) match
      case None =>
        missing(c, root, "element with role=combobox")
      case Some(cb) =>
        requireName(c, cb)
        if !attr(cb, "aria-expanded").exists(Set("true", "false")) then
          c.fail("expanded", cb, "needs aria-expanded=\"true\" or \"false\"")
        val popup    = ids(cb, "aria-controls").headOption.flatMap(byId)
        val expanded = attr(cb, "aria-expanded").contains("true")
        if expanded &&
          !popup.exists(p => Set("listbox", "tree", "grid", "dialog")(role(p)) && !isClosed(p))
        then
          c.fail("controls", cb, "is expanded, so aria-controls should point to its popup")
        attr(cb, "aria-activedescendant").foreach { id =>
          if !popup.exists(p => byId(id).exists(p.contains)) then
            c.fail("active-descendant", cb, s"aria-activedescendant=\"${id}\" is not in its popup")
        }
        requireIdRefs(c, cb +: popup.toSeq)
        popup.filter(p => expanded && role(p) == "listbox") match
          case None =>
            Nil
          case Some(lb) =>
            def options = items(lb, Set("option"))
            requireSingleSelection(c, lb, options)
            val n = options.size
            if n < 2 then
              Nil
            else
              // The focus stays in the input; aria-activedescendant names the option
              val index =
                () =>
                  attr(cb, "aria-activedescendant")
                    .flatMap(byId)
                    .map(t => options.indexWhere(_ eq t))
                    .getOrElse(-1)
              def go(key: String)(expected: Int => Set[Int]) =
                nav(c, "keyboard", () => cb, key, index)(expected)
              val keepsFocus: Step =
                () =>
                  if !(active eq cb) then
                    c.fail("focus", cb, "should keep the focus while the arrow keys move")
              List[Step](() => focus(cb)) ++
                go("ArrowDown") { i =>
                  if i < 0 then
                    Set(0)
                  else if i == n - 1 then
                    Set(n - 1, 0)
                  else
                    Set(i + 1)
                } ++
                go("ArrowUp") { i =>
                  if i <= 0 then
                    Set(0, n - 1, -1)
                  else
                    Set(i - 1)
                } :+ keepsFocus
            end if
        end match

  private def dialog(c: Checker, root: dom.Element, dialogRole: String): List[Step] =
    locate(root, Set(dialogRole)) match
      case None =>
        missing(c, root, s"element with role=${dialogRole}")
      case Some(d) =>
        requireName(c, d)
        if dialogRole == "alertdialog" && !attr(d, "aria-modal").contains("true") then
          c.fail("modal", d, "an alert dialog is modal: it needs aria-modal=\"true\"")
        requireIdRefs(c, Seq(d))
        if !d.contains(active) then
          c.fail("focus", d, "an open dialog should hold the focus")

        val trap =
          if tabbables(d).isEmpty then
            Nil
          else
            List[Step](
              () =>
                focus(tabbables(d).last)
                press("Tab")
              ,
              () =>
                if !(active eq tabbables(d).head) then
                  c.fail("tab-trap", d, "Tab on the last element should move to the first"),
              () => press("Tab", shift = true),
              () =>
                if !(active eq tabbables(d).last) then
                  c.fail("tab-trap", d, "Shift+Tab on the first element should move to the last")
            )
        trap ++
          List[Step](
            () => press("Escape"),
            () =>
              if !isClosed(d) then
                c.fail("escape", d, "Escape should close the dialog")
          )

  private def tree(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("tree")) match
      case None =>
        missing(c, root, "element with role=tree")
      case Some(t) =>
        def treeItems = items(t, Set("treeitem"))
        val ts        = treeItems
        requireName(c, t)
        if ts.isEmpty then
          c.fail("items", t, "owns no role=treeitem")
        val flat = t.querySelector("[role=group]") == null
        ts.foreach { it =>
          requireName(c, it, "item-name")
          if flat && !it.hasAttribute("aria-level") then
            c.fail("level", it, "a tree without role=group rows needs aria-level on each item")
        }
        requireSingleSelection(c, t, ts)
        requireIdRefs(c, t +: ts)
        requireOneTabStop(c, t, ts)

        val n = ts.size
        if n < 2 then
          Nil
        else
          val index =
            () => current(t, treeItems).map(x => treeItems.indexWhere(_ eq x)).getOrElse(-1)
          def go(key: String)(expected: Int => Set[Int]) =
            nav(c, "keyboard", () => t, key, index)(expected)
          // Right opens a closed row and Left closes an open one; Home leaves the first row current
          def toggle(key: String, to: String): List[Step] = List(
            () => press(key),
            () =>
              val now = current(t, treeItems).flatMap(attr(_, "aria-expanded"))
              if !now.contains(to) then
                c.fail("expand", t, s"${key} should set aria-expanded=\"${to}\" on the current row")
          )
          val openClose =
            attr(ts.head, "aria-expanded") match
              case Some("false") =>
                toggle("ArrowRight", "true") ++ toggle("ArrowLeft", "false")
              case Some("true") =>
                toggle("ArrowLeft", "false") ++ toggle("ArrowRight", "true")
              case _ =>
                Nil
          List[Step](() => focus(host(t, ts))) ++
            go("ArrowDown")(i => Set(math.min(i + 1, n - 1))) ++
            go("ArrowUp")(i => Set(math.max(i - 1, 0))) ++ go("End")(_ => Set(n - 1)) ++
            go("Home")(_ => Set(0)) ++ openClose

  end tree

  private val cellRoles = Set("gridcell", "columnheader", "rowheader")

  private def grid(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("grid", "treegrid")) match
      case None =>
        missing(c, root, "element with role=grid")
      case Some(g) =>
        def rows                                      = items(g, Set("row"))
        def cellsOf(r: dom.Element): Seq[dom.Element] = all(
          r.querySelectorAll(cellRoles.map(x => s"[role=${x}]").mkString(","))
        ).filter(cell => ancestors(cell).find(p => role(p) == "row").exists(_.eq(r)))
        def cells = rows.flatMap(cellsOf)
        val rs    = rows
        requireName(c, g)
        if rs.isEmpty then
          c.fail("rows", g, "owns no role=row")
        rs.foreach { r =>
          if cellsOf(r).isEmpty then
            c.fail("cells", r, "a grid row needs gridcell, columnheader, or rowheader cells")
        }
        requireSingleSelection(c, g, rs ++ cells)
        requireIdRefs(c, g +: (rs ++ cells))
        requireOneTabStop(c, g, rs ++ cells)

        def now: Option[dom.Element] = current(g, rows ++ cells)
        def rowOf(x: dom.Element)    = rows.find(r => (r eq x) || r.contains(x))
        def rowIndex(): Int = now.flatMap(rowOf).map(r => rows.indexWhere(_ eq r)).getOrElse(-1)
        def colIndex(): Int = now
          .filter(x => cellRoles(role(x)))
          .map(x => rowOf(x).map(cellsOf).getOrElse(Nil).indexWhere(_ eq x))
          .getOrElse(-1)
        def colCount(): Int = now.flatMap(rowOf).map(cellsOf(_).size).getOrElse(0)

        // Two opposite keys move one step and back, starting away from the end we are at
        def pair(forward: String, back: String, index: () => Int, size: () => Int): Step =
          () =>
            val from = index()
            val n    = size()
            if from >= 0 && n > 1 then
              val (k1, k2, d) =
                if from < n - 1 then
                  (forward, back, 1)
                else
                  (back, forward, -1)
              press(k1)
              val mid = index()
              if mid != from + d then
                c.fail("keyboard", g, s"${k1} from ${from} should move to ${from + d}, got ${mid}")
              else
                press(k2)
                val end = index()
                if end != from then
                  c.fail(
                    "keyboard",
                    g,
                    s"${k2} from ${mid} should move back to ${from}, got ${end}"
                  )

        if rs.size < 2 then
          Nil
        else
          List[Step](
            () => focus(host(g, rs ++ cells)),
            () =>
              if rowIndex() < 0 then
                c.fail("keyboard", g, "focusing the grid should make a row or cell current"),
            pair("ArrowDown", "ArrowUp", rowIndex, () => rows.size),
            // Rows that take the focus as a whole have no column to move in
            () =>
              if colIndex() >= 0 then
                pair("ArrowRight", "ArrowLeft", colIndex, colCount)()
          )

  end grid

  private def tooltip(c: Checker, root: dom.Element): List[Step] =
    locate(root, Set("tooltip")) match
      case None =>
        missing(c, root, "element with role=tooltip")
      case Some(tip) =>
        val trigger = Option(tip.id)
          .filter(_.nonEmpty)
          .flatMap(id => Option(dom.document.querySelector(s"[aria-describedby~='${id}']")))
        if trigger.isEmpty then
          c.fail("described-by", tip, "the element it describes needs aria-describedby with its id")
        if tabbables(tip).nonEmpty || isTabbable(tip) && tip.hasAttribute("tabindex") then
          c.fail(
            "focusable",
            tip,
            "a tooltip never takes the focus; use a dialog for interactive content"
          )
        trigger match
          case None =>
            Nil
          case Some(t) =>
            List(
              () => fireEvent.keyDown(t, "Escape"): Unit,
              () =>
                if !isClosed(tip) && ids(t, "aria-describedby").contains(tip.id) then
                  c.fail("escape", tip, "Escape should hide the tooltip")
            )

end AriaChecks
