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
import wvlet.uni.agent.UiAgentApi
import wvlet.uni.dom.{DomNode, DomRenderer}
import wvlet.uni.dom.all.data
import wvlet.uni.json.JSON.{JSONArray, JSONLong, JSONObject}
import wvlet.uni.mcp.MCPServer
import wvlet.uni.rx.{Cancelable, OnError, OnNext, Rx, RxRunner}
import wvlet.uni.ui.{AlertDialog, ChangeSource}

import scala.collection.mutable
import scala.scalajs.js
import scala.util.control.NonFatal

/** What an agent asks to do: run `action` on the component `ref` of `kind`. */
case class AgentTarget(ref: String, kind: String, action: String)

/**
  * What an agent may do once [[UiAgent.install]] runs. Snapshots list only the actions the policy
  * allows. Components marked [[UiAgent.hidden]] never appear, and actions on components marked
  * [[UiAgent.confirm]] wait for the human's answer.
  *
  * {{{
  *   UiAgent.install(AgentPolicy.allowAll)                            // the default
  *   UiAgent.install(AgentPolicy.readOnly)                            // snapshots only
  *   UiAgent.install(AgentPolicy.allow(t => t.kind != "button"))      // no presses
  * }}}
  *
  * @param confirm
  *   asks the human about an action marked [[UiAgent.confirm]]; the default shows an
  *   [[AlertDialog]]
  */
class AgentPolicy(val allows: AgentTarget => Boolean, val confirm: String => Rx[Boolean]):
  def withAllows(f: AgentTarget => Boolean): AgentPolicy = AgentPolicy(f, confirm)

  /** Replaces the confirmation dialog, such as with a fixed answer in tests. */
  def withConfirm(f: String => Rx[Boolean]): AgentPolicy = AgentPolicy(allows, f)

object AgentPolicy:
  val allowAll: AgentPolicy = AgentPolicy(_ => true, UiAgent.confirmWithDialog)
  val readOnly: AgentPolicy = AgentPolicy(_ => false, UiAgent.confirmWithDialog)

  def allow(f: AgentTarget => Boolean): AgentPolicy = AgentPolicy(f, UiAgent.confirmWithDialog)

/**
  * Lets an AI agent read and operate the uni-ui components on screen. Off until the app installs
  * it:
  *
  * {{{
  *   val agent = UiAgent.install()
  *   agent.snapshot()                         // the components, their values, and actions
  *   agent.act("select:region", "select", Some("Tokyo"))
  *   agent.changes(since = 0)                 // what the human changed meanwhile
  * }}}
  *
  * Every change an agent makes goes through the component's `onChange` handler with
  * `ChangeSource.Agent`, so the app can refuse it. [[api]] is the same API as JSON for transports:
  * [[installWebMcp]] registers it as WebMCP tools, and `MCPServer.withTools[UiAgentApi]` serves it
  * as MCP tools.
  *
  * @param root
  *   the element whose components the agent sees; overlays render into `document.body`, so the
  *   default is the body
  */
