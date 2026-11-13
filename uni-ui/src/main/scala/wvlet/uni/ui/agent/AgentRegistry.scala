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
import wvlet.uni.rx.Cancelable
import wvlet.uni.ui.{ChangeCause, ChangeDetails, ChangeReason, ChangeSource}

import scala.collection.mutable
import scala.scalajs.js

/** A value an agent passed that the action cannot use; the act result reports `message`. */
private[ui] class AgentInputError(message: String) extends Exception(message)

/**
  * An action a component offers to agents.
  *
  * @param run
  *   makes the change, given the agent's value; throws [[AgentInputError]] for a bad value
  */
private[ui] case class AgentAction(
    name: String,
    description: String,
    input: Option[JSONObject],
    run: Option[String] => Unit
)

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
    Some(UiJson.choice("One of the listed items", labels)),
    v =>
      val label = v.getOrElse(throw AgentInputError(s"${name} needs a value"))
      val i     = labels.indexOf(label)
      if i < 0 then
        throw AgentInputError(s"No item '${label}'. Items: ${labels.mkString(", ")}")
      run(i)
  )

  /** The cause of a change an agent makes for `reason`. */
  def cause(reason: ChangeReason): ChangeCause = ChangeCause(reason, ChangeSource.Agent)

end AgentAction

/**
  * How a uni-ui component describes itself to [[UiAgent]]. A component adds
  * `AgentRegistry.attach(binding)` to its root element; the binding is live while the element is
  * mounted.
  */
private[ui] trait AgentBinding:
  /** The component kind, such as `select`. */
  def kind: String

  /**
    * When two components share an element, as a [[wvlet.uni.ui.Menu]] does with its trigger
    * button, agents see the one with the higher priority.
    */
  def priority: Int = 0

  /** The element this binding is attached to, while mounted. */
  var element: Option[dom.Element] = None

  def value: Option[String] = None

  def items: Seq[UiItem] = Nil

  /** The actions available now; [[UiAgent]] drops them while the component is disabled. */
  def actions: Seq[AgentAction] = Nil

  /** The `RxVar`s this component writes, so [[UiAgent.changes]] can name the component. */
  def states: Seq[AnyRef] = Nil

  /** Text for a value of one of [[states]], for the change feed. */
  def format(v: Any): String = String.valueOf(v)

  /** Subscriptions this binding keeps while mounted, such as on a list of options. */
  def watch(): Cancelable = Cancelable.empty

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
    var ref: Option[String] = None
    var watching: Cancelable = Cancelable.empty

  private val ElementKey = "__uniAgent"

  private val entries = mutable.LinkedHashSet.empty[Entry]
  private val byState = java.util.IdentityHashMap[AnyRef, Entry]()

  private var seq       = 0L
  private val listeners = mutable.ArrayBuffer.empty[UiChange => Unit]
  // The changes reported while an agent action runs, to tell whether the app canceled it
  private var tracked: Option[mutable.ArrayBuffer[ChangeDetails[Any]]] = None

  /** Registers `binding` while the element this modifier is placed on is mounted. */
  def attach(binding: AgentBinding): DomNode = RefBinding(Hook(binding))

  private class Hook(binding: AgentBinding) extends DomRef[dom.Element]:
    private var entry: Option[Entry] = None

    override def set(elem: dom.Element): Unit =
      super.set(elem)
      entry.foreach(remove)
      val e = Entry(binding, elem)
      binding.element = Some(elem)
      entries += e
      if entryOf(elem).forall(_.binding.priority <= binding.priority) then
        elem.asInstanceOf[js.Dynamic].updateDynamic(ElementKey)(e.asInstanceOf[js.Any])
      binding.states.foreach(s => byState.put(s, e))
      e.watching = binding.watch()
      entry = Some(e)

    override def clear(): Unit =
      super.clear()
      entry.foreach(remove)
      entry = None
      binding.element = None

  end Hook

  private def remove(e: Entry): Unit =
    e.watching.cancel
    entries -= e
    e.binding.states.foreach(s => if byState.get(s) eq e then byState.remove(s))
    val el = e.element.asInstanceOf[js.Dynamic]
    if el.selectDynamic(ElementKey).asInstanceOf[js.Any] eq e.asInstanceOf[js.Any] then
      // Another component on the same element takes over
      entries.filter(_.element eq e.element).maxByOption(_.binding.priority) match
        case Some(other) =>
          el.updateDynamic(ElementKey)(other.asInstanceOf[js.Any])
        case None =>
          js.special.delete(el, ElementKey)

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
            val label = AgentDom.slug(AgentDom.labelOf(e.element))
            val base  =
              if label.isEmpty then
                e.binding.kind
              else
                s"${e.binding.kind}:${label}"
            val taken = entries.flatMap(_.ref).toSet
            val r     = Iterator
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

  /** Called by `ChangeGate` once the app's handler has seen a change to `target`. */
  def report(target: AnyRef, d: ChangeDetails[Any]): Unit =
    tracked.foreach(_ += d)
    if !d.canceled && listeners.nonEmpty then
      val entry  = Option(byState.get(target)).filter(entries.contains)
      val secret = entry.exists(e => AgentDom.isSecret(e.element))
      def text(v: Any): String =
        if secret then
          AgentDom.Redacted
        else
          entry.fold(String.valueOf(v))(_.binding.format(v))
      seq += 1
      val change = UiChange(
        seq,
        entry.map(refOf),
        entry.map(_.binding.kind),
        d.key,
        text(d.previous),
        text(d.value),
        d.reason.name,
        d.source
      )
      listeners.toSeq.foreach(_(change))

end AgentRegistry

/** Reads what an agent sees of an element: its label, and whether it is hidden or secret. */
private[ui] object AgentDom:
  val Redacted = "<redacted>"

  /** The element's accessible name, roughly: aria-label, aria-labelledby, a label, or its text. */
  def labelOf(el: dom.Element): String =
    def attr(name: String): Option[String] = Option(el.getAttribute(name))
      .map(_.trim)
      .filter(_.nonEmpty)
    def textOf(n: dom.Node): String = Option(n.textContent).getOrElse("")
    def labelled: Option[String] = attr("aria-labelledby").map(
      _.split("\\s+")
        .flatMap(id => Option(dom.document.getElementById(id)))
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
      .getOrElse(textOf(el))
    collapse(raw).take(80)

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
    !el.isConnected || Iterator
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

  def isDisabled(el: dom.Element): Boolean =
    (el.asInstanceOf[js.Dynamic].selectDynamic("disabled"): Any) == true ||
      el.getAttribute("aria-disabled") == "true"

  /** The message of the closest `UiAgent.confirm`, if any. */
  def confirmation(el: dom.Element): Option[String] = Option(
    el.closest(s"[${UiAgent.ConfirmAttr}]")
  ).map(_.getAttribute(UiAgent.ConfirmAttr))

end AgentDom
