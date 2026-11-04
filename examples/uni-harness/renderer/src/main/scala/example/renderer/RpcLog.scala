package example.renderer

import example.api.{ChatApi, ChatMessage, ChatReply, Conversation}
import wvlet.uni.rx.Rx

import scala.scalajs.js

/** One RPC call, shown in the dock's Activity tab. */
case class RpcCall(seq: Int, method: String, millis: Double, ok: Boolean, at: Double)

/** The most recent RPC calls, newest first. */
class RpcLog(limit: Int = 40):
  val calls = Rx.variable(Seq.empty[RpcCall])

  private var seq = 0

  private def now(): Double = js.Dynamic.global.performance.now().asInstanceOf[Double]

  /** Record when `rx` emits or fails, with the time since this call. */
  def track[A](method: String)(rx: Rx[A]): Rx[A] =
    seq += 1
    val n                         = seq
    val start                     = now()
    def record(ok: Boolean): Unit =
      val call = RpcCall(n, method, now() - start, ok, js.Date.now())
      calls.update(cs => (call +: cs).take(limit))
    rx.tap(_ => record(ok = true)).tapOnFailure(_ => record(ok = false))

end RpcLog

/** A [[ChatApi]] decorator that records every call in an [[RpcLog]]. */
class TrackedChatApi(api: ChatApi, log: RpcLog) extends ChatApi:
  override def listConversations(): Rx[Seq[Conversation]] =
    log.track("listConversations")(api.listConversations())

  override def createConversation(): Rx[Conversation] =
    log.track("createConversation")(api.createConversation())

  override def listMessages(conversationId: String): Rx[Seq[ChatMessage]] =
    log.track("listMessages")(api.listMessages(conversationId))

  override def sendMessage(conversationId: String, text: String): Rx[ChatReply] =
    log.track("sendMessage")(api.sendMessage(conversationId, text))

end TrackedChatApi
