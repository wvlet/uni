package example.renderer

import example.api.{ChatApi, ChatMessage, Conversation}
import wvlet.uni.rx.Rx

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
  * Chat data and the actions that change it, over any [[ChatApi]]: the typed RPC client in the app,
  * or a `ChatService` in tests. Each action returns a Future that completes once its result is
  * applied (or its failure is reported in [[error]]), so tests can await it; the UI ignores it.
  *
  * @param explain
  *   turns a failed call into the message shown in the UI
  */
class ChatStore(api: ChatApi, explain: (String, Throwable) => String = ChatStore.describe):
  val conversations = Rx.variable(Seq.empty[Conversation])
  val selected      = Rx.variable(Option.empty[String])
  val messages      = Rx.variable(Seq.empty[ChatMessage])

  /** Conversations waiting for a reply. Each one sends one prompt at a time. */
  val sending = Rx.variable(Set.empty[String])
  val error   = Rx.variable(Option.empty[String])
  val query   = Rx.variable("")

  /** True while the selected conversation waits for a reply. */
  val pending: Rx[Boolean] = Rx.join(sending, selected).map((s, sel) => sel.exists(s))

  val selectedConversation: Rx[Option[Conversation]] = conversations.flatMap { cs =>
    selected.map(id => cs.find(c => id.contains(c.id)))
  }

  /** Messages stored on the server, without the draft shown while a send is in flight. */
  val messageCount: Rx[Int] = messages.map(_.count(m => !ChatStore.isDraft(m)))

  private var freshReply: Option[String] = None
  private var drafts                     = 0

  /** True once for the reply that just arrived, so only it types itself in. */
  def takeFresh(messageId: String): Boolean =
    val fresh = freshReply.contains(messageId)
    if fresh then
      freshReply = None
    fresh

  /** Load the conversations and open the most recent one. */
  def load(): Future[Unit] = run("listConversations", api.listConversations()) { cs =>
    conversations := cs
  }.flatMap(_ => conversations.get.headOption.map(c => select(c.id)).getOrElse(Future.unit))

  def select(id: String): Future[Unit] =
    selected := Some(id)
    run("listMessages", api.listMessages(id)) { ms =>
      // Responses can arrive out of order; keep only the one for the current selection
      if selected.get.contains(id) then
        messages := ms
    }

  def newChat(): Future[Unit] =
    run("createConversation", api.createConversation()) { c =>
      conversations.update(c +: _)
      selected := Some(c.id)
      messages := Seq.empty
    }

  /**
    * Send a prompt in the selected conversation. Ignored while that conversation waits for a reply.
    */
  def send(text: String): Future[Unit] =
    selected.get match
      case None =>
        Future.unit
      case Some(id) if sending.get(id) =>
        Future.unit
      case Some(id) =>
        // Show the prompt right away as a draft; the reply replaces it with the stored message
        drafts += 1
        val draft = ChatMessage(
          s"${ChatStore.draftPrefix}${drafts}",
          "user",
          text,
          js.Date.now().toLong
        )
        messages.update(_ :+ draft)
        sending.update(_ + id)
        run(
          "sendMessage",
          api.sendMessage(id, text),
          onFailure =
            () =>
              sending.update(_ - id)
              messages.update(_.filterNot(_.id == draft.id))
        ) { reply =>
          sending.update(_ - id)
          if selected.get.contains(id) then
            freshReply = Some(reply.assistant.id)
            // Replace by id: a thread reloaded while the reply was on its way may already hold
            // the stored prompt
            val added = Set(draft.id, reply.user.id, reply.assistant.id)
            messages.update(ms => ms.filterNot(m => added(m.id)) :+ reply.user :+ reply.assistant)
          conversations.update(cs => reply.conversation +: cs.filterNot(_.id == id))
        }

  private def run[A](method: String, rx: Rx[A], onFailure: () => Unit = () => ())(
      onSuccess: A => Unit
  ): Future[Unit] =
    val done = Promise[Unit]()
    rx.map { a =>
        error := None
        onSuccess(a)
        done.trySuccess(())
      }
      .recover { case e: Throwable =>
        onFailure()
        error := Some(explain(method, e))
        done.trySuccess(())
      }
      .run()
    done.future

end ChatStore

object ChatStore:
  val draftPrefix                      = "draft-"
  def isDraft(m: ChatMessage): Boolean = m.id.startsWith(draftPrefix)

  def describe(method: String, e: Throwable): String =
    s"${method} failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
