package example.api

import wvlet.uni.http.HttpAsyncClient
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.Rx

/** A conversation listed in the sidebar. */
case class Conversation(id: String, title: String, updatedAt: Long)

/** One message in a conversation. `role` is "user" or "assistant". */
case class ChatMessage(id: String, role: String, text: String, createdAt: Long)

/** The result of sending a message: the stored user message, the reply, and the renamed thread. */
case class ChatReply(user: ChatMessage, assistant: ChatMessage, conversation: Conversation)

/**
  * The chat service, shared by both sides. The server module implements it once; the renderer calls
  * it over Electron IPC in the desktop app and over HTTP (fetch) in the browser.
  */
trait ChatApi:
  def listConversations(): Rx[Seq[Conversation]]
  def createConversation(): Rx[Conversation]
  def listMessages(conversationId: String): Rx[Seq[ChatMessage]]
  def sendMessage(conversationId: String, text: String): Rx[ChatReply]

/**
  * A typed [[ChatApi]] over Uni RPC. Implementing the trait keeps every call compile-checked, so
  * the method-name strings live only here. sbt-uni's `uniHttpClients` generator writes this class
  * for you; the example spells it out, as `examples/rest-server` does.
  *
  * The transport comes from `http`: Electron IPC after `ElectronRenderer.install()`, fetch
  * otherwise.
  */
class ChatApiClient(http: HttpAsyncClient) extends ChatApi:
  private val rpc = RPCClient.of[ChatApi].newAsyncClient(http)

  override def listConversations(): Rx[Seq[Conversation]] = rpc.call("listConversations")
  override def createConversation(): Rx[Conversation]     = rpc.call("createConversation")
  override def listMessages(conversationId: String): Rx[Seq[ChatMessage]] = rpc.call(
    "listMessages",
    conversationId
  )

  override def sendMessage(conversationId: String, text: String): Rx[ChatReply] = rpc.call(
    "sendMessage",
    conversationId,
    text
  )

end ChatApiClient
