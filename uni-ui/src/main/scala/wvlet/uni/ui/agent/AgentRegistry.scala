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
package wvlet.uni.ui.agent

import org.scalajs.dom
import wvlet.uni.dom.{DomNode, DomRef, RefBinding}
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.rx.{Cancelable, RxVar}
import wvlet.uni.ui.{ChangeCause, ChangeDetails, ChangeHandler, ChangeReason, ChangeSource}

import scala.collection.mutable
import scala.scalajs.js
import scala.util.control.NonFatal

/** A value an agent passed that the action cannot use; the act result reports `message`. */
private[ui] class AgentInputError(message: String) extends Exception(message)

/**
  * An action a component offers to agents.
  *
  * @param run
  *   makes the change, given the agent's value; throws [[AgentInputError]] for a bad value
  * @param confirm
  *   the question to ask the human before running with the agent's value, if any, such as for a row
  *   marked by a list's `withAgentConfirm`
  */
private[ui] case class AgentAction(
    name: String,
    description: String,
    input: Option[JSONObject],
    run: Option[String] => Unit,
    confirm: Option[String] => Option[String] = _ => None
):
  def withConfirm(f: Option[String] => Option[String]): AgentAction = copy(confirm = f)

private[ui] object AgentAction:
  def apply(name: String, description: String)(run: () => Unit): AgentAction = AgentAction(
    name,
    description,
    None,
    _ => run()
  )

  /** `press`: clicks the element of `b`, as a person would, so the app's own listeners run too. */
  def press(b: AgentBinding, description: String = "Press it"): AgentAction =
    AgentAction("press", description) { () =>
      b.element
        .foreach {
          case h: dom.HTMLElement =>
            h.click()
          case _ =>
        }
    }

  /** An action that takes free text. */
  def text(name: String, description: String)(run: String => Unit): AgentAction = AgentAction(
    name,
    description,
    Some(UiJson.text("The text")),
    v => run(v.getOrElse(throw AgentInputError(s"${name} needs a value")))
  )

  /**
    * An action that takes one of `labels` (as given by [[AgentBinding.itemLabels]]); `run` gets its
    * index.
    */
  def choice(name: String, description: String, labels: Seq[String])(
      run: Int => Unit
  ): AgentAction = AgentAction(
    name,
    description,
    // A long list (a table's rows) would flood the schema; it takes a label as text instead
    Some(
      if labels.size <= maxChoices then
        UiJson.choice("One of the listed items", labels)
      else
        UiJson.text("The label of an item, as listed in items")
    ),
    v =>
      val label = v.getOrElse(throw AgentInputError(s"${name} needs a value"))
      val i     = labels.indexOf(label)
      if i < 0 then
        throw AgentInputError(
          s"No item '${label}'. Items: ${labels.take(maxChoices).mkString(", ")}"
        )
      run(i)
  )

  /** The most choices listed in a schema `enum`, matching the items a snapshot lists. */
  val maxChoices = 100

  /** The cause of a change an agent makes for `reason`. */
  def cause(reason: ChangeReason): ChangeCause = ChangeCause(reason, ChangeSource.Agent)

end AgentAction

/**
  * A mounted component that writes app state through `ChangeGate`: the `RxVar`s it writes and the
  * `onChange` handler it asks now. [[UiHistory]] reverts a change through the handler of the
  * component that owns the state at that time, not the one the change was made through.
  */
private[ui] trait StateOwner:
  /** The component kind, such as `select`. */
  def kind: String

  /** The `RxVar`s this component writes. */
  def states: Seq[AnyRef]

  /** The component's current `onChange` handler, or `ChangeGate.noHandler` when it has none. */
  def changeHandler: ChangeHandler

  /** The name [[UiHistory]] entries use, when the element's accessible name doesn't fit. */
  def label: Option[String] = None

  /** Text for a value of one of [[states]], for the change feed and the history. */
  def format(v: Any): String = String.valueOf(v)

end StateOwner

/**
  * How a uni-ui component describes itself to [[UiAgent]]. A component adds
  * `AgentRegistry.attach(binding)` to its root element; the binding is live while the element is
  * mounted.
  */