class UiAgent private (val policy: AgentPolicy, root: () => dom.Element):

  private val bufferSize = 500
  private val buffer     = mutable.ArrayDeque.empty[UiChange]
  private var subscription: Cancelable = AgentRegistry.subscribe { c =>
    if c.source != ChangeSource.Agent then
      buffer.append(c)
      if buffer.size > bufferSize then
        buffer.removeHead()
  }

  /** The components on screen, as trees that follow the page's nesting. */
  def snapshot(): Seq[UiNode] =
    val out = mutable.ArrayBuffer.empty[UiNode]
    collect(root(), out)
    out.toSeq

  private def collect(el: dom.Element, out: mutable.ArrayBuffer[UiNode]): Unit =
    if !AgentDom.hidesSubtree(el) then
      def children(into: mutable.ArrayBuffer[UiNode]): Unit =
        var c = el.firstElementChild
        while c != null do
          collect(c, into)
          c = c.nextElementSibling
      AgentRegistry.entryOf(el) match
        case Some(e) =>
          val kids = mutable.ArrayBuffer.empty[UiNode]
          children(kids)
          out += nodeOf(e, kids.toSeq)
        case None =>
          children(out)

  private val maxItems = 100

  private def nodeOf(e: AgentRegistry.Entry, children: Seq[UiNode]): UiNode =
    val b        = e.binding
    val ref      = AgentRegistry.refOf(e)
    val disabled = AgentDom.isDisabled(e.element)
    val items    = b.items
    val actions =
      if disabled then
        Nil
      else
        b.actions
          .filter(a => policy.allows(AgentTarget(ref, b.kind, a.name)))
          .map(a => UiAction(a.name, a.description, a.input))
    UiNode(
      ref = ref,
      kind = b.kind,
      label = AgentDom.labelOf(e.element),
      value =
        if AgentDom.isSecret(e.element) then
          b.value.map(_ => AgentDom.Redacted)
        else
          b.value
      ,
      items = items.take(maxItems),
      moreItems = math.max(0, items.size - maxItems),
      disabled = disabled,
      actions = actions,
      children = children
    )

  /**
    * The components matching every given filter, flattened: `kind`, text in the label or value
    * (case-insensitive), and a ref prefix.
    */
  def query(
      kind: Option[String] = None,
      text: Option[String] = None,
      ref: Option[String] = None
  ): Seq[UiNode] =
    val t = text.map(_.toLowerCase)
    snapshot()
      .flatMap(_.flatten)
      .filter { n =>
        kind.forall(_ == n.kind) && ref.forall(n.ref.startsWith) &&
        t.forall(s => n.label.toLowerCase.contains(s) || n.value.exists(_.toLowerCase.contains(s)))
      }
      .map(_.copy(children = Nil))

  private def find(ref: String): Option[(AgentRegistry.Entry, UiNode)] = AgentRegistry
    .all
    .filter(e => AgentRegistry.refOf(e) == ref && !AgentDom.isHidden(e.element))
    .filter(e => root().contains(e.element))
    .headOption
    .map(e => (e, nodeOf(e, Nil)))

  /**
    * Runs `action` on the component `ref`, as an agent. The result comes once the change is made,
    * refused, or, for a component marked [[UiAgent.confirm]], once the human answers.
    */
  def act(ref: String, action: String, value: Option[String] = None): Rx[ActResult] =
    find(ref) match
      case _ if UiAgent.confirming =>
        // Nothing runs while the human decides, so an agent cannot press the dialog's own buttons
        Rx.const(ActResult(ActStatus.Refused, "An action is waiting for the human's answer"))
      case None =>
        Rx.const(ActResult(ActStatus.NotFound, s"No component '${ref}' on screen"))
      case Some((e, node)) =>
        e.binding.actions.find(_.name == action) match
          case None =>
            Rx.const(ActResult(ActStatus.Refused, s"'${ref}' has no action '${action}'"))
          case Some(_) if node.disabled =>
            Rx.const(ActResult(ActStatus.Refused, s"'${ref}' is disabled"))
          case Some(_) if node.action(action).isEmpty =>
            Rx.const(ActResult(ActStatus.Refused, s"The policy does not allow '${action}'"))
          case Some(a) =>
            AgentDom.confirmation(e.element) match
              case None =>
                Rx.const(run(ref, a, value))
              case Some(message) =>
                confirmThenRun(message, ref, a, value)

  private def confirmThenRun(
      message: String,
      ref: String,
      a: AgentAction,
      value: Option[String]
  ): Rx[ActResult] =
    val result = Rx.variable(Option.empty[ActResult])
    UiAgent.confirming = true
    var answer = Cancelable.empty
    answer = RxRunner.run(policy.confirm(message)) {
      case OnNext(ok) if result.get.isEmpty =>
        UiAgent.confirming = false
        result :=
          Some(
            if ok == true then
              run(ref, a, value)
            else
              ActResult(ActStatus.Declined, "The human declined")
          )
        answer.cancel
      case OnError(err) if result.get.isEmpty =>
        UiAgent.confirming = false
        result := Some(ActResult(ActStatus.Declined, s"Confirmation failed: ${err.getMessage}"))
      case _ =>
    }
    result.filter(_.isDefined).map(_.get)

  private def run(ref: String, a: AgentAction, value: Option[String]): ActResult =
    try
      val changes = AgentRegistry.tracking {
        ChangeSource.withSource(ChangeSource.Agent)(a.run(value))
      }
      val after = find(ref).map(_._2)
      if changes.exists(_.canceled) then
        ActResult(ActStatus.Canceled, "The app canceled the change", after)
      else
        ActResult(ActStatus.Applied, "", after)
    catch
      case err: AgentInputError =>
        ActResult(ActStatus.Refused, err.getMessage)

  /**
    * Changes made by people and app code (not by agents) after the cursor `since`, oldest first,
    * with the cursor to pass next time. Keeps the latest 500.
    */
  def changes(since: Long = 0): (Seq[UiChange], Long) =
    val xs = buffer.filter(_.seq > since).toSeq
    (xs, xs.lastOption.map(_.seq).getOrElse(math.max(since, buffer.lastOption.fold(0L)(_.seq))))

  /** Calls `f` with every change components make to app state, including the agent's own. */
  def subscribe(f: UiChange => Unit): Cancelable = AgentRegistry.subscribe(f)

  /** This agent as [[UiAgentApi]], whose results are JSON text, for MCP and other transports. */
  lazy val api: UiAgentApi =
    new UiAgentApi:
      private def list(xs: Seq[UiNode]): String = JSONArray(xs.map(_.toJSON).toIndexedSeq).toJSON

      override def snapshot(): String = list(UiAgent.this.snapshot())

      override def query(kind: Option[String], text: Option[String], ref: Option[String]): String =
        list(UiAgent.this.query(kind, text, ref))

      override def act(ref: String, action: String, value: Option[String]): Rx[String] = UiAgent
        .this
        .act(ref, action, value)
        .map(_.toJSON.toJSON)

      override def changes(since: Long): String =
        val (xs, cursor) = UiAgent.this.changes(since)
        JSONObject(
          Seq(
            "changes" -> JSONArray(xs.map(_.toJSON).toIndexedSeq),
            "cursor"  -> JSONLong(cursor)
          )
        ).toJSON

  /**
    * Registers [[api]] as WebMCP tools when the browser offers `document.modelContext`, so an agent
    * in the browser can call them. Does nothing elsewhere. Cancel the result to unregister them.
    */
  def installWebMcp(): Cancelable =
    val mc = dom.document.asInstanceOf[js.Dynamic].selectDynamic("modelContext")
    if js.isUndefined(mc) || mc == null then
      Cancelable.empty
    else
      val server     = MCPServer().withName("uni-ui").withTools[UiAgentApi](api)
      val controller = dom.AbortController()
      server
        .tools
        .foreach { t =>
          val execute: js.Function1[js.Any, js.Promise[js.Any]] =
            args => UiAgent.callTool(server, t.name, args)
          val tool = js
            .Dynamic
            .literal(
              name = t.name,
              description = t.description.getOrElse(""),
              inputSchema = js.JSON.parse(t.inputSchema.toJSON),
              execute = execute,
              annotations = js.Dynamic.literal(readOnlyHint = t.name != "act")
            )
          try mc.registerTool(tool, js.Dynamic.literal(signal = controller.signal))
          catch
            case NonFatal(err) =>
              dom.console.warn(s"WebMCP registerTool(${t.name}) failed: ${err.getMessage}")
        }
      Cancelable(() => controller.abort())

  /** Stops recording changes. Components stay registered for other agents. */
  def uninstall(): Unit =
    subscription.cancel
    subscription = Cancelable.empty
    buffer.clear()

