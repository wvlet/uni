package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.json.JSON.{JSONArray, JSONObject, JSONString}
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.*
import wvlet.uni.ui.agent.{AgentPolicy, UiAction, UiAgent, UiNode}

import scala.language.implicitConversions

/**
  * What an AI agent sees in the story: a `UiAgent` installed over the preview lists each component
  * with its ref, kind, label, value, and the actions it allows. Each action runs through the agent,
  * as an MCP client's call would, so the preview changes and the result shows here. The raw JSON is
  * the text `UiAgentApi.snapshot` returns.
  */
class AgentView(story: Rx[Story], stage: () => Option[dom.Element]) extends RxElement:
  private val nodes                  = Rx.variable(Seq.empty[UiNode])
  private val showJson               = Rx.variable(false)
  private val lastResult             = Rx.variable("")
  private var agent: Option[UiAgent] = None
  private var subscription           = Cancelable.empty
  private var timer: Int             = 0

  private def refresh(): Unit = agent.foreach(a => nodes := a.snapshot())

  override def onMount(node: Any): Unit =
    val a = UiAgent.install(AgentPolicy.allowAll, root = stage().getOrElse(dom.document.body))
    agent = Some(a)
    // People's changes and the story's own updates both change what the agent sees
    subscription = Cancelable.merge(
      a.subscribe(_ => refresh()),
      story.run(_ => dom.window.setTimeout(() => refresh(), 50))
    )
    timer = dom.window.setInterval(() => refresh(), 1000)
    refresh()

  override def beforeUnmount: Unit =
    subscription.cancel
    dom.window.clearInterval(timer)
    agent.foreach(_.uninstall())
    agent = None

  private def act(n: UiNode, action: String, value: Option[String]): Unit = agent.foreach { a =>
    a.act(n.ref, action, value)
      .run { r =>
        val v = value.map(x => s" \"${x}\"").getOrElse("")
        lastResult :=
          s"${n.ref} · ${action}${v} → ${r.status.name}${
              if r.message.nonEmpty then
                s": ${r.message}"
              else
                ""
            }"
        dom.window.setTimeout(() => refresh(), 50)
      }
  }

  /** The values an action takes, when its input schema lists them. */
  private def choices(a: UiAction): Seq[String] = a.input.toSeq.flatMap(schema => findEnum(schema))

  private def findEnum(o: JSONObject): Seq[String] =
    o.get("enum") match
      case Some(JSONArray(vs)) =>
        vs.collect { case JSONString(s) =>
          s
        }
      case _ =>
        o.v
          .collect { case (_, child: JSONObject) =>
            child
          }
          .flatMap(findEnum)

  private def takesText(a: UiAction): Boolean = a.input.isDefined && choices(a).isEmpty

  private def actionEditor(n: UiNode, a: UiAction): RxElement =
    val options = choices(a)
    if options.nonEmpty then
      div(
        cls -> "agent-action",
        span(cls -> "agent-action__name", a.name),
        options
          .take(8)
          .map(v =>
            button(
              cls       -> "agent-chip",
              titleAttr -> a.description,
              onclick   -> { () =>
                act(n, a.name, Some(v))
              },
              v
            )
          )
      )
    else if takesText(a) then
      val text = Rx.variable("")
      div(
        cls -> "agent-action",
        span(cls -> "agent-action__name", a.name),
        Input(text).withPlaceholder("text")(cls += "agent-input"),
        button(
          cls     -> "agent-chip",
          onclick -> { () =>
            act(n, a.name, Some(text.get))
          },
          "Send"
        )
      )
    else
      button(
        cls       -> "agent-chip agent-chip--action",
        titleAttr -> a.description,
        onclick   -> { () =>
          act(n, a.name, None)
        },
        a.name
      )
    end if

  end actionEditor

  private def nodeView(n: UiNode, depth: Int): RxElement = div(
    cls       -> "agent-node",
    styleAttr -> s"--depth: ${depth}",
    div(
      cls -> "agent-node__head",
      span(cls -> "agent-kind", n.kind),
      span(
        cls -> "agent-label",
        if n.label.nonEmpty then
          n.label
        else
          "(no label)"
      ),
      n.value.map(v => span(cls -> "agent-value", v)),
      when(n.disabled, span(cls -> "agent-flag", "disabled")),
      when(n.readOnly, span(cls -> "agent-flag", "read-only"))
    ),
    div(cls -> "agent-ref", n.ref),
    when(
      n.items.nonEmpty,
      div(
        cls -> "agent-items",
        n.items
          .take(8)
          .map(i =>
            span(
              cls -> (
                if i.selected then
                  "agent-item is-selected"
                else
                  "agent-item"
              ),
              i.label
            )
          ),
        when(n.items.size > 8 || n.moreItems > 0, span(cls -> "agent-item", "…"))
      )
    ),
    when(n.actions.nonEmpty, div(cls -> "agent-actions", n.actions.map(a => actionEditor(n, a)))),
    n.children.map(c => nodeView(c, depth + 1))
  )

  override def render: RxElement = div(
    cls -> "agent-view",
    div(
      cls -> "agent-toolbar",
      span(cls -> "agent-title", Icons.sparkle, "What an AI agent sees"),
      div(cls  -> "code-toolbar__spacer"),
      Tooltip(
        "The JSON that UiAgentApi.snapshot returns",
        trigger = Button(
          showJson.map(j =>
            if j then
              "Components"
            else
              "JSON"
          )
        ).ghost.small.onClick(() => showJson.update(!_))
      )
    ),
    div(
      cls -> "agent-body",
      Rx.join(showJson, nodes)
        .map { (json, ns) =>
          if json then
            pre(
              cls -> "agent-json",
              agent.map(_ => ns.map(_.toJSON.toJSON).mkString("[\n  ", ",\n  ", "\n]"))
            )
          else if ns.isEmpty then
            div(cls -> "agent-empty", "No components an agent can read here.")
          else
            div(ns.map(n => nodeView(n, 0)))
        }
    ),
    div(
      cls -> "agent-footer",
      lastResult.map(r =>
        if r.isEmpty then
          "Press an action to run it through the agent."
        else
          r
      )
    )
  )

end AgentView
