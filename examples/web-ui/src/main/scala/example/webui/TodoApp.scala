package example.webui

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

case class Item(id: Int, title: String, done: Boolean = false)

/**
  * A todo list built with RxElement. State lives in RxVars; markup that embeds an Rx re-renders in
  * place when the value changes, so there is no manual DOM update code.
  */
class TodoApp extends RxElement:
  private val items   = Rx.variable(Seq.empty[Item])
  private val newItem = Rx.variable("")
  private var nextId  = 0

  private def add(): Unit =
    val title = newItem.get.trim
    if title.nonEmpty then
      nextId += 1
      items.update(_ :+ Item(nextId, title))
      newItem := ""

  private def toggle(id: Int): Unit = items.update(
    _.map { i =>
      if i.id == id then
        i.copy(done = !i.done)
      else
        i
    }
  )

  private def remove(id: Int): Unit = items.update(_.filterNot(_.id == id))

  private def itemView(item: Item): RxElement = li(
    cls -> (
      if item.done then
        "done"
      else
        ""
    ),
    input(
      tpe      -> "checkbox",
      checked  -> item.done,
      onchange -> { () =>
        toggle(item.id)
      }
    ),
    span(item.title),
    button(
      onclick -> { () =>
        remove(item.id)
      },
      "×"
    )
  )

  override def render: RxElement = div(
    cls -> "todo-app",
    h1("Uni Todo"),
    form(
      onsubmit -> { (e: org.scalajs.dom.Event) =>
        e.preventDefault()
        add()
      },
      input(tpe  -> "text", placeholder -> "What needs doing?", value.bind(newItem)),
      button(tpe -> "submit", "Add")
    ),
    ul(items.map(_.map(itemView))),
    p(
      cls -> "summary",
      items.map { xs =>
        s"${xs.count(!_.done)} of ${xs.size} left"
      }
    )
  )

end TodoApp

object TodoApp:
  def main(args: Array[String]): Unit = TodoApp().renderTo("app")
