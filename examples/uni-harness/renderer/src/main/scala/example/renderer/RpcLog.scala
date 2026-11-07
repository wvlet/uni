package example.renderer

import example.api.{ChatApi, ChatEvent, ChatMessage, Conversation}
import wvlet.uni.rx.Rx

import scala.scalajs.js

/**
  * One RPC call, shown in the dock's Activity tab. `id` counts up from 1 in call order. A streaming
  * call also records when its first event arrived, and how many events it received.
  */
case class RpcCall(
    id: Long,
    method: String,
    millis: Double,
    ok: Boolean,
    firstMillis: Option[Double] = None,
    events: Int = 1
)

/** The most recent RPC calls, newest first. */
class RpcLog(limit: Int = 500):
  val calls = Rx.variable(Seq.empty[RpcCall])

  private var count = 0L

  private def now(): Double = js.Dynamic.global.performance.now().asInstanceOf[Double]

  /** Record when `rx` emits or fails, with the time since this call. */
  def track[A](method: String)(rx: Rx[A]): Rx[A] =
    val start                     = now()
    def record(ok: Boolean): Unit =
      count += 1
      val call = RpcCall(count, method, now() - start, ok)
      calls.update(cs => (call +: cs).take(limit))
    rx.tap(_ => record(ok = true)).tapOnFailure(_ => record(ok = false))

  /** Record a streaming call when `rx` completes or fails, with the time to its first event. */
  def trackStream[A](method: String)(rx: Rx[A]): Rx[A] =
    val start                     = now()
    var first                     = Option.empty[Double]
    var events                    = 0
    def record(ok: Boolean): Unit =
      count += 1
      val call = RpcCall(count, method, now() - start, ok, first, events)
      calls.update(cs => (call +: cs).take(limit))
    rx.tapOn {
        case scala.util.Success(_) =>
          events += 1
          if first.isEmpty then
            first = Some(now() - start)
        case scala.util.Failure(_) =>
          record(ok = false)
      }
      .concat(
        // Runs once the stream has ended without a failure
        Rx.fromSeq {
          record(ok = true)
          Seq.empty[A]
        }
      )

end RpcLog

/** A [[ChatApi]] decorator that records every call in an [[RpcLog]]. */
class TrackedChatApi(api: ChatApi, log: RpcLog) extends ChatApi:
  override def listConversations(): Rx[Seq[Conversation]] =
    log.track("listConversations")(api.listConversations())

  override def createConversation(): Rx[Conversation] =
    log.track("createConversation")(api.createConversation())

  override def listMessages(conversationId: String): Rx[Seq[ChatMessage]] =
    log.track("listMessages")(api.listMessages(conversationId))

  override def streamMessage(conversationId: String, text: String): Rx[ChatEvent] =
    log.trackStream("streamMessage")(api.streamMessage(conversationId, text))

end TrackedChatApi
