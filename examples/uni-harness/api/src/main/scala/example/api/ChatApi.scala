package example.api

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
