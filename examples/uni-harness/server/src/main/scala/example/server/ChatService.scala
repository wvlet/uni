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
  * @param respond
  *   the model: prompt => reply. The default is a mock that echoes after a short pause, so the
  *   example needs no API key. Pass a call to your LLM or agent here; tests pass an instant one.
  * @param now
  *   the clock, in epoch milliseconds
  */
class ChatService(
    respond: String => Future[String] = ChatService.mockModel,
    now: () => Long = () => js.Date.now().toLong
) extends ChatApi:

  private val conversations = mutable.LinkedHashMap.empty[String, Conversation]
  private val messages      = mutable.Map.empty[String, mutable.ArrayBuffer[ChatMessage]]
  private var nextId        = 0

  private def newId(prefix: String): String =
    nextId += 1
    s"${prefix}-${nextId}"

  private def newConversation(): Conversation =
    val c = Conversation(newId("c"), ChatService.untitled, now())
    conversations(c.id) = c
    messages(c.id) = mutable.ArrayBuffer.empty
    c

  // Start with one empty conversation; the renderer shows its welcome screen
  newConversation()

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
        val prev = conversations.getOrElse(
          conversationId,
          Conversation(conversationId, ChatService.untitled, 0)
        )
        val updated = prev.copy(
          title = ChatService.titleFor(prev.title, text),
          updatedAt = assistant.createdAt
        )
        conversations(conversationId) = updated
        ChatReply(user, assistant, updated)
      }

end ChatService

object ChatService:
  val untitled = "New chat"

  /** A conversation is named after its first non-blank prompt line, up to 40 characters. */
  def titleFor(current: String, prompt: String): String =
    if current != untitled && current.nonEmpty then
      current
    else
      prompt.linesIterator.map(_.trim).find(_.nonEmpty).map(_.take(40)).getOrElse(untitled)

  /** The mock model: wait a moment, then echo. */
  def mockModel(prompt: String): Future[String] =
    val p = Promise[String]()
    js.timers
      .setTimeout(600) {
        p.success(
          s"You said: “${prompt}”\n\nI am the mock model in ChatService. Replace me with a call to your LLM or agent, and this reply arrives the same way: as a typed ChatApi result over IPC or HTTP."
        )
      }
    p.future

end ChatService