private[ui] trait AgentBinding extends StateOwner:
  /**
    * When two components share an element, as a [[wvlet.uni.ui.Menu]] does with its trigger button,
    * agents see the one with the higher priority.
    */
  def priority: Int = 0

  /** The element this binding is attached to, while mounted. */
  var element: Option[dom.Element] = None

  def value: Option[String] = None

  def items: Seq[UiItem] = Nil

  /** The actions available now; [[UiAgent]] drops them while the component is disabled. */
  def actions: Seq[AgentAction] = Nil

  /** True while people can't edit it, as a `readonly` field; [[UiAgent]] then drops its actions. */
  def readOnly: Boolean = false

  /** The name agents read, when the element's accessible name doesn't fit, as for a toast. */
  def name: Option[String] = None

  /**
    * False for a component whose text is its items or its value, such as a list or a select: it is
    * then named only by its own label, never by the text inside it.
    */
  def namedByText: Boolean = true

  /** The `RxVar`s this component writes, so [[UiAgent.changes]] can name the component. */
  def states: Seq[AnyRef] = Nil

  /** Subscriptions this binding keeps while mounted, such as on a list of options. */
  def watch(): Cancelable = Cancelable.empty

  /**
    * Roots in other windows whose components belong to this one, such as a Dock's detached panels.
    * Agents see them as more children of this component.
    */
  def windows: Seq[dom.Element] = Nil

end AgentBinding

private[ui] object AgentBinding:
  /** Labels for items, with ` (2)`, ` (3)`, ... added to repeats so that each one is unique. */
  def itemLabels(labels: Seq[String]): Seq[String] =
    val seen = mutable.Map.empty[String, Int]
    labels.map { l =>
      val n = seen.getOrElse(l, 0) + 1
      seen(l) = n
      if n == 1 then
        l
      else
        s"${l} (${n})"
    }

