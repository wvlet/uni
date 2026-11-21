package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.ChangeSource
import wvlet.uni.ui.agent.{AgentPolicy, UiAgent, UiChange}

import scala.language.implicitConversions

/**
  * The changes the example's components report, newest first: each `onChange` key, the old and new
  * value, the reason, and who made it (pointer, keyboard, app code, or an AI agent). It reads the
  * same feed `UiAgent.changes` serves, keeping the changes of the components in this example.
  */
class EventLog(stage: () => Option[dom.Element]) extends RxElement:
  import EventLog.Entry

  private val entries                = Rx.variable(Seq.empty[Entry])
  private val open                   = Rx.variable(true)
  private var agent: Option[UiAgent] = None
  private var known                  = Set.empty[String]
  private var subscription           = Cancelable.empty
  private var timer                  = 0

  // The refs of the components in this example, kept after they leave (a dialog that closes)
  private def learn(): Unit = agent.foreach { a =>
    known ++= a.snapshot().flatMap(_.flatten).map(_.ref)
  }

  override def onMount(node: Any): Unit =
    val a = UiAgent.install(AgentPolicy.readOnly, root = stage().getOrElse(dom.document.body))
    agent = Some(a)
    learn()
    timer = dom.window.setInterval(() => learn(), 1000)
    subscription = a.subscribe { c =>
      learn()
      if c.ref.exists(known.contains) then
        entries.update(es => (Entry(EventLog.now(), c) +: es).take(EventLog.limit))
    }

  override def beforeUnmount: Unit =
    subscription.cancel
    dom.window.clearInterval(timer)
    agent.foreach(_.uninstall())
    agent = None

  private def row(e: Entry): RxElement =
    val c = e.change
    div(
      cls -> "event-row",
      span(cls -> "event-time", e.time),
      span(cls -> "event-ref", c.ref.getOrElse("")),
      span(cls -> "event-key", c.key),
      span(cls -> "event-value", s"${c.previous} → ${c.value}"),
      span(
        cls -> s"event-source event-source--${c.source.toString.toLowerCase}",
        EventLog.label(c.source)
      ),
      when(c.reason.nonEmpty, span(cls -> "event-reason", c.reason))
    )

  override def render: RxElement = div(
    cls -> "event-log",
    div(
      cls -> "event-head",
      button(
        cls          -> "event-toggle",
        ariaExpanded -> open.map(_.toString),
        onclick      -> { () =>
          open.update(!_)
        },
        span(
          cls ->
            open.map(o =>
              if o then
                "event-chevron is-open"
              else
                "event-chevron"
            ),
          "›"
        ),
        "Events",
        entries.map(es => when(es.nonEmpty, span(cls -> "event-count", es.size.toString)))
      ),
      div(cls -> "event-spacer"),
      entries.map(es =>
        when(
          es.nonEmpty,
          button(
            cls     -> "event-clear",
            onclick -> { () =>
              entries := Seq.empty
            },
            "Clear"
          )
        )
      )
    ),
    Rx.join(open, entries)
      .map { (o, es) =>
        when(
          o,
          div(
            cls -> "event-list",
            if es.isEmpty then
              div(cls -> "event-empty", "Use the example: each change it reports shows here.")
            else
              div(es.map(row))
          )
        )
      }
  )

end EventLog

object EventLog:
  case class Entry(time: String, change: UiChange)

  val limit = 30

  def label(s: ChangeSource): String =
    s match
      case ChangeSource.Pointer =>
        "pointer"
      case ChangeSource.Keyboard =>
        "keyboard"
      case ChangeSource.Programmatic =>
        "app"
      case ChangeSource.Agent =>
        "agent"

  private def now(): String =
    val d = new scala.scalajs.js.Date()
    f"${d.getHours().toInt}%02d:${d.getMinutes().toInt}%02d:${d.getSeconds().toInt}%02d"
