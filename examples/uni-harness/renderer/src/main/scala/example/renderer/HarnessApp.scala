package example.renderer

import example.api.{ChatApi, ChatMessage, ChatReply, Conversation}
import org.scalajs.dom
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.Rx

import scala.language.implicitConversions
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The renderer entry point, shared by the Electron app and the browser app. The only difference
  * between the two is the transport for `ChatApi` calls, chosen once at startup.
  */
object HarnessApp:

  @JSExportTopLevel("main")
  def main(): Unit =
    val window = dom.window.asInstanceOf[js.Dynamic]
    // The Electron preload script exposes `window.uniRPC`. In a browser it is absent, and Uni's
    // HTTP client keeps its default fetch channel, calling the same-origin web server.
    val transport =
      if js.typeOf(window.uniRPC) != "undefined" then
        ElectronRenderer.install()
        Transport.ElectronIPC
      else
        Transport.HttpFetch

    val root = dom.document.documentElement.classList
    if transport == Transport.ElectronIPC then
      root.add("is-electron")
      if js.typeOf(window.uniHarness) != "undefined" &&
        window.uniHarness.platform.asInstanceOf[String] == "darwin"
      then
        root.add("mac-inset")

    Shell(HarnessState(transport)).renderTo("app")

end HarnessApp

enum Transport(val label: String, val detail: String):
  case ElectronIPC extends Transport("Electron IPC", "ipcRenderer.invoke → ipcMain")
  case HttpFetch   extends Transport("HTTP", "fetch → Http.server on Node")

/** The sidebar views selected from the icon rail. */
enum View(val label: String):
  case Chats    extends View("Chats")
  case Files    extends View("Files")
  case Settings extends View("Settings")

enum Theme(val label: String):
  case System extends Theme("System")
  case Light  extends Theme("Light")
  case Dark   extends Theme("Dark")

/** One RPC call, shown in the dock's Activity tab. */
case class RpcCall(seq: Int, method: String, millis: Double, ok: Boolean, at: Double)

/**
  * UI state and the actions that change it. Layout state (which panes are open, the active view,
  * the theme) is kept in localStorage so it survives a reload; chat data comes from the server.
  */
class HarnessState(val transport: Transport):
  // Created lazily so it picks up the channel installed in HarnessApp.main
  private lazy val rpc = RPCClient.of[ChatApi].newAsyncClient(Http.client.newAsyncClient)

  val sidebarOpen = Storage.local("uni-harness.sidebarOpen", true)
  val dockOpen    = Storage.local("uni-harness.dockOpen", true)
  val view        = Storage.local("uni-harness.view", View.Chats.toString)
  val theme       = Storage.local("uni-harness.theme", Theme.System.toString)
  val dockTab     = Storage.local("uni-harness.dockTab", "details")

  val conversations = Rx.variable(Seq.empty[Conversation])
  val selected      = Rx.variable(Option.empty[String])
  val messages      = Rx.variable(Seq.empty[ChatMessage])
  val pending       = Rx.variable(false)
  val error         = Rx.variable(Option.empty[String])
  val query         = Rx.variable("")
  val activity      = Rx.variable(Seq.empty[RpcCall])

  /** The id of the reply that just arrived; it types itself in once. */
  var freshReply: Option[String] = None
  private var callSeq            = 0

  def currentView: View   = View.values.find(_.toString == view.get).getOrElse(View.Chats)
  def currentTheme: Theme = Theme.values.find(_.toString == theme.get).getOrElse(Theme.System)

  def selectedConversation: Rx[Option[Conversation]] = conversations.flatMap { cs =>
    selected.map(id => cs.find(c => id.contains(c.id)))
  }

  def toggleSidebar(): Unit = sidebarOpen.update(!_)
  def toggleDock(): Unit    = dockOpen.update(!_)

  /** Clicking the active rail icon folds the sidebar; clicking another one switches to it. */
  def openView(v: View): Unit =
    if currentView == v && sidebarOpen.get then
      sidebarOpen := false
    else
      view        := v.toString
      sidebarOpen := true

  def cycleTheme(): Unit =
    val all = Theme.values
    theme := all((all.indexOf(currentTheme) + 1) % all.length).toString

  def load(): Unit =
    call("listConversations", rpc.call[Seq[Conversation]]("listConversations")) { cs =>
      conversations := cs
      cs.headOption.foreach(c => select(c.id))
    }

  def select(id: String): Unit =
    selected := Some(id)
    call("listMessages", rpc.call[Seq[ChatMessage]]("listMessages", id)) { ms =>
      messages := ms
    }

  def newChat(): Unit =
    call("createConversation", rpc.call[Conversation]("createConversation")) { c =>
      conversations.update(c +: _)
      selected    := Some(c.id)
      messages    := Seq.empty
      view        := View.Chats.toString
      sidebarOpen := true
    }

  def send(text: String): Unit = selected
    .get
    .foreach { id =>
      // Show the prompt right away; the server echoes the stored message back with the reply
      val draft = ChatMessage("pending", "user", text, js.Date.now().toLong)
      messages.update(_ :+ draft)
      pending := true
      call("sendMessage", rpc.call[ChatReply]("sendMessage", id, text)) { reply =>
        pending := false
        if selected.get.contains(id) then
          freshReply = Some(reply.assistant.id)
          messages.update(ms => ms.filterNot(_.id == "pending") :+ reply.user :+ reply.assistant)
        conversations.update(cs => reply.conversation +: cs.filterNot(_.id == id))
      }
    }

  /**
    * Run an RPC call once: record it in the activity log, and report a failure in the UI instead of
    * the console only.
    */
  private def call[A](method: String, rx: Rx[A])(onSuccess: A => Unit): Unit =
    val start = dom.window.performance.now()
    callSeq += 1
    val seq                    = callSeq
    def log(ok: Boolean): Unit =
      val entry = RpcCall(seq, method, dom.window.performance.now() - start, ok, js.Date.now())
      activity.update(as => (entry +: as).take(40))
    rx.map { a =>
        log(ok = true)
        error := None
        onSuccess(a)
      }
      .recover { case e: Throwable =>
        log(ok = false)
        pending := false
        error   := Some(s"${method} failed: ${e.getMessage}")
      }
      .run()

end HarnessState

/** "just now", "5m", "3h", or a date, for sidebar timestamps. */
def ago(millis: Long): String =
  val s = (js.Date.now() - millis.toDouble) / 1000
  if s < 45 then
    "just now"
  else if s < 3600 then
    s"${(s / 60).toInt}m ago"
  else if s < 86400 then
    s"${(s / 3600).toInt}h ago"
  else
    new js.Date(millis.toDouble).toLocaleDateString()
