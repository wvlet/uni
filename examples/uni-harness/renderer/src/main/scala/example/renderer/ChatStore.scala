package example.renderer

import example.api.{ChatApi, ChatEvent, ChatMessage, Conversation}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

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

  /** Conversations whose reply hasn't sent its first part yet. */
  val thinking = Rx.variable(Set.empty[String])

  /** True while the selected conversation waits for a reply. */
  val pending: Rx[Boolean] = Rx.join(sending, selected).map((s, sel) => sel.exists(s))

  /** True while the selected conversation waits for the first part of its reply. */
  val waitingForFirstPart: Rx[Boolean] = Rx.join(thinking, selected).map((t, sel) => sel.exists(t))

  val selectedConversation: Rx[Option[Conversation]] = conversations.flatMap { cs =>
    selected.map(id => cs.find(c => id.contains(c.id)))
  }

  /** Messages stored on the server, without the draft shown while a send is in flight. */
  val messageCount: Rx[Int] = messages.map(_.count(m => !ChatStore.isDraft(m)))

  private var drafts = 0

  // The reply each conversation is receiving, as it stands: a draft that grows with every part.
  // Kept per conversation, so a thread opened again mid-stream shows the reply so far.
  private var live = Map.empty[String, ChatMessage]

  // The draft id of each prompt confirmed in this thread, by its stored id. The thread keys bubbles
  // with it, so a confirmed prompt keeps the draft's DOM node and doesn't play its entrance again.
  private var draftKeys = Map.empty[String, String]

  /** The key that renders `m` in the thread: its draft's id once confirmed, else its own id. */
  def keyOf(m: ChatMessage): String = draftKeys.getOrElse(m.id, m.id)

  /** Load the conversations and open the most recent one. */
  def load(): Future[Unit] = run("listConversations", api.listConversations()) { cs =>
    conversations := cs
  }.flatMap(_ => conversations.get.headOption.map(c => select(c.id)).getOrElse(Future.unit))

  def select(id: String): Future[Unit] =
    if !selected.get.contains(id) then
      draftKeys = Map.empty
    selected := Some(id)
    run("listMessages", api.listMessages(id)) { ms =>
      // Responses can arrive out of order; keep only the one for the current selection
      if selected.get.contains(id) then
        messages := ms ++ live.get(id)
    }

  def newChat(): Future[Unit] =
    run("createConversation", api.createConversation()) { c =>
      conversations.update(c +: _)
      selected := Some(c.id)
      draftKeys = Map.empty
      messages := Seq.empty
    }

  /** Send a prompt in the selected conversation, or in a new one when none is selected. */
  def sendOrStart(text: String): Future[Unit] =
    if selected.get.isEmpty then
      newChat().flatMap(_ => send(text))
    else
      send(text)

  /**
    * Send a prompt in the selected conversation and show its reply as it streams in. Ignored while
    * that conversation waits for a reply.
    */
  def send(text: String): Future[Unit] =
    selected.get match
      case None =>
        Future.unit
      case Some(id) if sending.get(id) =>
        Future.unit
      case Some(id) =>
        // Show the prompt right away as a draft; `Started` replaces it with the stored message
        val draft = newDraft("user", text)
        messages.update(_ :+ draft)
        sending.update(_ + id)
        thinking.update(_ + id)
        var started = false

        def shown: Boolean = selected.get.contains(id)

        // Swap drafts for their stored messages in one update. A draft's key stays with the stored
        // message while the draft is shown, so its bubble isn't rebuilt; a thread reloaded
        // mid-stream already renders the stored message under its own id.
        def confirm(stored: (String, ChatMessage)*): Unit =
          val ms = messages.get
          stored.foreach { (draftId, m) =>
            if ms.exists(_.id == draftId) then
              draftKeys += m.id -> draftId
          }
          val replaced = stored.flatMap((draftId, m) => Seq(draftId, m.id)).toSet
          messages := ms.filterNot(m => replaced(m.id)) ++ stored.map(_._2)

        def settle(): Unit =
          sending.update(_ - id)
          thinking.update(_ - id)
          live -= id

        stream(
          "streamMessage",
          api.streamMessage(id, text),
          onFailure =
            () =>
              // Before `Started` the prompt never reached the server; after it, the partial
              // reply stays in view next to the error
              if !started then
                messages.update(_.filterNot(_.id == draft.id))
              settle()
        ) {
          case ChatEvent.Started(user) =>
            started = true
            if shown then
              confirm(draft.id -> user)
          case ChatEvent.Delta(part) =>
            thinking.update(_ - id)
            val reply =
              live.get(id) match
                case Some(r) =>
                  r.copy(text = r.text + part)
                case None =>
                  newDraft("assistant", part)
            live += id -> reply
            if shown then
              messages.update { ms =>
                if ms.exists(_.id == reply.id) then
                  ms.map(m =>
                    if m.id == reply.id then
                      reply
                    else
                      m
                  )
                else
                  ms :+ reply
              }
          case ChatEvent.Done(reply) =>
            val growing = live.get(id).map(_.id)
            settle()
            if shown then
              confirm(draft.id -> reply.user, growing.getOrElse("") -> reply.assistant)
            conversations.update(cs => reply.conversation +: cs.filterNot(_.id == id))
        }

  private def newDraft(role: String, text: String): ChatMessage =
    drafts += 1
    ChatMessage(s"${ChatStore.draftPrefix}${drafts}", role, text, js.Date.now().toLong)

  /**
    * Run a streaming call, handing each event to `onEvent`. The Future completes when the stream
    * ends or fails.
    */
  private def stream[A](method: String, rx: Rx[A], onFailure: () => Unit)(
      onEvent: A => Unit
  ): Future[Unit] =
    val done = Promise[Unit]()
    error := None
    RxRunner.run(rx) {
      case OnNext(a) =>
        onEvent(a.asInstanceOf[A])
      case OnError(e) =>
        onFailure()
        error := Some(explain(method, e))
        done.trySuccess(())
      case OnCompletion =>
        done.trySuccess(())
    }
    done.future

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
