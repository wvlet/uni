package example.server

import example.api.{ChatApi, ChatEvent, ChatMessage, ChatReply, Conversation}
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxStream

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
  * An in-memory [[ChatApi]]. The same instance backs the Electron app (over IPC) and the browser
  * app (over HTTP); conversations live until the process exits.
  *
  * @param respond
  *   the model: prompt => the reply's text in parts, as the model writes it. The default is a mock
  *   that echoes a few words at a time, so the example needs no API key. Pass your LLM's or agent's
  *   token stream here, or wrap a model that answers at once with [[ChatService.atOnce]].
  * @param now
  *   the clock, in epoch milliseconds
  */
class ChatService(
    respond: String => Rx[String] = ChatService.mockModel,
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

  override def streamMessage(conversationId: String, text: String): RxStream[ChatEvent] =
    val thread = messages.getOrElseUpdate(conversationId, mutable.ArrayBuffer.empty)
    val user   = ChatMessage(newId("m"), "user", text, now())
    thread += user
    val answer = StringBuilder()

    // Store the reply once the model is done; Rx.single defers this until the deltas have passed
    def finish(): ChatEvent =
      val assistant = ChatMessage(newId("m"), "assistant", answer.result(), now())
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
      ChatEvent.Done(ChatReply(user, assistant, updated))

    RxStream(
      Rx.single[ChatEvent](ChatEvent.Started(user))
        .concat(
          respond(text).map { part =>
            answer ++= part
            ChatEvent.Delta(part)
          }
        )
        .concat(Rx.single(finish()))
    )

  end streamMessage

end ChatService

object ChatService:
  val untitled = "New chat"

  /** A conversation is named after its first non-blank prompt line, up to 40 characters. */
  def titleFor(current: String, prompt: String): String =
    if current != untitled && current.nonEmpty then
      current
    else
      prompt.linesIterator.map(_.trim).find(_.nonEmpty).map(_.take(40)).getOrElse(untitled)

  /** A model that returns its whole reply at once, as one part. */
  def atOnce(model: String => Future[String]): String => Rx[String] =
    prompt => Rx.future(model(prompt))

  /** Split a reply into the parts a token stream would deliver: words with their spacing. */
  def parts(text: String): Seq[String] =
    val out  = Seq.newBuilder[String]
    var from = 0
    for
      i <- text.indices
      if text(i).isWhitespace
    do
      out += text.substring(from, i + 1)
      from = i + 1
    if from < text.length then
      out += text.substring(from)
    out.result()

  /** The mock model: think for a moment, then echo a word every 30 ms. */
  def mockModel(prompt: String): Rx[String] =
    val words = parts(
      s"You said: “${prompt}”\n\nI am the mock model in ChatService. Replace me with your LLM's or agent's token stream, and this reply arrives the same way: as typed ChatEvents, streamed over IPC or HTTP by one RPC call."
    )
    Rx.delay(500, TimeUnit.MILLISECONDS)
      .flatMap(_ => Rx.intervalMillis(30).take(words.size))
      .scan(-1)((i, _) => i + 1)
      .map(words)

end ChatService