/** The registry of mounted components that [[UiAgent]] reads. */
private[ui] object AgentRegistry:

  private[agent] class Entry(val binding: AgentBinding, val element: dom.Element):
    var ref: Option[String]  = None
    var watching: Cancelable = Cancelable.empty

  private val ElementKey = "__uniAgent"

  private val entries = mutable.LinkedHashSet.empty[Entry]

  /**
    * A mounted component that owns state: an agent binding, or a component without one, such as a
    * Dock, that [[UiHistory]] still reverts through.
    */
  private[agent] class Owner(
      val state: StateOwner,
      val element: dom.Element,
      val agent: Option[Entry]
  )

  private val byState = java.util.IdentityHashMap[AnyRef, Owner]()

  private var seq       = 0L
  private val listeners = mutable.ArrayBuffer.empty[UiChange => Unit]
  private val recorders = mutable.ArrayBuffer.empty[Reported => Unit]
  // The changes reported while an agent action runs, to tell whether the app canceled it
  private var tracked: Option[mutable.ArrayBuffer[ChangeDetails[Any]]] = None

  /** Registers `binding` while the element this modifier is placed on is mounted. */
  def attach(binding: AgentBinding): DomNode = RefBinding(Hook(binding, Some(binding)))

  /**
    * Registers `owner` as the owner of its states while the element this modifier is placed on is
    * mounted, so [[UiHistory]] can revert its changes. Agents don't see it.
    */
  def own(owner: StateOwner): DomNode = RefBinding(Hook(owner, None))

  private class Hook(owner: StateOwner, binding: Option[AgentBinding]) extends DomRef[dom.Element]:
    private var mounted: Option[Owner] = None

    override def set(elem: dom.Element): Unit =
      super.set(elem)
      mounted.foreach(remove)
      val entry = binding.map { b =>
        val e = Entry(b, elem)
        b.element = Some(elem)
        entries += e
        if entryOf(elem).forall(_.binding.priority <= b.priority) then
          elem.asInstanceOf[js.Dynamic].updateDynamic(ElementKey)(e.asInstanceOf[js.Any])
        e
      }
      val o = Owner(owner, elem, entry)
      owner.states.foreach(s => byState.put(s, o))
      entry.foreach(e => e.watching = e.binding.watch())
      mounted = Some(o)

    override def clear(): Unit =
      super.clear()
      mounted.foreach(remove)
      mounted = None
      binding.foreach(_.element = None)

  end Hook

  private def remove(o: Owner): Unit =
    o.state
      .states
      .foreach(s =>
        if byState.get(s) eq o then
          byState.remove(s)
      )
    o.agent
      .foreach { e =>
        e.watching.cancel
        entries -= e
        val el = e.element.asInstanceOf[js.Dynamic]
        if el.selectDynamic(ElementKey).asInstanceOf[js.Any] eq e.asInstanceOf[js.Any] then
          // Another component on the same element takes over
          entries.filter(_.element eq e.element).maxByOption(_.binding.priority) match
            case Some(other) =>
              el.updateDynamic(ElementKey)(other.asInstanceOf[js.Any])
            case None =>
              js.special.delete(el, ElementKey)
      }

  def entryOf(el: dom.Element): Option[Entry] =
    val v = el.asInstanceOf[js.Dynamic].selectDynamic(ElementKey).asInstanceOf[js.UndefOr[Entry]]
    v.toOption.filter(entries.contains)

  def all: Seq[Entry] = entries.toSeq

  /** The ref of `e`: its [[UiAgent.ref]], or a unique one made from its kind and label. */
  def refOf(e: Entry): String =
    Option(e.element.getAttribute(UiAgent.RefAttr)).filter(_.nonEmpty) match
      case Some(r) =>
        r
      case None =>
        e.ref match
          case Some(r) =>
            r
          case None =>
            val label = AgentDom.slug(nameOf(e))
            val base  =
              if label.isEmpty then
                e.binding.kind
              else
                s"${e.binding.kind}:${label}"
            val taken = entries.flatMap(_.ref).toSet
            val r     =
              Iterator
                .from(1)
                .map(n =>
                  if n == 1 then
                    base
                  else
                    s"${base}-${n}"
                )
                .find(!taken.contains(_))
                .get
            e.ref = Some(r)
            r

  /** The name agents read for `e`. */
  def nameOf(e: Entry): String = e
    .binding
    .name
    .map(AgentDom.collapse(_).take(80))
    .getOrElse(AgentDom.labelOf(e.element, byText = e.binding.namedByText))

  def subscribe(f: UiChange => Unit): Cancelable =
    listeners += f
    Cancelable(() => listeners -= f)

  /** Runs `body` and returns the changes it made or tried to make. */
  def tracking(body: => Unit): Seq[ChangeDetails[Any]] =
    val saved = tracked
    val buf   = mutable.ArrayBuffer.empty[ChangeDetails[Any]]
    tracked = Some(buf)
    try body
    finally tracked = saved
    buf.toSeq

  /**
    * A committed change as [[UiHistory]] sees it.
    *
    * @param owner
    *   the mounted component that owns `target`, if any
    * @param gesture
    *   the pointer drag the change is part of, if any; see `ChangeGate.inGesture`
    */
  private[agent] class Reported(
      val target: RxVar[Any],
      val details: ChangeDetails[Any],
      val owner: Option[Owner],
      val gesture: Option[AnyRef]
  ):
    val secret: Boolean = owner.exists(o => AgentDom.isSecret(o.element))

    /** `v` as text for agents and history: formatted by the owner, or redacted when secret. */
    def text(v: Any): String =
      if secret then
        AgentDom.Redacted
      else
        owner.fold(String.valueOf(v))(_.state.format(v))

  /** Calls `f` with every committed change of a component that is not hidden from agents. */
  def record(f: Reported => Unit): Cancelable =
    recorders += f
    Cancelable(() => recorders -= f)

  /** The mounted component that owns `target`, if any. */
  def ownerOf(target: AnyRef): Option[Owner] = Option(byState.get(target)).filter(
    _.element.isConnected
  )

  /** Called by `ChangeGate` once the app's handler has seen a change to `target`. */
  def report(target: RxVar[?], d: ChangeDetails[Any], gesture: Option[AnyRef]): Unit =
    tracked.foreach(_ += d)
    if !d.canceled && (listeners.nonEmpty || recorders.nonEmpty) then
      val owner = Option(byState.get(target))
      // A component inside `UiAgent.hidden` stays out of the feed, as it does out of snapshots
      if !owner.exists(o => AgentDom.isHidden(o.element)) then
        val r = Reported(target.asInstanceOf[RxVar[Any]], d, owner, gesture)
        notify(recorders.toSeq, r, "UiHistory")
        if listeners.nonEmpty then
          // Only components agents see are named in the feed
          val entry = owner.flatMap(_.agent)
          seq += 1
          val change = UiChange(
            seq,
            entry.map(refOf),
            entry.map(_.binding.kind),
            d.key,
            r.text(d.previous),
            r.text(d.value),
            d.reason.name,
            d.source
          )
          notify(listeners.toSeq, change, "UiAgent change listener")

  end report

  // A failing listener must not stop the write the app's handler already accepted
  private def notify[A](fs: Seq[A => Unit], a: A, name: String): Unit = fs.foreach { f =>
    try
      f(a)
    catch
      case NonFatal(err) =>
        dom.console.warn(s"${name} failed: ${err.getMessage}")
  }

