package snippets.dom.ui

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

// Names that docs/dom/ui.md uses without defining
def send(text: String): Unit  = ()
def close(): Unit             = ()
def rename(): Unit            = ()
def delete(): Unit            = ()
def openFile(item: Any): Unit = ()
val closeIcon: RxElement      = span("x")
val moreIcon: RxElement       = span("...")
val query                     = Rx.variable("")
val count                     = Rx.variable(0)
val dockOpen                  = Rx.variable(true)

class ChatList extends RxElement:
  override def render: RxElement = div("chats")

class DetailsView extends RxElement:
  override def render: RxElement = div("details")

class ActivityLog extends RxElement:
  override def render: RxElement = div("activity")

class ShellView extends RxElement:
  override def render: RxElement = div("shell")

object Icons:
  val activity: RxElement   = span("activity")
  val panelRight: RxElement = span("panel")

case class LogLine(id: Long, text: String)

object log:
  val lines = Rx.variable(Seq(LogLine(1, "started")))

case class Conversation(id: String, title: String)

object store:
  val conversations            = Rx.variable(Seq(Conversation("1", "Hello")))
  def newChat(): Unit          = ()
  def select(id: String): Unit = ()
