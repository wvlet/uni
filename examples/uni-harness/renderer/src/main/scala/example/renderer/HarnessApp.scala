package example.renderer

import example.api.{ChatApi, ChatMessage, ChatReply, Conversation}
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.electron.ElectronRenderer
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
    // The Electron preload script exposes `window.uniRPC`. In a browser it is absent, and Uni's
    // HTTP client keeps its default fetch channel, calling the same-origin web server.
    val transport =
      if js.typeOf(dom.window.asInstanceOf[js.Dynamic].uniRPC) != "undefined" then
        ElectronRenderer.install()
        Transport.ElectronIPC
      else
        Transport.HttpFetch
    Shell(HarnessState(transport)).renderTo("app")

end HarnessApp

enum Transport(val label: String):
  case ElectronIPC extends Transport("Electron IPC")
  case HttpFetch   extends Transport("HTTP (fetch)")

/** The sidebar views selected from the icon rail. */
enum View(val label: String):
  case Chats    extends View("Chats")
  case Files    extends View("Files")
  case Settings extends View("Settings")

/**
  * UI state and the actions that change it. Layout state (which panes are open, the active view) is
  * kept in localStorage so it survives a reload; chat data comes from the server.
  */
class HarnessState(val transport: Transport):
  // Created lazily so it picks up the channel installed in HarnessApp.main
  private lazy val rpc = RPCClient.of[ChatApi].newAsyncClient(Http.client.newAsyncClient)

  val sidebarOpen = Storage.local("uni-harness.sidebarOpen", true)
  val dockOpen    = Storage.local("uni-harness.dockOpen", true)
  val view        = Storage.local("uni-harness.view", View.Chats.toString)

  val conversations = Rx.variable(Seq.empty[Conversation])
  val selected      = Rx.variable(Option.empty[String])
  val messages      = Rx.variable(Seq.empty[ChatMessage])
  val pending       = Rx.variable(false)
  val error         = Rx.variable(Option.empty[String])

  def currentView: View = View.values.find(_.toString == view.get).getOrElse(View.Chats)

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

  def load(): Unit =
    call(rpc.call[Seq[Conversation]]("listConversations")) { cs =>
      conversations := cs
      cs.headOption.foreach(c => select(c.id))
    }

  def select(id: String): Unit =
    selected := Some(id)
    call(rpc.call[Seq[ChatMessage]]("listMessages", id)) { ms =>
      messages := ms
    }

  def newChat(): Unit =
    call(rpc.call[Conversation]("createConversation")) { c =>
      conversations.update(c +: _)
      selected := Some(c.id)
      messages := Seq.empty
      view     := View.Chats.toString
    }

  def send(text: String): Unit = selected
    .get
    .foreach { id =>
      // Show the prompt right away; the server echoes the stored message back with the reply
      val draft = ChatMessage("pending", "user", text, js.Date.now().toLong)
      messages.update(_ :+ draft)
      pending := true
      call(rpc.call[ChatReply]("sendMessage", id, text)) { reply =>
        pending := false
        if selected.get.contains(id) then
          messages.update(ms => ms.filterNot(_.id == "pending") :+ reply.user :+ reply.assistant)
        conversations.update(cs => reply.conversation +: cs.filterNot(_.id == id))
      }
    }

  /** Run an RPC call once, reporting failures in the UI instead of the console only. */
  private def call[A](rx: Rx[A])(onSuccess: A => Unit): Unit = rx
    .map { a =>
      error := None
      onSuccess(a)
    }
    .recover { case e: Throwable =>
      pending := false
      error   := Some(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }
    .run()

end HarnessState