end AgentRegistry

/** Reads what an agent sees of an element: its label, and whether it is hidden or secret. */
private[ui] object AgentDom:
  val Redacted = "<redacted>"

  /**
    * The element's accessible name, roughly: aria-label, aria-labelledby, a label, or, when
    * `byText`, its text.
    */
  def labelOf(el: dom.Element, byText: Boolean = true): String =
    def attr(name: String): Option[String] = Option(el.getAttribute(name))
      .map(_.trim)
      .filter(_.nonEmpty)
    def textOf(n: dom.Node): String = Option(n.textContent).getOrElse("")
    def labelled: Option[String]    = attr("aria-labelledby").map(
      _.split("\\s+")
        // In the element's own document, which is another window's for a detached panel
        .flatMap(id => Option(Option(el.ownerDocument).getOrElse(dom.document).getElementById(id)))
        .map(textOf)
        .mkString(" ")
    )
    def fieldLabels: Option[String] = el
      .asInstanceOf[js.Dynamic]
      .selectDynamic("labels")
      .asInstanceOf[js.UndefOr[dom.NodeList[dom.Node]]]
      .toOption
      .filter(_ != null)
      .map(ls => (0 until ls.length).map(i => textOf(ls(i))).mkString(" "))
    val raw = attr("aria-label")
      .orElse(labelled)
      .orElse(fieldLabels.filter(_.trim.nonEmpty))
      .orElse(attr("placeholder"))
      .orElse(attr("title"))
      .getOrElse(
        if byText then
          textOf(el)
        else
          ""
      )
    collapse(raw).take(80)

  end labelOf

  def collapse(s: String): String = s.trim.replaceAll("\\s+", " ")

  def slug(s: String): String = s
    .toLowerCase
    .replaceAll("[^a-z0-9]+", "-")
    .stripPrefix("-")
    .stripSuffix("-")
    .take(40)
    .stripSuffix("-")

  /** Hidden from agents by itself: `UiAgent.hidden`, `hidden`, `inert`, or `aria-hidden`. */
  def hidesSubtree(el: dom.Element): Boolean =
    el.hasAttribute(UiAgent.HiddenAttr) || el.hasAttribute("hidden") || el.hasAttribute("inert") ||
      el.getAttribute("aria-hidden") == "true"

  /** True when `el` or an ancestor hides it from agents, or it is not in the document. */
  def isHidden(el: dom.Element): Boolean =
    !el.isConnected ||
      Iterator
        .iterate(el)(e =>
          e.parentNode match
            case p: dom.Element =>
              p
            case _ =>
              null
        )
        .takeWhile(_ != null)
        .exists(hidesSubtree)

  /** A password field, or inside `UiAgent.secret`. */
  def isSecret(el: dom.Element): Boolean =
    el.getAttribute("type") == "password" || el.closest(s"[${UiAgent.SecretAttr}]") != null

  /** A `readonly` field, or one marked `aria-readonly`. */
  def isReadOnly(el: dom.Element): Boolean =
    (el.asInstanceOf[js.Dynamic].selectDynamic("readOnly"): Any) == true ||
      el.getAttribute("aria-readonly") == "true"

  def isDisabled(el: dom.Element): Boolean =
    (el.asInstanceOf[js.Dynamic].selectDynamic("disabled"): Any) == true ||
      el.getAttribute("aria-disabled") == "true"

  /** The message of the closest `UiAgent.confirm`, if any. */
  def confirmation(el: dom.Element): Option[String] = Option(
    el.closest(s"[${UiAgent.ConfirmAttr}]")
  ).map(_.getAttribute(UiAgent.ConfirmAttr))

end AgentDom