end UiAgent

object UiAgent:
  private[agent] val RefAttr     = "data-agent-ref"
  private[agent] val HiddenAttr  = "data-agent-hidden"
  private[agent] val SecretAttr  = "data-agent-secret"
  private[agent] val ConfirmAttr = "data-agent-confirm"

  // True while a confirmation dialog waits, so an agent cannot act again (or answer the dialog)
  private[agent] var confirming = false

  /**
    * Lets an agent see and operate the components under `root` (the body by default). See
    * [[AgentPolicy]] for what it may do.
    */
  def install(
      policy: AgentPolicy = AgentPolicy.allowAll,
      root: => dom.Element = dom.document.body
  ): UiAgent = new UiAgent(policy, () => root)

  /** A stable ref for this component, such as `UiAgent.ref("region")`. */
  def ref(name: String): DomNode = data("agent-ref") -> name

  /** Hides this element and everything inside it from agents. */
  def hidden: DomNode = data("agent-hidden") -> "true"

  /** Agents see this component, but its value reads `<redacted>`. Password fields always do. */
  def secret: DomNode = data("agent-secret") -> "true"

  /** An agent's actions on this component, or inside it, wait for the human to allow them. */
  def confirm(message: String): DomNode = data("agent-confirm") -> message

  /** Asks the human with an [[AlertDialog]] whether the agent may go ahead. */
  def confirmWithDialog(message: String): Rx[Boolean] =
    val open   = Rx.variable(true)
    val answer = Rx.variable(Option.empty[Boolean])
    val host   = dom.document.createElement("div")
    host.setAttribute(HiddenAttr, "true")
    dom.document.body.appendChild(host)
    var rendered = Cancelable.empty
    def finish(ok: Boolean): Unit =
      answer := Some(ok)
      // After the dialog's own click handling is done
      dom
        .window
        .setTimeout(
          () =>
            rendered.cancel
            host.remove()
          ,
          0
        )
    rendered = DomRenderer.renderTo(
      host,
      AlertDialog(open)(message)
        .withDescription("An AI agent asked to do this.")
        .withConfirmLabel("Allow")
        .withCancelLabel("Deny")
        .onConfirm(() => finish(true))
        .onCancel(() => finish(false))
    )
    answer.filter(_.isDefined).map(_.get)

  end confirmWithDialog

  /** Calls the MCP tool `name` of `server`, resolving with its MCP result object. */
  private def callTool(server: MCPServer, name: String, args: js.Any): js.Promise[js.Any] =
    val params = js.Dynamic.literal(name = name, arguments = Option(args).getOrElse(js.Object()))
    val line   = js
      .JSON
      .stringify(js.Dynamic.literal(jsonrpc = "2.0", id = 1, method = "tools/call", params = params))
    js.Promise[js.Any] { (resolve, reject) =>
      RxRunner.run(server.handleMessage(line)) {
        case OnNext(v) =>
          val response = js.JSON.parse(v.asInstanceOf[Option[String]].getOrElse("{}"))
          if js.isUndefined(response.selectDynamic("result")) then
            reject(js.JavaScriptException(response.selectDynamic("error")))
          else
            resolve(response.selectDynamic("result"))
        case OnError(e) =>
          reject(e)
        case _ =>
      }
    }

end UiAgent
