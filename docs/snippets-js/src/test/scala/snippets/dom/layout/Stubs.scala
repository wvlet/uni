package snippets.dom.layout

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

// Names that docs/dom/layout.md uses without defining
val count    = Rx.variable(0)
val dockOpen = Rx.variable(true)

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
