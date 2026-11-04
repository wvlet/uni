package example.server

import example.api.{ChatApi, ChatMessage, ChatReply, Conversation}
import wvlet.uni.rx.Rx

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
  * An in-memory [[ChatApi]]. The same instance backs the Electron app (over IPC) and the browser
  * app (over HTTP); conversations live until the process exits.
  *
  * The assistant is a mock that echoes the prompt after a short pause, so the example needs no API
  * key or network. Replace [[respond]] with a call to your model or agent to make it real.
  */
class ChatService extends ChatApi:
  private val conversations = mutable.LinkedHashMap.empty[String, Conversation]
  private val messages      = mutable.Map.empty[String, mutable.ArrayBuffer[ChatMessage]]
  private var nextId        = 0

  private def newId(prefix: String): String =
    nextId += 1
    s"${prefix}-${nextId}"

  private def now(): Long = js.Date.now().toLong

  // Start with one empty conversation; the renderer shows its welcome screen
  newConversation()

  private def newConversation(): Conversation =
    val c = Conversation(newId("c"), "New chat", now())
    conversations(c.id) = c
    messages(c.id) = mutable.ArrayBuffer.empty
    c

  override def listConversations(): Rx[Seq[Conversation]] = Rx.single(
    conversations.values.toSeq.sortBy(c => -c.updatedAt)
  )

  override def createConversation(): Rx[Conversation] = Rx.single(newConversation())

  override def listMessages(conversationId: String): Rx[Seq[ChatMessage]] = Rx.single(
    messages.get(conversationId).map(_.toSeq).getOrElse(Seq.empty)
  )

  override def sendMessage(conversationId: String, text: String): Rx[ChatReply] =
    val thread = messages.getOrElseUpdate(conversationId, mutable.ArrayBuffer.empty)
    val user   = ChatMessage(newId("m"), "user", text, now())
    thread += user
    Rx.future(respond(text))
      .map { answer =>
        val assistant = ChatMessage(newId("m"), "assistant", answer, now())
        thread += assistant
        // Name the conversation after its first prompt
        val prev  = conversations.getOrElse(conversationId, Conversation(conversationId, "", 0))
        val title =
          if prev.title == "New chat" || prev.title.isEmpty then
            text.linesIterator.next().take(40)
          else
            prev.title
        val updated = prev.copy(title = title, updatedAt = assistant.createdAt)
        conversations(conversationId) = updated
        ChatReply(user, assistant, updated)
      }

  /** The mock model: wait a moment, then echo. */
  private def respond(prompt: String): Future[String] =
    val p = Promise[String]()
    js.timers
      .setTimeout(600) {
        p.success(
          s"You said: “${prompt}”\n\nI am the mock model in ChatService.respond. Replace me with a call to your LLM or agent, and this reply arrives the same way: as a typed ChatApi result over ${"IPC or HTTP"}."
        )
      }
    p.future

end ChatService
